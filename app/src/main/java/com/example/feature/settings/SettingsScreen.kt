package com.example.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.localization.BidiUtils
import com.example.core.localization.NoorThemeMode

private val UI_LANGUAGES = listOf(
    "en" to "English",
    "bn" to "বাংলা (Bangla)",
    "ar" to "العربية (Arabic)",
    "ur" to "اردو (Urdu)",
    "id" to "Bahasa Indonesia",
    "tr" to "Türkçe"
)

private val WBW_LANGUAGES = listOf(
    "bn" to "বাংলা (WBW)",
    "en" to "English (WBW)",
    "ur" to "اردو (WBW)"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            state.statusMessage?.let { msg ->
                item {
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }

            // 1. Optional Google Cloud Sync (Guest Offline-First Friendly)
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (state.signedInUserEmail != null) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.cloud_sync_title),
                                style = MaterialTheme.typography.titleMedium
                            )
                        }

                        if (state.signedInUserEmail != null) {
                            Text(
                                text = "Synced as ${state.signedInUserName ?: ""} (${state.signedInUserEmail})",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            OutlinedButton(
                                onClick = viewModel::signOut,
                                modifier = Modifier.testTag("sign_out_button")
                            ) {
                                Text(stringResource(R.string.sign_out))
                            }
                        } else {
                            Text(
                                text = stringResource(R.string.guest_mode_desc),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = { viewModel.signInWithGoogle(context) },
                                modifier = Modifier.testTag("google_sign_in_button")
                            ) {
                                Text(stringResource(R.string.sign_in_google))
                            }
                        }
                    }
                }
            }

            // 2. Dynamic In-App UI Language Switcher
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.ui_language_title), style = MaterialTheme.typography.titleMedium)
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            UI_LANGUAGES.forEach { (code, label) ->
                                FilterChip(
                                    selected = state.preferences.uiLanguageCode == code,
                                    onClick = { viewModel.setUiLanguage(code) },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("ui_lang_$code")
                                )
                            }
                        }
                    }
                }
            }

            // 3. Reading Themes
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.theme_title), style = MaterialTheme.typography.titleMedium)
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                NoorThemeMode.SERENE_EMERALD to stringResource(R.string.theme_serene_emerald),
                                NoorThemeMode.SOFT_SEPIA to stringResource(R.string.theme_sepia),
                                NoorThemeMode.SLATE_DARK to stringResource(R.string.theme_slate_dark),
                                NoorThemeMode.AMOLED_BLACK to stringResource(R.string.theme_amoled)
                            ).forEach { (mode, title) ->
                                FilterChip(
                                    selected = state.preferences.themeMode == mode,
                                    onClick = { viewModel.setThemeMode(mode) },
                                    label = { Text(title) },
                                    modifier = Modifier.testTag("theme_chip_${mode.name}")
                                )
                            }
                        }
                    }
                }
            }

            // 4. Word-by-Word & Multi-Translation Languages
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.content_languages_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WBW_LANGUAGES.forEach { (code, label) ->
                                FilterChip(
                                    selected = state.preferences.wbwLanguageCode == code,
                                    onClick = { viewModel.setWbwLanguage(code) },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("wbw_lang_$code")
                                )
                            }
                        }
                        HorizontalDivider()
                        state.translationSources.forEach { src ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(src.translatorName, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = "${src.languageName} • ${src.fileSizeKb} KB • ${if (src.isDownloaded) "Offline" else "Cloud"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (state.busySourceId == src.id) {
                                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                                } else {
                                    Switch(
                                        checked = src.isEnabled,
                                        onCheckedChange = { enabled -> viewModel.toggleTranslationSource(src, enabled) },
                                        modifier = Modifier.testTag("translation_switch_${src.id}")
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 5. Offline Surah Download Manager (Highlights popular Surahs)
            item {
                Text(
                    text = stringResource(R.string.download_manager_title),
                    style = MaterialTheme.typography.titleMedium
                )
            }
            val featuredSurahs = state.surahs.filter { it.id in setOf(1, 2, 18, 36, 55, 67, 103, 108, 112, 113, 114) }
            items(featuredSurahs, key = { it.id }) { surah ->
                val name = BidiUtils.extractLocalizedSurahName(
                    surah.localizedNamesJson,
                    state.preferences.uiLanguageCode
                ).ifBlank { surah.nameTransliteration }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${surah.id}. $name", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = if (surah.isDownloaded) stringResource(R.string.downloaded_badge) else "Tap to cache offline",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    when {
                        state.busySurahId == surah.id -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        surah.isDownloaded -> Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        else -> IconButton(
                            onClick = { viewModel.downloadSurahOffline(surah.id) },
                            modifier = Modifier.testTag("settings_dl_surah_${surah.id}")
                        ) {
                            Icon(Icons.Default.CloudDownload, contentDescription = stringResource(R.string.download_surah))
                        }
                    }
                }
                HorizontalDivider(thickness = 0.5.dp)
            }

            item { Spacer(modifier = Modifier.height(72.dp)) }
        }
    }
}
