package com.example.feature.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.localization.BidiUtils
import com.example.core.localization.QuranScriptType
import com.example.core.localization.ReaderViewMode
import com.example.feature.tafsir.AiCompanionSheet
import com.example.feature.tafsir.TafsirSheet
import com.example.ui.components.AudioHifzBar
import com.example.ui.components.ShareCardGenerator
import com.example.ui.components.TajweedTextFormatter
import com.example.ui.components.WordByWordFlowRow
import com.example.ui.theme.LocalTajweedPalette
import com.example.ui.theme.arabicScriptFont
import com.example.ui.theme.banglaSafeBodyStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    surahId: Int,
    targetAyah: Int,
    viewModel: ReaderViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(surahId) {
        viewModel.loadSurah(surahId)
    }

    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    val tajweedPalette = LocalTajweedPalette.current

    var showDisplaySheet by remember { mutableStateOf(false) }
    var showSurahSwitcher by remember { mutableStateOf(false) }
    var showAiCompanion by remember { mutableStateOf(false) }
    var mushafChromeVisible by rememberSaveable { mutableStateOf(true) }
    var pendingTargetAyah by rememberSaveable { mutableStateOf(targetAyah) }

    LaunchedEffect(targetAyah) {
        pendingTargetAyah = targetAyah
    }

    val headerOffset = if (state.surah?.bismillahPre == true) 1 else 0

    // Scroll to target Ayah accurately (offsetting Bismillah header when present)
    LaunchedEffect(state.ayahs.size, pendingTargetAyah, headerOffset) {
        if (pendingTargetAyah > 1 && state.ayahs.isNotEmpty()) {
            val idx = state.ayahs.indexOfFirst { it.ayah.ayahNumber == pendingTargetAyah }
            if (idx >= 0) listState.animateScrollToItem(idx + headerOffset)
        }
    }

    // Auto-scroll when currently playing Ayah changes
    LaunchedEffect(state.playbackState.ayahNumber, state.playbackState.surahId, state.playbackState.isPlaying, headerOffset) {
        if (state.playbackState.isPlaying && state.playbackState.surahId == (state.surah?.id ?: surahId)) {
            val idx = state.ayahs.indexOfFirst { it.ayah.ayahNumber == state.playbackState.ayahNumber }
            if (idx >= 0) listState.animateScrollToItem(idx + headerOffset)
        }
    }

    val showChrome = state.preferences.readerViewMode == ReaderViewMode.WORD_BY_WORD || mushafChromeVisible

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            AnimatedVisibility(visible = showChrome) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = onNavigateBack,
                            modifier = Modifier.testTag("reader_back_button")
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.cd_back)
                            )
                        }
                    },
                    title = {
                        Column(
                            modifier = Modifier
                                .clickable { showSurahSwitcher = true }
                                .testTag("reader_surah_switcher_trigger")
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = state.localizedSurahName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Icon(
                                    Icons.Default.ExpandMore,
                                    contentDescription = "Switch Surah",
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            state.surah?.let { surah ->
                                Text(
                                    text = "${surah.nameArabic} • ${BidiUtils.formatNumber(surah.ayahCount, state.preferences.uiLanguageCode)} Ayahs • Juz ${surah.startJuz}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    actions = {
                        if (state.ayahs.isNotEmpty()) {
                            IconButton(
                                onClick = { viewModel.playAyah(state.ayahs.first().ayah) },
                                modifier = Modifier.testTag("reader_play_surah_button")
                            ) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = "Play Surah"
                                )
                            }
                        }
                        IconButton(
                            onClick = { showDisplaySheet = true },
                            modifier = Modifier.testTag("reader_display_settings_button")
                        ) {
                            Icon(
                                Icons.Default.Tune,
                                contentDescription = stringResource(R.string.cd_settings)
                            )
                        }
                    }
                )
            }
        },
        bottomBar = {
            AudioHifzBar(
                playbackState = state.playbackState,
                reciters = state.reciters,
                onTogglePlayPause = viewModel::togglePlayPause,
                onPreviousAyah = viewModel::previousAyah,
                onNextAyah = viewModel::nextAyah,
                onDismiss = viewModel::dismissAudioBar,
                onSelectReciter = viewModel::selectReciter,
                onUpdateHifzConfig = viewModel::updateHifzConfig
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
            ) {
                // Mode Toggle Row (Word-by-Word vs Mushaf)
                AnimatedVisibility(visible = showChrome) {
                    SingleChoiceSegmentedButtonRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        SegmentedButton(
                            selected = state.preferences.readerViewMode == ReaderViewMode.WORD_BY_WORD,
                            onClick = { viewModel.toggleViewMode(ReaderViewMode.WORD_BY_WORD) },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            modifier = Modifier.testTag("mode_wbw_button")
                        ) {
                            Text(stringResource(R.string.mode_word_by_word))
                        }
                        SegmentedButton(
                            selected = state.preferences.readerViewMode == ReaderViewMode.MUSHAF,
                            onClick = { viewModel.toggleViewMode(ReaderViewMode.MUSHAF) },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            modifier = Modifier.testTag("mode_mushaf_button")
                        ) {
                            Text(stringResource(R.string.mode_mushaf))
                        }
                    }
                }

                if (state.ayahs.isEmpty()) {
                    // Online fetch prompt for Surahs not in starter bundle
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = state.localizedSurahName,
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "This Surah is ready to download from the verified Quran Foundation (Quran.com API v4) repository for offline Word-by-Word & Mushaf study.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        state.downloadError?.let { err ->
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = err,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Center
                            )
                        }
                        Spacer(modifier = Modifier.height(18.dp))
                        Button(
                            onClick = { viewModel.downloadCurrentSurah() },
                            enabled = !state.isDownloadingSurah,
                            modifier = Modifier.testTag("download_current_surah_button")
                        ) {
                            if (state.isDownloadingSurah) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                Icon(Icons.Default.CloudDownload, contentDescription = null)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.download_surah))
                        }
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                enabled = state.preferences.readerViewMode == ReaderViewMode.MUSHAF
                            ) {
                                mushafChromeVisible = !mushafChromeVisible
                            },
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        if (state.surah?.bismillahPre == true) {
                            item {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                                    shape = MaterialTheme.shapes.medium,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "بِسْمِ ٱللَّهِ ٱلرَّحْمَـٰنِ ٱلرَّحِيمِ",
                                        fontFamily = arabicScriptFont(state.preferences.scriptType),
                                        fontSize = 26.sp,
                                        textAlign = TextAlign.Center,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 12.dp)
                                    )
                                }
                            }
                        }

                        if (state.preferences.readerViewMode == ReaderViewMode.MUSHAF) {
                            items(state.ayahs, key = { it.ayah.id }) { bundle ->
                                val isPlayingThis = state.playbackState.isPlaying &&
                                    state.playbackState.surahId == bundle.ayah.surahId &&
                                    state.playbackState.ayahNumber == bundle.ayah.ayahNumber
                                val arabicRaw = if (state.preferences.scriptType == QuranScriptType.INDOPAK) {
                                    bundle.ayah.textIndopak.ifBlank { bundle.ayah.textUthmani }
                                } else {
                                    bundle.ayah.textUthmani
                                }
                                val formatted = TajweedTextFormatter.formatArabicVerse(
                                    rawText = arabicRaw,
                                    tajweedHtml = bundle.ayah.textTajweedHtml,
                                    tajweedEnabled = state.preferences.tajweedColorsEnabled,
                                    palette = tajweedPalette,
                                    ayahEndMarker = BidiUtils.formatAyahMarker(bundle.ayah.ayahNumber, useArabicDigits = true)
                                )
                                Surface(
                                    color = if (isPlayingThis) {
                                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    } else {
                                        MaterialTheme.colorScheme.background
                                    },
                                    shape = MaterialTheme.shapes.small,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            mushafChromeVisible = !mushafChromeVisible
                                            viewModel.recordAyahRead(bundle.ayah)
                                        }
                                ) {
                                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                        Text(
                                            text = formatted,
                                            fontFamily = arabicScriptFont(state.preferences.scriptType),
                                            fontSize = state.preferences.arabicFontSizeSp.sp,
                                            lineHeight = (state.preferences.arabicFontSizeSp * 1.75f).sp,
                                            textAlign = TextAlign.Start,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(vertical = 6.dp, horizontal = 8.dp)
                                        )
                                    }
                                }
                            }
                        } else {
                            items(state.ayahs, key = { it.ayah.id }) { bundle ->
                                val isPlayingThis = state.playbackState.surahId == bundle.ayah.surahId &&
                                    state.playbackState.ayahNumber == bundle.ayah.ayahNumber &&
                                    state.playbackState.isVisible
                                val activeWordPos = if (isPlayingThis) state.playbackState.activeWordPosition else null

                                ElevatedCard(
                                    shape = MaterialTheme.shapes.large,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("ayah_card_${bundle.ayah.verseKey}")
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        // Ayah Header Action Bar
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                shape = MaterialTheme.shapes.small
                                            ) {
                                                Text(
                                                    text = BidiUtils.wrapVerseKey(bundle.ayah.verseKey),
                                                    style = MaterialTheme.typography.labelLarge,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                                )
                                            }

                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                IconButton(
                                                    onClick = { viewModel.playAyah(bundle.ayah) },
                                                    modifier = Modifier.testTag("play_ayah_${bundle.ayah.verseKey}")
                                                ) {
                                                    Icon(
                                                        Icons.Default.PlayArrow,
                                                        contentDescription = stringResource(R.string.cd_play_ayah)
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { viewModel.toggleBookmark(bundle.ayah) },
                                                    modifier = Modifier.testTag("bookmark_ayah_${bundle.ayah.verseKey}")
                                                ) {
                                                    Icon(
                                                        if (bundle.isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                                        contentDescription = stringResource(R.string.cd_bookmark_ayah),
                                                        tint = if (bundle.isBookmarked) {
                                                            MaterialTheme.colorScheme.secondary
                                                        } else {
                                                            MaterialTheme.colorScheme.onSurfaceVariant
                                                        }
                                                    )
                                                }
                                                IconButton(
                                                    onClick = { viewModel.openTafsir(bundle.ayah) },
                                                    modifier = Modifier.testTag("open_tafsir_${bundle.ayah.verseKey}")
                                                ) {
                                                    Icon(
                                                        if (bundle.hasNote) Icons.Default.EditNote else Icons.AutoMirrored.Filled.MenuBook,
                                                        contentDescription = stringResource(R.string.cd_open_tafsir)
                                                    )
                                                }
                                                IconButton(
                                                    onClick = {
                                                        val tr = bundle.translations.firstOrNull()
                                                        ShareCardGenerator.shareAyahAsImageCard(
                                                            context = context,
                                                            surahName = state.localizedSurahName,
                                                            verseKey = bundle.ayah.verseKey,
                                                            arabicText = bundle.ayah.textUthmani,
                                                            translationText = tr?.translatedText.orEmpty(),
                                                            translatorName = tr?.translatorName ?: "Verified Translation"
                                                        )
                                                    },
                                                    modifier = Modifier.testTag("share_card_${bundle.ayah.verseKey}")
                                                ) {
                                                    Icon(
                                                        Icons.Default.Share,
                                                        contentDescription = stringResource(R.string.cd_share_ayah)
                                                    )
                                                }
                                                IconButton(
                                                    onClick = {
                                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                                        val firstTr = bundle.translations.firstOrNull()?.translatedText.orEmpty()
                                                        val clip = "${bundle.ayah.textUthmani}\n$firstTr\n[${state.localizedSurahName} ${bundle.ayah.verseKey}]"
                                                        clipboard.setPrimaryClip(ClipData.newPlainText("Quran Ayah", clip))
                                                        Toast.makeText(context, "Ayah copied", Toast.LENGTH_SHORT).show()
                                                    },
                                                    modifier = Modifier.testTag("copy_ayah_${bundle.ayah.verseKey}")
                                                ) {
                                                    Icon(
                                                        Icons.Default.ContentCopy,
                                                        contentDescription = stringResource(R.string.cd_copy_ayah)
                                                    )
                                                }
                                            }
                                        }

                                        // Full Arabic Ayah (with Tajweed when enabled)
                                        val arabicRaw = if (state.preferences.scriptType == QuranScriptType.INDOPAK) {
                                            bundle.ayah.textIndopak.ifBlank { bundle.ayah.textUthmani }
                                        } else {
                                            bundle.ayah.textUthmani
                                        }
                                        val formattedArabic = TajweedTextFormatter.formatArabicVerse(
                                            rawText = arabicRaw,
                                            tajweedHtml = bundle.ayah.textTajweedHtml,
                                            tajweedEnabled = state.preferences.tajweedColorsEnabled,
                                            palette = tajweedPalette,
                                            ayahEndMarker = BidiUtils.formatAyahMarker(bundle.ayah.ayahNumber, useArabicDigits = true)
                                        )
                                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                                            Text(
                                                text = formattedArabic,
                                                fontFamily = arabicScriptFont(state.preferences.scriptType),
                                                fontSize = state.preferences.arabicFontSizeSp.sp,
                                                lineHeight = (state.preferences.arabicFontSizeSp * 1.7f).sp,
                                                textAlign = TextAlign.Start,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }

                                        // Word-by-Word RTL FlowRow
                                        if (bundle.words.isNotEmpty()) {
                                            WordByWordFlowRow(
                                                words = bundle.words,
                                                scriptType = state.preferences.scriptType,
                                                wbwLanguageCode = state.preferences.wbwLanguageCode,
                                                arabicFontSizeSp = (state.preferences.arabicFontSizeSp * 0.72f).coerceAtLeast(18f),
                                                wbwFontSizeSp = state.preferences.wbwFontSizeSp,
                                                showTransliteration = state.preferences.showTransliteration,
                                                activeWordPosition = activeWordPos,
                                                ayahNumber = bundle.ayah.ayahNumber,
                                                onWordClick = { wItem ->
                                                    viewModel.inspectWord(wItem, bundle.ayah.verseKey)
                                                }
                                            )
                                        }

                                        HorizontalDivider(thickness = 0.6.dp)

                                        // Multi-translation Stack
                                        bundle.translations.forEach { tr ->
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    text = tr.translatedText,
                                                    style = banglaSafeBodyStyle(
                                                        state.preferences.translationFontSizeSp,
                                                        tr.languageCode
                                                    ),
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                Text(
                                                    text = tr.translatorName,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.secondary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Previous / Next Surah Navigation Footer
                        item {
                            val currentSid = state.surah?.id ?: surahId
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                if (currentSid > 1) {
                                    OutlinedButton(
                                        onClick = {
                                            pendingTargetAyah = 1
                                            viewModel.loadSurah(currentSid - 1)
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("reader_prev_surah_button")
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text("Prev Surah")
                                    }
                                }
                                if (currentSid < 114) {
                                    FilledTonalButton(
                                        onClick = {
                                            pendingTargetAyah = 1
                                            viewModel.loadSurah(currentSid + 1)
                                        },
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("reader_next_surah_button")
                                    ) {
                                        Text("Next Surah")
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }

                        item { Spacer(modifier = Modifier.height(72.dp)) }
                    }
                }
            }
        }
    }

    if (showSurahSwitcher && state.allSurahs.isNotEmpty()) {
        ModalBottomSheet(onDismissRequest = { showSurahSwitcher = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Jump to Surah (114 Surahs)",
                    style = MaterialTheme.typography.titleLarge
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(state.allSurahs, key = { it.id }) { itemSurah ->
                        val isCurrent = itemSurah.id == (state.surah?.id ?: surahId)
                        val title = BidiUtils.extractLocalizedSurahName(
                            itemSurah.localizedNamesJson,
                            state.preferences.uiLanguageCode
                        ).ifBlank { itemSurah.nameTransliteration }
                        Surface(
                            shape = MaterialTheme.shapes.medium,
                            color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pendingTargetAyah = 1
                                    viewModel.loadSurah(itemSurah.id)
                                    showSurahSwitcher = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${itemSurah.id}. $title",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium
                                )
                                Text(
                                    text = itemSurah.nameArabic,
                                    fontFamily = arabicScriptFont(state.preferences.scriptType),
                                    fontSize = 20.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDisplaySheet) {
        ReaderDisplaySettingsSheet(
            preferences = state.preferences,
            onSelectScript = viewModel::setScriptType,
            onToggleTajweed = viewModel::setTajweedEnabled,
            onToggleTransliteration = viewModel::setShowTransliteration,
            onArabicSizeChange = viewModel::setArabicFontSize,
            onWbwSizeChange = viewModel::setWbwFontSize,
            onTranslationSizeChange = viewModel::setTranslationFontSize,
            onDismiss = { showDisplaySheet = false }
        )
    }

    state.inspectedWord?.let { inspected ->
        WordMorphologySheet(
            inspected = inspected,
            scriptType = state.preferences.scriptType,
            wbwLanguageCode = state.preferences.wbwLanguageCode,
            onPlayWordAudio = { speed -> viewModel.playInspectedWord(speed) },
            onNavigateToOccurrence = { targetS, targetA ->
                pendingTargetAyah = targetA
                if (targetS != (state.surah?.id ?: surahId)) {
                    viewModel.loadSurah(targetS)
                }
            },
            onDismiss = viewModel::dismissWordInspector
        )
    }

    state.selectedTafsirAyah?.let { selectedAyah ->
        val bundle = state.ayahs.firstOrNull { it.ayah.id == selectedAyah.id }
        TafsirSheet(
            ayah = selectedAyah,
            surahName = state.localizedSurahName,
            translations = bundle?.translations.orEmpty(),
            tafsirSources = state.tafsirSources,
            loadedTafsirs = state.selectedAyahTafsirs,
            selectedTafsirSourceId = state.preferences.activeTafsirSourceId,
            existingNoteText = state.selectedAyahNoteText,
            isFetchingOnlineTafsir = state.isFetchingTafsir,
            translationFontSizeSp = state.preferences.translationFontSizeSp,
            onSelectTafsirSource = viewModel::selectTafsirSource,
            onSaveNote = viewModel::saveNoteForSelectedAyah,
            onShareAsCard = {
                val tr = bundle?.translations?.firstOrNull()
                ShareCardGenerator.shareAyahAsImageCard(
                    context = context,
                    surahName = state.localizedSurahName,
                    verseKey = selectedAyah.verseKey,
                    arabicText = selectedAyah.textUthmani,
                    translationText = tr?.translatedText.orEmpty(),
                    translatorName = tr?.translatorName ?: "Verified Translation"
                )
            },
            onOpenAiCompanion = { showAiCompanion = true },
            onDismiss = viewModel::closeTafsir
        )

        if (showAiCompanion) {
            AiCompanionSheet(
                surahName = state.localizedSurahName,
                verseKey = selectedAyah.verseKey,
                uiLanguageCode = state.preferences.uiLanguageCode,
                aiState = state.aiStudyState,
                onAskQuestion = viewModel::askAiAboutSelectedAyah,
                onDismiss = {
                    showAiCompanion = false
                    viewModel.resetAiState()
                }
            )
        }
    }
}
