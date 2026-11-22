package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.core.audio.AudioPlaybackState
import com.example.core.audio.HifzLoopConfig
import com.example.core.database.AudioReciterEntity

@Composable
fun AudioHifzBar(
    playbackState: AudioPlaybackState,
    reciters: List<AudioReciterEntity>,
    onTogglePlayPause: () -> Unit,
    onPreviousAyah: () -> Unit,
    onNextAyah: () -> Unit,
    onDismiss: () -> Unit,
    onSelectReciter: (AudioReciterEntity) -> Unit,
    onUpdateHifzConfig: ((HifzLoopConfig) -> HifzLoopConfig) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!playbackState.isVisible) return

    var expanded by rememberSaveable { mutableStateOf(false) }
    val progress = if (playbackState.durationMs > 0L) {
        (playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onPreviousAyah,
                    modifier = Modifier.testTag("audio_prev_button")
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Previous Ayah")
                }

                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.testTag("audio_play_pause_button")
                ) {
                    if (playbackState.isBuffering) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else if (playbackState.isPlaying) {
                        Icon(Icons.Default.Pause, contentDescription = "Pause")
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                    }
                }

                IconButton(
                    onClick = onNextAyah,
                    modifier = Modifier.testTag("audio_next_button")
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Next Ayah")
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp)
                ) {
                    Text(
                        text = "${playbackState.surahName} • ${playbackState.surahId}:${playbackState.ayahNumber}",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = playbackState.errorMessage ?: playbackState.reciterName,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (playbackState.errorMessage != null) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = { expanded = !expanded },
                    modifier = Modifier.testTag("audio_expand_hifz_button")
                ) {
                    Icon(
                        if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = stringResource(R.string.hifz_controls)
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("audio_close_button")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close audio player")
                }
            }

            AnimatedVisibility(visible = expanded) {
                Surface(color = MaterialTheme.colorScheme.surface) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HorizontalDivider()
                        Text(
                            text = stringResource(R.string.hifz_controls),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        // Reciter selector
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            reciters.take(3).forEach { reciter ->
                                FilterChip(
                                    selected = reciter.id == playbackState.reciterId,
                                    onClick = { onSelectReciter(reciter) },
                                    label = {
                                        Text(
                                            text = reciter.name.substringBefore(" ").ifBlank { reciter.name },
                                            maxLines = 1
                                        )
                                    },
                                    modifier = Modifier.testTag("reciter_chip_${reciter.id}")
                                )
                            }
                        }

                        // Repeat per Ayah
                        Text(
                            text = stringResource(R.string.repeat_ayah),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1 to "1x", 3 to "3x", 5 to "5x", 10 to "10x", 0 to "∞").forEach { (count, label) ->
                                FilterChip(
                                    selected = playbackState.hifzConfig.repeatPerAyah == count,
                                    onClick = { onUpdateHifzConfig { it.copy(repeatPerAyah = count) } },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("repeat_chip_$count")
                                )
                            }
                        }

                        // Pause after Ayah + Speed
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0 to "0s", 3 to "3s", 5 to "5s").forEach { (sec, label) ->
                                    FilterChip(
                                        selected = playbackState.hifzConfig.pauseSecondsAfterAyah == sec,
                                        onClick = { onUpdateHifzConfig { it.copy(pauseSecondsAfterAyah = sec) } },
                                        label = { Text("+${label}") }
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf(0.75f to "0.75x", 1.0f to "1.0x", 1.25f to "1.25x").forEach { (spd, label) ->
                                    FilterChip(
                                        selected = playbackState.hifzConfig.playbackSpeed == spd,
                                        onClick = { onUpdateHifzConfig { it.copy(playbackSpeed = spd) } },
                                        label = { Text(label) }
                                    )
                                }
                            }
                        }

                        // A-B Loop controls
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${stringResource(R.string.loop_range)}: ${playbackState.hifzConfig.startAyah}–${playbackState.hifzConfig.endAyah}",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            Switch(
                                checked = playbackState.hifzConfig.loopRangeEnabled,
                                onCheckedChange = { enabled ->
                                    onUpdateHifzConfig {
                                        it.copy(
                                            loopRangeEnabled = enabled,
                                            startAyah = playbackState.ayahNumber.coerceAtMost(playbackState.maxAyahInSurah),
                                            endAyah = (playbackState.ayahNumber + 2).coerceAtMost(playbackState.maxAyahInSurah)
                                        )
                                    }
                                },
                                modifier = Modifier.testTag("hifz_loop_switch")
                            )
                        }

                        if (playbackState.hifzConfig.loopRangeEnabled) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        onUpdateHifzConfig { cfg ->
                                            cfg.copy(startAyah = (cfg.startAyah - 1).coerceAtLeast(1))
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("A -")
                                }
                                OutlinedButton(
                                    onClick = {
                                        onUpdateHifzConfig { cfg ->
                                            cfg.copy(startAyah = (cfg.startAyah + 1).coerceAtMost(playbackState.maxAyahInSurah))
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("A +")
                                }
                                OutlinedButton(
                                    onClick = {
                                        onUpdateHifzConfig { cfg ->
                                            cfg.copy(endAyah = (cfg.endAyah - 1).coerceAtLeast(1))
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("B -")
                                }
                                OutlinedButton(
                                    onClick = {
                                        onUpdateHifzConfig { cfg ->
                                            cfg.copy(endAyah = (cfg.endAyah + 1).coerceAtMost(playbackState.maxAyahInSurah))
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("B +")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
