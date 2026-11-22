package com.example.feature.explorer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.ai.GroundedAiRepository
import com.example.core.database.AyahEntity
import com.example.core.database.AyahTranslationEntity
import com.example.core.database.NoorDatabase
import com.example.core.database.SurahEntity
import com.example.core.localization.BidiUtils
import com.example.core.localization.PreferencesRepository
import com.example.core.localization.UserPreferences
import com.example.core.network.QuranSyncRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExplorerSearchResult(
    val ayah: AyahEntity,
    val surahName: String,
    val matchedTranslation: AyahTranslationEntity?
)

data class ExplorerUiState(
    val surahs: List<SurahEntity> = emptyList(),
    val preferences: UserPreferences = UserPreferences(),
    val searchQuery: String = "",
    val searchResults: List<ExplorerSearchResult> = emptyList(),
    val aiHint: String? = null,
    val isSearchingAi: Boolean = false,
    val downloadingSurahId: Int? = null,
    val downloadMessage: String? = null
)

class ExplorerViewModel(
    private val database: NoorDatabase,
    private val preferencesRepository: PreferencesRepository,
    private val syncRepository: QuranSyncRepository,
    private val aiRepository: GroundedAiRepository
) : ViewModel() {

    private val quranDao = database.quranDao()

    private val _searchQuery = MutableStateFlow("")
    private val _searchResults = MutableStateFlow<List<ExplorerSearchResult>>(emptyList())
    private val _aiHint = MutableStateFlow<String?>(null)
    private val _isSearchingAi = MutableStateFlow(false)
    private val _downloadingSurahId = MutableStateFlow<Int?>(null)
    private val _downloadMessage = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ExplorerUiState> = combine(
        quranDao.observeAllSurahs(),
        preferencesRepository.preferencesFlow,
        _searchQuery,
        _searchResults,
        _aiHint,
        _isSearchingAi,
        _downloadingSurahId,
        _downloadMessage
    ) { array ->
        @Suppress("UNCHECKED_CAST")
        val surahs = array[0] as List<SurahEntity>
        val prefs = array[1] as UserPreferences
        val query = array[2] as String
        @Suppress("UNCHECKED_CAST")
        val results = array[3] as List<ExplorerSearchResult>
        val aiHint = array[4] as String?
        val isSearchingAi = array[5] as Boolean
        val downloadingId = array[6] as Int?
        val dlMessage = array[7] as String?

        ExplorerUiState(
            surahs = surahs,
            preferences = prefs,
            searchQuery = query,
            searchResults = results,
            aiHint = aiHint,
            isSearchingAi = isSearchingAi,
            downloadingSurahId = downloadingId,
            downloadMessage = dlMessage
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = ExplorerUiState()
    )

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
        _aiHint.value = null
        val trimmed = query.trim()
        if (trimmed.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            val normalized = BidiUtils.normalizeArabicForSearch(trimmed)
            val matchedAyahs = quranDao.searchAyahs(
                exactQuery = trimmed,
                normalizedQuery = normalized.ifBlank { trimmed },
                rawQuery = trimmed
            )
            mapAndEmitResults(matchedAyahs)
        }
    }

    fun runAiSemanticSearch() {
        val query = _searchQuery.value.trim()
        if (query.isBlank()) return
        viewModelScope.launch {
            _isSearchingAi.value = true
            _aiHint.value = null
            val prefs = uiState.value.preferences
            val result = aiRepository.expandSemanticQuery(query, prefs.uiLanguageCode)
            result.onSuccess { expansion ->
                _aiHint.value = expansion.summaryHint.takeIf { it.isNotBlank() }
                val byKeys = if (expansion.verseKeys.isNotEmpty()) {
                    quranDao.getAyahsByKeys(expansion.verseKeys)
                } else {
                    emptyList()
                }
                val byKeyword = expansion.keywords.firstOrNull()?.let { kw ->
                    quranDao.searchAyahs(kw, BidiUtils.normalizeArabicForSearch(kw), kw)
                } ?: emptyList()

                val merged = (byKeys + byKeyword).distinctBy { it.id }
                mapAndEmitResults(merged)
            }.onFailure { err ->
                _aiHint.value = err.localizedMessage
            }
            _isSearchingAi.value = false
        }
    }

    fun downloadSurah(surahId: Int) {
        viewModelScope.launch {
            _downloadingSurahId.value = surahId
            _downloadMessage.value = null
            val prefs = uiState.value.preferences
            val res = syncRepository.downloadSurahForOffline(
                surahId = surahId,
                wbwLanguages = listOf(prefs.wbwLanguageCode),
                reciterId = prefs.activeReciterId
            )
            res.onSuccess { count ->
                _downloadMessage.value = "Downloaded Surah $surahId ($count Ayahs)"
            }.onFailure { err ->
                _downloadMessage.value = err.localizedMessage ?: "Download failed"
            }
            _downloadingSurahId.value = null
        }
    }

    fun clearMessage() {
        _downloadMessage.value = null
    }

    private suspend fun mapAndEmitResults(ayahs: List<AyahEntity>) {
        val prefs = uiState.value.preferences
        val surahsMap = uiState.value.surahs.associateBy { it.id }
        val mapped = ayahs.map { ayah ->
            val surah = surahsMap[ayah.surahId]
            val surahName = surah?.let {
                BidiUtils.extractLocalizedSurahName(it.localizedNamesJson, prefs.uiLanguageCode)
                    .ifBlank { it.nameTransliteration }
            } ?: "Surah ${ayah.surahId}"
            val trs = quranDao.getTranslationsForAyah(ayah.id)
            val bestTr = trs.firstOrNull { it.languageCode == prefs.uiLanguageCode }
                ?: trs.firstOrNull { it.languageCode == "bn" }
                ?: trs.firstOrNull()
            ExplorerSearchResult(ayah, surahName, bestTr)
        }
        _searchResults.update { mapped }
    }
}
