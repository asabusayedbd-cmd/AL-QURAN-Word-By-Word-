package com.example.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.ai.AiStudyUiState
import com.example.core.ai.GroundedAiRepository
import com.example.core.audio.AudioPlaybackState
import com.example.core.audio.HifzLoopConfig
import com.example.core.audio.QuranAudioController
import com.example.core.database.AudioReciterEntity
import com.example.core.database.AyahEntity
import com.example.core.database.AyahTafsirEntity
import com.example.core.database.AyahTranslationEntity
import com.example.core.database.HabitStreakEntity
import com.example.core.database.NoorDatabase
import com.example.core.database.ReadingProgressEntity
import com.example.core.database.SurahEntity
import com.example.core.database.TafsirSourceEntity
import com.example.core.database.UserBookmarkEntity
import com.example.core.database.UserNoteEntity
import com.example.core.database.WordEntity
import com.example.core.database.WordOccurrenceProjection
import com.example.core.firebase.FirestoreSyncRepository
import com.example.core.ibadah.StreakCalculator
import com.example.core.localization.BidiUtils
import com.example.core.localization.DetailedWordAnalysis
import com.example.core.localization.PreferencesRepository
import com.example.core.localization.QuranScriptType
import com.example.core.localization.ReaderViewMode
import com.example.core.localization.UserPreferences
import com.example.core.localization.WordMorphologyAnalyzer
import com.example.core.network.QuranSyncRepository
import com.example.ui.components.WordDisplayItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AyahDisplayBundle(
    val ayah: AyahEntity,
    val words: List<WordDisplayItem>,
    val translations: List<AyahTranslationEntity>,
    val isBookmarked: Boolean,
    val hasNote: Boolean
)

data class WordInspectorState(
    val word: WordEntity,
    val localizedMeaning: String,
    val ayahVerseKey: String,
    val surahName: String,
    val rootOccurrencesCount: Int,
    val rootOccurrences: List<WordOccurrenceProjection>,
    val analysis: DetailedWordAnalysis
)

