package com.example.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.core.localization.QuranScriptType
import com.example.core.localization.UserPreferences
import com.example.ui.theme.arabicScriptFont
import com.example.ui.theme.banglaSafeBodyStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WordMorphologySheet(
    inspected: WordInspectorState,
    scriptType: QuranScriptType,
    wbwLanguageCode: String,
    onPlayWordAudio: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.word_inspector_title),
                style = MaterialTheme.typography.titleLarge
            )

            Surface(
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val wordArabic = if (scriptType == QuranScriptType.INDOPAK) {
                        inspected.word.textIndopak.ifBlank { inspected.word.textUthmani }
                    } else {
                        inspected.word.textUthmani
                    }
                    Text(
                        text = wordArabic,
                        fontFamily = arabicScriptFont(scriptType),
                        fontSize = 40.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    if (inspected.word.transliteration.isNotBlank()) {
                        Text(
                            text = inspected.word.transliteration,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    Text(
                        text = inspected.localizedMeaning,
                        style = banglaSafeBodyStyle(18f, wbwLanguageCode),
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            if (inspected.word.rootArabic.isNotBlank() || inspected.word.lemmaArabic.isNotBlank() || inspected.word.grammarTagJson.isNotBlank()) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (inspected.word.rootArabic.isNotBlank()) {
                            MorphologyRow(
                                label = stringResource(R.string.word_root_label),
                                value = inspected.word.rootArabic
                            )
                        }
                        if (inspected.word.lemmaArabic.isNotBlank()) {
                            MorphologyRow(
                                label = stringResource(R.string.word_lemma_label),
                                value = inspected.word.lemmaArabic
                            )
                        }
                        if (inspected.word.grammarTagJson.isNotBlank()) {
                            MorphologyRow(
                                label = stringResource(R.string.word_grammar_label),
                                value = inspected.word.grammarTagJson
                            )
                        }
                        MorphologyRow(
                            label = stringResource(R.string.word_occurrences_label),
                            value = inspected.rootOccurrencesCount.toString()
                        )
                    }
                }
            }

            Button(
                onClick = onPlayWordAudio,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("play_single_word_audio_button")
            ) {
                Icon(Icons.Default.VolumeUp, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.play_word_audio))
            }

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}

@Composable
private fun MorphologyRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderDisplaySettingsSheet(
    preferences: UserPreferences,
    onSelectScript: (QuranScriptType) -> Unit,
    onToggleTajweed: (Boolean) -> Unit,
    onToggleTransliteration: (Boolean) -> Unit,
    onArabicSizeChange: (Float) -> Unit,
    onWbwSizeChange: (Float) -> Unit,
    onTranslationSizeChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(stringResource(R.string.display_settings), style = MaterialTheme.typography.titleLarge)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = preferences.scriptType == QuranScriptType.UTHMANI,
                    onClick = { onSelectScript(QuranScriptType.UTHMANI) },
                    label = { Text(stringResource(R.string.script_uthmani)) },
                    modifier = Modifier.testTag("script_chip_uthmani")
                )
                FilterChip(
                    selected = preferences.scriptType == QuranScriptType.INDOPAK,
                    onClick = { onSelectScript(QuranScriptType.INDOPAK) },
                    label = { Text(stringResource(R.string.script_indopak)) },
                    modifier = Modifier.testTag("script_chip_indopak")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.tajweed_colors), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = preferences.tajweedColorsEnabled,
                    onCheckedChange = onToggleTajweed,
                    modifier = Modifier.testTag("tajweed_switch")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.show_transliteration), style = MaterialTheme.typography.bodyLarge)
                Switch(
                    checked = preferences.showTransliteration,
                    onCheckedChange = onToggleTransliteration,
                    modifier = Modifier.testTag("translit_switch")
                )
            }

            HorizontalDivider()

            Text("${stringResource(R.string.arabic_font_size)}: ${preferences.arabicFontSizeSp.toInt()}sp")
            Slider(
                value = preferences.arabicFontSizeSp,
                onValueChange = onArabicSizeChange,
                valueRange = 20f..44f
            )

            Text("${stringResource(R.string.wbw_font_size)}: ${preferences.wbwFontSizeSp.toInt()}sp")
            Slider(
                value = preferences.wbwFontSizeSp,
                onValueChange = onWbwSizeChange,
                valueRange = 10f..20f
            )

            Text("${stringResource(R.string.translation_font_size)}: ${preferences.translationFontSizeSp.toInt()}sp")
            Slider(
                value = preferences.translationFontSizeSp,
                onValueChange = onTranslationSizeChange,
                valueRange = 12f..24f
            )

            Spacer(modifier = Modifier.height(14.dp))
        }
    }
}
