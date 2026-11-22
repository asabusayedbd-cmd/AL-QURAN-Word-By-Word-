package com.example.feature.settings

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.core.database.AudioReciterEntity
import com.example.core.database.NoorDatabase
import com.example.core.database.SurahEntity
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
    val reciters: List<AudioReciterEntity> = emptyList(),
    val surahs: List<SurahEntity> = emptyList(),
    val signedInUserEmail: String? = null,
    val signedInUserName: String? = null,
    val statusMessage: String? = null,
    val busySourceId: Int? = null,
    val busySurahId: Int? = null
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

    val uiState: StateFlow<SettingsUiState> = combine(
        preferencesRepository.preferencesFlow,
        quranDao.observeTranslationSources(),
        quranDao.observeReciters(),
        quranDao.observeAllSurahs(),
        Firebase.auth.authStateFlow(),
        _statusMessage,
        _busySourceId,
        _busySurahId
    ) { array ->
        val prefs = array[0] as UserPreferences
        @Suppress("UNCHECKED_CAST")
        val trSources = array[1] as List<TranslationSourceEntity>
        @Suppress("UNCHECKED_CAST")
        val reciters = array[2] as List<AudioReciterEntity>
        @Suppress("UNCHECKED_CAST")
        val surahs = array[3] as List<SurahEntity>
        val user = array[4] as com.google.firebase.auth.FirebaseUser?
        val msg = array[5] as String?
        val busySrc = array[6] as Int?
        val busySurah = array[7] as Int?

        SettingsUiState(
            preferences = prefs,
            translationSources = trSources,
            reciters = reciters,
            surahs = surahs,
            signedInUserEmail = user?.email,
            signedInUserName = user?.displayName,
            statusMessage = msg,
            busySourceId = busySrc,
            busySurahId = busySurah
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

    fun clearStatusMessage() {
        _statusMessage.value = null
    }
}
