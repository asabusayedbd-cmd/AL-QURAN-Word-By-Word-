package com.example.feature.tafsir

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.ai.AiStudyUiState
import com.example.ui.theme.banglaSafeBodyStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AiCompanionSheet(
    surahName: String,
    verseKey: String,
    uiLanguageCode: String,
    aiState: AiStudyUiState,
    onAskQuestion: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var questionText by remember { mutableStateOf("") }

    val quickPrompts = listOf(
        "Summarize the core lesson of this Ayah",
        "Explain the key message based on the loaded Tafsir",
        "How can I reflect on this verse in daily worship?"
    )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "${stringResource(R.string.ask_ai_study_helper)} • $surahName $verseKey",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.7f),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.ai_grounding_notice),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                quickPrompts.forEach { prompt ->
                    SuggestionChip(
                        onClick = {
                            questionText = prompt
                            onAskQuestion(prompt)
                        },
                        label = { Text(prompt) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = questionText,
                    onValueChange = { questionText = it },
                    placeholder = { Text(stringResource(R.string.ai_ask_hint)) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("ai_question_input")
                )
                IconButton(
                    onClick = {
                        if (questionText.isNotBlank()) {
                            onAskQuestion(questionText.trim())
                        }
                    },
                    enabled = questionText.isNotBlank() && aiState !is AiStudyUiState.Loading,
                    modifier = Modifier.testTag("ai_send_button")
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send question")
                }
            }

            when (aiState) {
                AiStudyUiState.Idle -> Unit
                AiStudyUiState.Loading -> {
                    Row(
                        modifier = Modifier.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Studying loaded Tafsir…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is AiStudyUiState.Streaming -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = aiState.text,
                            style = banglaSafeBodyStyle(15f, uiLanguageCode),
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
                is AiStudyUiState.Success -> {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("ai_response_card")
                    ) {
                        Text(
                            text = aiState.text,
                            style = banglaSafeBodyStyle(15f, uiLanguageCode),
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
                is AiStudyUiState.Error -> {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = aiState.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(14.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}