data class ReaderUiState(
    val surah: SurahEntity? = null,
    val allSurahs: List<SurahEntity> = emptyList(),
    val localizedSurahName: String = "",
    val ayahs: List<AyahDisplayBundle> = emptyList(),
    val preferences: UserPreferences = UserPreferences(),
    val playbackState: AudioPlaybackState = AudioPlaybackState(),
    val reciters: List<AudioReciterEntity> = emptyList(),
    val tafsirSources: List<TafsirSourceEntity> = emptyList(),
    val selectedTafsirAyah: AyahEntity? = null,
    val selectedAyahTafsirs: List<AyahTafsirEntity> = emptyList(),
    val selectedAyahNoteText: String = "",
    val isFetchingTafsir: Boolean = false,
    val isDownloadingSurah: Boolean = false,
    val downloadError: String? = null,
    val aiStudyState: AiStudyUiState = AiStudyUiState.Idle,
    val inspectedWord: WordInspectorState? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModel(
    initialSurahId: Int,
    private val database: NoorDatabase,
    private val preferencesRepository: PreferencesRepository,
    private val syncRepository: QuranSyncRepository,
    private val audioController: QuranAudioController,
    private val aiRepository: GroundedAiRepository,
    private val firestoreSyncRepository: FirestoreSyncRepository
) : ViewModel() {

    private val quranDao = database.quranDao()
    private val userLibraryDao = database.userLibraryDao()

    private val _surahId = MutableStateFlow(initialSurahId.coerceIn(1, 114))
    private val _selectedTafsirAyah = MutableStateFlow<AyahEntity?>(null)
    private val _isFetchingTafsir = MutableStateFlow(false)
    private val _isDownloadingSurah = MutableStateFlow(false)
    private val _downloadError = MutableStateFlow<String?>(null)
    private val _aiStudyState = MutableStateFlow<AiStudyUiState>(AiStudyUiState.Idle)
    private val _inspectedWord = MutableStateFlow<WordInspectorState?>(null)
    private var aiJob: Job? = null

    private val ayahsFlow = _surahId.flatMapLatest { sid -> quranDao.observeAyahsBySurah(sid) }

    private val bundlesFlow = combine(
        ayahsFlow,
        preferencesRepository.preferencesFlow,
        quranDao.observeEnabledTranslationSources(),
        userLibraryDao.observeBookmarks(),
        userLibraryDao.observeNotes()
    ) { ayahs, prefs, enabledSources, bookmarks, notes ->
        Triple(ayahs, prefs, Triple(enabledSources, bookmarks, notes))
    }.flatMapLatest { (ayahs, prefs, meta) ->
        val (enabledSources, bookmarks, notes) = meta
        if (ayahs.isEmpty()) {
            flowOf(emptyList())
        } else {
            val ayahIds = ayahs.map { it.id }
            combine(
                quranDao.observeWordsForAyahs(ayahIds),
                quranDao.observeAyahTranslations(ayahIds)
            ) { allWords, allTranslations ->
                val wordIds = allWords.map { it.id }
                Pair(allWords, allTranslations) to wordIds
            }.flatMapLatest { (pair, wordIds) ->
                val (allWords, allTranslations) = pair
                val wtFlow = if (wordIds.isEmpty()) flowOf(emptyList()) else quranDao.observeWordTranslations(wordIds)
                combine(wtFlow, flowOf(Unit)) { wordTrs, _ ->
                    val enabledIds = enabledSources.map { it.id }.toSet()
                    val wordsByAyah = allWords.groupBy { it.ayahId }
                    val trsByAyah = allTranslations.groupBy { it.ayahId }
                    val wordTrById = wordTrs.groupBy { it.wordId }
                    val bookmarkedKeys = bookmarks.map { it.verseKey }.toSet()
                    val noteKeys = notes.map { it.verseKey }.toSet()

                    ayahs.map { ayah ->
                        val wList = wordsByAyah[ayah.id].orEmpty().map { w ->
                            val wTrs = wordTrById[w.id].orEmpty()
                            val meaning = wTrs.firstOrNull { it.languageCode == prefs.wbwLanguageCode }?.meaningText
                                ?: wTrs.firstOrNull { it.languageCode == "en" }?.meaningText
                                ?: wTrs.firstOrNull()?.meaningText
                                ?: ""
                            WordDisplayItem(w, meaning)
                        }
                        val ayahTrs = trsByAyah[ayah.id].orEmpty()
                            .filter { enabledIds.isEmpty() || it.sourceId in enabledIds }
                            .ifEmpty { trsByAyah[ayah.id].orEmpty() }
                        AyahDisplayBundle(
                            ayah = ayah,
                            words = wList,
                            translations = ayahTrs,
                            isBookmarked = ayah.verseKey in bookmarkedKeys,
                            hasNote = ayah.verseKey in noteKeys
                        )
                    }
                }
            }
        }
    }

    private val selectedTafsirsFlow = _selectedTafsirAyah.flatMapLatest { ayah ->
        if (ayah == null) flowOf(emptyList()) else quranDao.observeTafsirsForAyah(ayah.id)
    }

    private val selectedNoteFlow = _selectedTafsirAyah.flatMapLatest { ayah ->
        if (ayah == null) flowOf(null) else userLibraryDao.observeNoteForVerse(ayah.verseKey)
    }

    private val catalogFlow = combine(
        quranDao.observeAllSurahs(),
        quranDao.observeReciters(),
        quranDao.observeTafsirSources()
    ) { surahs, reciters, tafsirs ->
        Triple(surahs, reciters, tafsirs)
    }

    private val overlayFlow = combine(
        _selectedTafsirAyah,
        selectedTafsirsFlow,
        selectedNoteFlow,
        _isFetchingTafsir,
        _isDownloadingSurah,
        _downloadError,
        _aiStudyState,
        _inspectedWord
    ) { arr ->
        @Suppress("UNCHECKED_CAST")
        Triple(
            arr[0] as AyahEntity?,
            arr[1] as List<AyahTafsirEntity>,
            arr[2] as UserNoteEntity?
        ) to Triple(
            arr[3] as Boolean,
            arr[4] as Boolean,
            Triple(arr[5] as String?, arr[6] as AiStudyUiState, arr[7] as WordInspectorState?)
        )
    }

    private val readerCoreFlow = combine(
        _surahId.flatMapLatest { quranDao.observeSurah(it) },
        bundlesFlow,
        preferencesRepository.preferencesFlow,
        audioController.state
    ) { surah, bundles, prefs, playback ->
        Pair(surah to bundles, prefs to playback)
    }

    val uiState: StateFlow<ReaderUiState> = combine(
        readerCoreFlow,
        catalogFlow,
        overlayFlow
    ) { core, catalog, overlay ->
        val (surah, bundles) = core.first
        val (prefs, playback) = core.second
        val (allSurahs, reciters, tafsirSources) = catalog
        val (selectedTriple, flagsAndAi) = overlay
        val (selectedAyah, selectedTafsirs, selectedNote) = selectedTriple
        val (fetchingTafsir, downloadingSurah, errAiWord) = flagsAndAi
        val (dlErr, aiState, inspected) = errAiWord

        val localizedName = surah?.let {
            BidiUtils.extractLocalizedSurahName(it.localizedNamesJson, prefs.uiLanguageCode)
                .ifBlank { it.nameTransliteration }
        } ?: "Surah ${_surahId.value}"

        ReaderUiState(
            surah = surah,
            allSurahs = allSurahs,
            localizedSurahName = localizedName,
            ayahs = bundles,
            preferences = prefs,
            playbackState = playback,
            reciters = reciters,
            tafsirSources = tafsirSources,
            selectedTafsirAyah = selectedAyah,
            selectedAyahTafsirs = selectedTafsirs,
            selectedAyahNoteText = selectedNote?.noteText.orEmpty(),
            isFetchingTafsir = fetchingTafsir,
            isDownloadingSurah = downloadingSurah,
            downloadError = dlErr,
            aiStudyState = aiState,
            inspectedWord = inspected
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ReaderUiState()
    )

    fun loadSurah(surahId: Int) {
        _surahId.value = surahId.coerceIn(1, 114)
        _downloadError.value = null
    }

    fun recordAyahRead(ayah: AyahEntity) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existingProgress = userLibraryDao.getReadingProgress() ?: ReadingProgressEntity()
            val updatedProgress = existingProgress.copy(
                lastSurahId = ayah.surahId,
                lastAyahNumber = ayah.ayahNumber,
                lastVerseKey = ayah.verseKey,
                lastPageNumber = ayah.pageNumber,
                lastJuzNumber = ayah.juzNumber,
                khatamCompletedAyahs = (existingProgress.khatamCompletedAyahs + 1).coerceAtMost(6236),
                updatedAtEpochMs = now
            )
            userLibraryDao.upsertReadingProgress(updatedProgress)
            firestoreSyncRepository.upsertProgress(updatedProgress)

            val todayKey = StreakCalculator.todayDateKey(now)
            val existingStreak = userLibraryDao.getStreakByDate(todayKey) ?: HabitStreakEntity(dateKey = todayKey)
            val updatedStreak = existingStreak.copy(
                ayahsReadCount = existingStreak.ayahsReadCount + 1,
                updatedAtEpochMs = now
            )
            userLibraryDao.upsertStreak(updatedStreak)
            firestoreSyncRepository.upsertStreak(updatedStreak)
        }
    }

    fun downloadCurrentSurah() {
        val sid = _surahId.value
        viewModelScope.launch {
            _isDownloadingSurah.value = true
            _downloadError.value = null
            val prefs = uiState.value.preferences
            val res = syncRepository.downloadSurahForOffline(
                surahId = sid,
                wbwLanguages = listOf(prefs.wbwLanguageCode),
                reciterId = prefs.activeReciterId
            )
            res.onFailure { err ->
                _downloadError.value = err.localizedMessage ?: "Failed to fetch Surah from Quran.com API"
            }
            _isDownloadingSurah.value = false
        }
    }

    fun playAyah(ayah: AyahEntity) {
        val s = uiState.value
        val reciter = s.reciters.firstOrNull { it.id == s.preferences.activeReciterId }
            ?: AudioReciterEntity(7, "Mishary Rashid Alafasy", "مشاري العفاسي", "Murattal", "", true)
        audioController.playAyah(
            surahId = ayah.surahId,
            ayahNumber = ayah.ayahNumber,
            surahName = s.localizedSurahName,
            maxAyahInSurah = s.surah?.ayahCount ?: s.ayahs.size.coerceAtLeast(1),
            reciterId = reciter.id,
            reciterName = reciter.name
        )
        recordAyahRead(ayah)
    }

    fun inspectWord(item: WordDisplayItem, verseKey: String) {
        viewModelScope.launch {
            val allTranslations = quranDao.getWordTranslations(item.word.id)
            val analysis = WordMorphologyAnalyzer.analyze(item.word, allTranslations)
            val occurrences = if (item.word.rootArabic.isNotBlank()) {
                quranDao.getRootOccurrences(item.word.rootArabic)
            } else {
                emptyList()
            }
            val totalOccurrencesCount = if (item.word.rootArabic.isNotBlank()) {
                quranDao.getWordsByRoot(item.word.rootArabic).size.coerceAtLeast(occurrences.size)
            } else {
                1
            }
            _inspectedWord.value = WordInspectorState(
                word = item.word,
                localizedMeaning = item.localizedMeaning,
                ayahVerseKey = verseKey,
                surahName = uiState.value.localizedSurahName,
                rootOccurrencesCount = totalOccurrencesCount,
                rootOccurrences = occurrences,
                analysis = analysis
            )
        }
    }

    fun dismissWordInspector() {
        _inspectedWord.value = null
    }

    fun playInspectedWord(speed: Float = 1.0f) {
        val inspected = _inspectedWord.value ?: return
        val parts = inspected.ayahVerseKey.split(":")
        val sId = parts.getOrNull(0)?.toIntOrNull() ?: _surahId.value
        val aNum = parts.getOrNull(1)?.toIntOrNull() ?: 1
        audioController.playSingleWord(
            wordAudioUrl = inspected.word.audioUrl,
            fallbackSurahId = sId,
            fallbackAyah = aNum,
            wordPosition = inspected.word.position,
            speed = speed
        )
    }

    fun toggleBookmark(ayah: AyahEntity) {
        viewModelScope.launch {
            val existing = userLibraryDao.getBookmarkByVerseKey(ayah.verseKey)
            if (existing != null) {
                userLibraryDao.deleteBookmark(existing.id)
                firestoreSyncRepository.deleteBookmark(existing.id)
            } else {
                val now = System.currentTimeMillis()
                val bookmark = UserBookmarkEntity(
                    id = "bm_${ayah.verseKey.replace(':', '_')}",
                    verseKey = ayah.verseKey,
                    surahId = ayah.surahId,
                    ayahNumber = ayah.ayahNumber,
                    collectionId = "favorites",
                    label = "${uiState.value.localizedSurahName} ${ayah.verseKey}",
                    createdAtEpochMs = now,
                    updatedAtEpochMs = now
                )
                userLibraryDao.insertBookmark(bookmark)
                firestoreSyncRepository.upsertBookmark(bookmark)
            }
        }
    }

    fun openTafsir(ayah: AyahEntity) {
        _selectedTafsirAyah.value = ayah
        _aiStudyState.value = AiStudyUiState.Idle
    }

    fun closeTafsir() {
        _selectedTafsirAyah.value = null
    }

    fun selectTafsirSource(source: TafsirSourceEntity) {
        val ayah = _selectedTafsirAyah.value ?: return
        viewModelScope.launch {
            preferencesRepository.setActiveTafsirSourceId(source.id)
            _isFetchingTafsir.value = true
            syncRepository.fetchAyahTafsirIfMissing(
                ayahId = ayah.id,
                verseKey = ayah.verseKey,
                tafsirId = source.id,
                languageCode = source.languageCode,
                tafsirName = source.tafsirName
            )
            _isFetchingTafsir.value = false
        }
    }

    fun saveNoteForSelectedAyah(noteText: String) {
        val ayah = _selectedTafsirAyah.value ?: return
        val trimmed = noteText.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val note = UserNoteEntity(
                id = "note_${ayah.verseKey.replace(':', '_')}",
                verseKey = ayah.verseKey,
                surahId = ayah.surahId,
                ayahNumber = ayah.ayahNumber,
                noteText = trimmed,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
            userLibraryDao.insertNote(note)
            firestoreSyncRepository.upsertNote(note)
        }
    }

    fun askAiAboutSelectedAyah(question: String) {
        val s = uiState.value
        val ayah = s.selectedTafsirAyah ?: return
        val bundle = s.ayahs.firstOrNull { it.ayah.id == ayah.id }
        val trs = bundle?.translations.orEmpty().map { "${it.translatorName}: ${it.translatedText}" }
        val tafsirs = s.selectedAyahTafsirs.map {
            "${it.tafsirName}: ${it.tafsirHtmlOrMarkdown.replace(Regex("<[^>]+>"), " ").take(1600)}"
        }
        aiJob?.cancel()
        aiJob = viewModelScope.launch {
            aiRepository.askAboutAyahStream(
                surahName = s.localizedSurahName,
                verseKey = ayah.verseKey,
                arabicText = ayah.textUthmani,
                verifiedTranslations = trs,
                loadedTafsirExcerpts = tafsirs,
                userQuestion = question,
                uiLanguageName = s.preferences.uiLanguageCode
            ).collect { state ->
                _aiStudyState.value = state
            }
        }
    }

    fun resetAiState() {
        aiJob?.cancel()
        _aiStudyState.value = AiStudyUiState.Idle
    }

    fun toggleViewMode(mode: ReaderViewMode) = viewModelScope.launch {
        preferencesRepository.setReaderViewMode(mode)
    }

    fun setScriptType(script: QuranScriptType) = viewModelScope.launch {
        preferencesRepository.setScriptType(script)
    }

    fun setTajweedEnabled(enabled: Boolean) = viewModelScope.launch {
        preferencesRepository.setTajweedEnabled(enabled)
    }

    fun setShowTransliteration(show: Boolean) = viewModelScope.launch {
        preferencesRepository.setShowTransliteration(show)
    }

    fun setArabicFontSize(sizeSp: Float) = viewModelScope.launch {
        preferencesRepository.setArabicFontSize(sizeSp)
    }

    fun setWbwFontSize(sizeSp: Float) = viewModelScope.launch {
        preferencesRepository.setWbwFontSize(sizeSp)
    }

    fun setTranslationFontSize(sizeSp: Float) = viewModelScope.launch {
        preferencesRepository.setTranslationFontSize(sizeSp)
    }

    fun togglePlayPause() = audioController.togglePlayPause()
    fun previousAyah() = audioController.skipToPreviousAyah()
    fun nextAyah() = audioController.skipToNextAyah()
    fun dismissAudioBar() = audioController.stopAndDismiss()
    fun updateHifzConfig(transform: (HifzLoopConfig) -> HifzLoopConfig) = audioController.updateHifzConfig(transform)
    fun selectReciter(reciter: AudioReciterEntity) {
        viewModelScope.launch { preferencesRepository.setActiveReciterId(reciter.id) }
        audioController.updateReciter(reciter.id, reciter.name)
    }
}
