package com.example.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.database.AyahEntity
import com.example.core.database.AyahTranslationEntity
import com.example.core.database.HabitStreakEntity
import com.example.core.database.NoorDatabase
import com.example.core.database.ReadingProgressEntity
import com.example.core.database.SurahEntity
import com.example.core.firebase.FirestoreSyncRepository
import com.example.core.ibadah.PrayerTimeCalculator
import com.example.core.ibadah.PrayerTimesSummary
import com.example.core.ibadah.StreakCalculator
import com.example.core.localization.BidiUtils
import com.example.core.localization.PreferencesRepository
import com.example.core.localization.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VerseOfTheDayItem(
    val ayah: AyahEntity,
    val surahName: String,
    val translation: AyahTranslationEntity?
)

data class HomeUiState(
    val preferences: UserPreferences = UserPreferences(),
    val progress: ReadingProgressEntity = ReadingProgressEntity(),
    val lastSurahName: String = "Al-Fatihah",
    val streaks: List<HabitStreakEntity> = emptyList(),
    val currentStreakDays: Int = 0,
    val todayAyahsRead: Int = 0,
    val todayTasbeehCount: Int = 0,
    val verseOfTheDay: VerseOfTheDayItem? = null,
    val prayerTimes: PrayerTimesSummary = PrayerTimeCalculator.calculateDailyTimes(23.8103, 90.4125),
    val qiblaBearingDegrees: Float = PrayerTimeCalculator.calculateQiblaBearing(23.8103, 90.4125),
    val gregorianDate: String = "",
    val hijriDate: String = "",
    val tasbeehPhraseIndex: Int = 0
)

class HomeViewModel(
    private val database: NoorDatabase,
    private val preferencesRepository: PreferencesRepository,
    private val firestoreSyncRepository: FirestoreSyncRepository
) : ViewModel() {

    private val quranDao = database.quranDao()
    private val userLibraryDao = database.userLibraryDao()

    private val _tasbeehPhraseIdx = MutableStateFlow(0)

    private val verseOfTheDayFlow = flow {
        // Verified curated rotating Ayahs bundled locally in starter dataset
        val keys = listOf("2:255", "1:5", "2:201", "3:8", "103:3", "112:1")
        val dayIdx = ((System.currentTimeMillis() / 86_400_000L) % keys.size).toInt()
        val chosenKey = keys[dayIdx]
        val ayah = quranDao.getAyahByKey(chosenKey) ?: quranDao.getAyahByKey("1:1")
        if (ayah != null) {
            val surah = quranDao.getSurah(ayah.surahId)
            val translations = quranDao.getTranslationsForAyah(ayah.id)
            emit(Triple(ayah, surah, translations))
        } else {
            emit(null)
        }
    }

    val uiState: StateFlow<HomeUiState> = combine(
        preferencesRepository.preferencesFlow,
        userLibraryDao.observeReadingProgress(),
        userLibraryDao.observeRecentStreaks(),
        quranDao.observeAllSurahs(),
        verseOfTheDayFlow,
        _tasbeehPhraseIdx
    ) { array ->
        @Suppress("UNCHECKED_CAST")
        val prefs = array[0] as UserPreferences
        val progress = (array[1] as ReadingProgressEntity?) ?: ReadingProgressEntity()
        @Suppress("UNCHECKED_CAST")
        val streaks = array[2] as List<HabitStreakEntity>
        @Suppress("UNCHECKED_CAST")
        val surahs = array[3] as List<SurahEntity>
        @Suppress("UNCHECKED_CAST")
        val votdTriple = array[4] as Triple<AyahEntity, SurahEntity?, List<AyahTranslationEntity>>?
        val phraseIdx = array[5] as Int

        val todayKey = StreakCalculator.todayDateKey()
        val todayStreak = streaks.firstOrNull { it.dateKey == todayKey }
        val streakDays = StreakCalculator.calculateCurrentStreak(streaks, todayKey)

        val lastSurah = surahs.firstOrNull { it.id == progress.lastSurahId }
        val lastSurahName = lastSurah?.let {
            BidiUtils.extractLocalizedSurahName(it.localizedNamesJson, prefs.uiLanguageCode)
                .ifBlank { it.nameTransliteration }
        } ?: "Al-Fatihah"

        val votdItem = votdTriple?.let { (ayah, surah, translations) ->
            val sName = surah?.let {
                BidiUtils.extractLocalizedSurahName(it.localizedNamesJson, prefs.uiLanguageCode)
                    .ifBlank { it.nameTransliteration }
            } ?: "Surah ${ayah.surahId}"
            val preferredTr = translations.firstOrNull { it.languageCode == prefs.uiLanguageCode }
                ?: translations.firstOrNull { it.languageCode == "bn" }
                ?: translations.firstOrNull()
            VerseOfTheDayItem(ayah, sName, preferredTr)
        }

        val (gregorian, hijri) = BidiUtils.formatGregorianAndHijriDate(prefs.uiLanguageCode)

        HomeUiState(
            preferences = prefs,
            progress = progress,
            lastSurahName = lastSurahName,
            streaks = streaks,
            currentStreakDays = streakDays,
            todayAyahsRead = todayStreak?.ayahsReadCount ?: 0,
            todayTasbeehCount = todayStreak?.tasbeehCount ?: 0,
            verseOfTheDay = votdItem,
            prayerTimes = PrayerTimeCalculator.calculateDailyTimes(prefs.latitude, prefs.longitude),
            qiblaBearingDegrees = PrayerTimeCalculator.calculateQiblaBearing(prefs.latitude, prefs.longitude),
            gregorianDate = gregorian,
            hijriDate = hijri,
            tasbeehPhraseIndex = phraseIdx
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = HomeUiState()
    )

    fun incrementTasbeeh() {
        viewModelScope.launch {
            val todayKey = StreakCalculator.todayDateKey()
            val existing = userLibraryDao.getStreakByDate(todayKey) ?: HabitStreakEntity(dateKey = todayKey)
            val updated = existing.copy(
                tasbeehCount = existing.tasbeehCount + 1,
                updatedAtEpochMs = System.currentTimeMillis()
            )
            userLibraryDao.upsertStreak(updated)
            firestoreSyncRepository.upsertStreak(updated)
        }
    }

    fun resetTodayTasbeeh() {
        viewModelScope.launch {
            val todayKey = StreakCalculator.todayDateKey()
            val existing = userLibraryDao.getStreakByDate(todayKey) ?: return@launch
            val updated = existing.copy(tasbeehCount = 0, updatedAtEpochMs = System.currentTimeMillis())
            userLibraryDao.upsertStreak(updated)
            firestoreSyncRepository.upsertStreak(updated)
        }
    }

    fun cycleTasbeehPhrase() {
        _tasbeehPhraseIdx.update { (it + 1) % 4 }
    }

    fun updateLocation(name: String, latitude: Double, longitude: Double) {
        viewModelScope.launch {
            preferencesRepository.setLocation(name, latitude, longitude)
        }
    }
}
