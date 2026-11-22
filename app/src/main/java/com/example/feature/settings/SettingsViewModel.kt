package com.example.feature.settings

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.database.AudioReciterEntity
import com.example.core.database.NoorDatabase
import com.example.core.database.SurahEntity
import com.example.core.database.TafsirSourceEntity
import com.example.core.database.TranslationSourceEntity
import com.example.core.firebase.GoogleAuthHelper
import com.example.core.firebase.authStateFlow
import com.example.core.localization.NoorThemeMode
import com.example.core.localization.PreferencesRepository
import com.example.core.localization.QuranScriptType
import com.example.core.localization.UserPreferences
import com.example.core.network.QuranSyncRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SettingsUiState(
    val preferences: UserPreferences = UserPreferences(),
    val translationSources: List<TranslationSourceEntity> = emptyList(),
    val tafsirSources: List<TafsirSourceEntity> = emptyList(),
    val reciters: List<AudioReciterEntity> = emptyList(),
    val surahs: List<SurahEntity> = emptyList(),
    val signedInUserEmail: String? = null,
    val signedInUserName: String? = null,
    val statusMessage: String? = null,
    val busySourceId: Int? = null,
    val busySurahId: Int? = null,
    val isRefreshingTafsirs: Boolean = false
)

class SettingsViewModel(
    private val database: NoorDatabase,
    private val preferencesRepository: PreferencesRepository,
    private val syncRepository: QuranSyncRepository,
    private val credentialManager: CredentialManager
) : ViewModel() {

    private val quranDao = database.quranDao()
    private val _statusMessage = MutableStateFlow<String?>(null)
    private val _busySourceId = MutableStateFlow<Int?>(null)
    private val _busySurahId = MutableStateFlow<Int?>(null)
    private val _isRefreshingTafsirs = MutableStateFlow(false)

    private val catalogsFlow = combine(
        quranDao.observeTranslationSources(),
        quranDao.observeTafsirSources(),
        quranDao.observeReciters(),
        quranDao.observeAllSurahs()
    ) { tr, taf, rec, sur ->
        Pair(tr to taf, rec to sur)
    }

    private val statusFlow = combine(
        _statusMessage,
        _busySourceId,
        _busySurahId,
        _isRefreshingTafsirs
    ) { msg, busySrc, busySurah, refreshingTaf ->
        Pair(msg to busySrc, busySurah to refreshingTaf)
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        preferencesRepository.preferencesFlow,
        catalogsFlow,
        Firebase.auth.authStateFlow(),
        statusFlow
    ) { prefs, catalogs, user, statuses ->
        val (trSources, tafSources) = catalogs.first
        val (reciters, surahs) = catalogs.second
        val (msg, busySrc) = statuses.first
        val (busySurah, refreshingTaf) = statuses.second

        SettingsUiState(
            preferences = prefs,
            translationSources = trSources,
            tafsirSources = tafSources,
            reciters = reciters,
            surahs = surahs,
            signedInUserEmail = user?.email,
            signedInUserName = user?.displayName,
            statusMessage = msg,
            busySourceId = busySrc,
            busySurahId = busySurah,
            isRefreshingTafsirs = refreshingTaf
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = SettingsUiState()
    )

    fun setUiLanguage(languageCode: String) {
        viewModelScope.launch {
            preferencesRepository.setUiLanguage(languageCode)
        }
    }

    fun setWbwLanguage(languageCode: String) {
        viewModelScope.launch {
            preferencesRepository.setWbwLanguage(languageCode)
        }
    }

    fun setThemeMode(mode: NoorThemeMode) {
        viewModelScope.launch {
            preferencesRepository.setThemeMode(mode)
        }
    }

    fun setScriptType(script: QuranScriptType) {
        viewModelScope.launch {
            preferencesRepository.setScriptType(script)
        }
    }

    fun toggleTranslationSource(source: TranslationSourceEntity, enabled: Boolean) {
        viewModelScope.launch {
            if (!source.isDownloaded && enabled) {
                _busySourceId.value = source.id
                val res = syncRepository.downloadTranslationPack(source.id)
                _statusMessage.value = res.fold(
                    onSuccess = { "Downloaded ${source.translatorName}" },
                    onFailure = { "Download error: ${it.localizedMessage}" }
                )
                _busySourceId.value = null
            } else {
                quranDao.setTranslationEnabled(source.id, enabled)
            }
        }
    }

    fun downloadSurahOffline(surahId: Int) {
        viewModelScope.launch {
            _busySurahId.value = surahId
            val prefs = uiState.value.preferences
            val res = syncRepository.downloadSurahForOffline(
                surahId = surahId,
                wbwLanguages = listOf(prefs.wbwLanguageCode),
                reciterId = prefs.activeReciterId
            )
            _statusMessage.value = res.fold(
                onSuccess = { "Surah $surahId ready offline ($it Ayahs)" },
                onFailure = { "Failed: ${it.localizedMessage}" }
            )
            _busySurahId.value = null
        }
    }

    fun signInWithGoogle(activityContext: Context) {
        GoogleAuthHelper.onGoogleSignInClicked(
            context = activityContext,
            credentialManager = credentialManager,
            onAuthSuccess = { _statusMessage.value = "Cloud Sync active with Google." },
            onAuthError = { err -> _statusMessage.value = err },
            scope = viewModelScope
        )
    }

    fun signOut() {
        GoogleAuthHelper.signOut(
            credentialManager = credentialManager,
            scope = viewModelScope,
            onSignOutComplete = { _statusMessage.value = "Switched to offline Guest mode." }
        )
    }

    fun setActiveReciter(reciter: AudioReciterEntity) {
        viewModelScope.launch {
            preferencesRepository.setActiveReciterId(reciter.id)
            _statusMessage.value = "Default Qari set to ${reciter.name}"
        }
    }

    fun selectDefaultTafsirSource(source: TafsirSourceEntity) {
        viewModelScope.launch {
            preferencesRepository.setActiveTafsirSourceId(source.id)
            _statusMessage.value = "Default Tafsir set to ${source.tafsirName}"
        }
    }

    fun syncRemoteTafsirCatalog() {
        viewModelScope.launch {
            _isRefreshingTafsirs.value = true
            val res = syncRepository.refreshRemoteTafsirCatalog()
            _statusMessage.value = res.fold(
                onSuccess = { count -> "Synced $count verified Tafsirs from Quran Foundation API" },
                onFailure = { err -> "Could not refresh catalog: ${err.localizedMessage}" }
            )
            _isRefreshingTafsirs.value = false
        }
    }

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
