package com.example.feature.explorer

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.core.localization.BidiUtils
import com.example.ui.theme.arabicScriptFont
import com.example.ui.theme.banglaSafeBodyStyle

private data class JuzStarterInfo(val juz: Int, val surahId: Int, val ayahNumber: Int, val label: String)
private data class RabbanaDuaItem(val surahId: Int, val ayahNumber: Int, val title: String, val verseKey: String)

private val JUZ_MAP = (1..30).map { j ->
    val (s, a, lbl) = when (j) {
        1 -> Triple(1, 1, "Al-Fatihah 1:1")
        2 -> Triple(2, 142, "Al-Baqarah 2:142")
        3 -> Triple(2, 253, "Al-Baqarah 2:253")
        15 -> Triple(17, 1, "Al-Isra 17:1")
        23 -> Triple(36, 28, "Ya-Sin 36:28")
        29 -> Triple(67, 1, "Al-Mulk 67:1")
        30 -> Triple(78, 1, "An-Naba 78:1")
        else -> Triple((j * 3).coerceIn(1, 114), 1, "Surah ${(j * 3).coerceIn(1, 114)}")
    }
    JuzStarterInfo(j, s, a, lbl)
}

private val RABBANA_DUAS = listOf(
    RabbanaDuaItem(2, 201, "Rabbana atina fid-dunya hasanah (Good in both worlds)", "2:201"),
    RabbanaDuaItem(3, 8, "Rabbana la tuzigh qulubana (Steadfast heart)", "3:8"),
    RabbanaDuaItem(1, 5, "Iyyaka na'budu wa iyyaka nasta'in (Guidance & Help)", "1:5"),
    RabbanaDuaItem(1, 6, "Ihdinas-siratal-mustaqim (Straight Path)", "1:6"),
    RabbanaDuaItem(2, 255, "Ayat al-Kursi (Protection & Sovereignty)", "2:255")
)

@Composable
fun ExplorerScreen(
    viewModel: ExplorerViewModel,
    initialFocusSearch: Boolean = false,
    onOpenReader: (surahId: Int, ayahNumber: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val searchFocusRequester = remember { FocusRequester() }

    LaunchedEffect(initialFocusSearch) {
        if (initialFocusSearch) {
            kotlinx.coroutines.delay(120L)
            runCatching { searchFocusRequester.requestFocus() }
        }
    }

    val tabTitles = listOf(
        stringResource(R.string.tab_surah),
        stringResource(R.string.tab_juz),
        stringResource(R.string.tab_page),
        stringResource(R.string.tab_duas)
    )

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 760.dp)
        ) {
            // Search & AI Smart Search Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    placeholder = { Text(stringResource(R.string.search_hint), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (state.searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.updateSearchQuery("") }) {
                                Icon(Icons.Default.Close, contentDescription = "Clear search")
                            }
                        }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(searchFocusRequester)
                        .testTag("explorer_search_input")
                )

                if (state.searchQuery.isNotBlank()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = { viewModel.runAiSemanticSearch() },
                            enabled = !state.isSearchingAi,
                            modifier = Modifier.testTag("ai_semantic_search_button")
                        ) {
                            if (state.isSearchingAi) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(stringResource(R.string.ai_semantic_search))
                        }

                        Text(
                            text = "${state.searchResults.size} matches",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                state.aiHint?.let { hint ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = hint,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }

            if (state.searchQuery.isNotBlank()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.searchResults, key = { it.ayah.id }) { res ->
                        ElevatedCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("search_result_${res.ayah.verseKey}")
                                .clickable { onOpenReader(res.ayah.surahId, res.ayah.ayahNumber) }
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "${res.surahName} • ${BidiUtils.wrapVerseKey(res.ayah.verseKey)}",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    text = res.ayah.textUthmani,
                                    fontFamily = arabicScriptFont(state.preferences.scriptType),
                                    fontSize = 22.sp,
                                    lineHeight = 36.sp
                                )
                                res.matchedTranslation?.let { tr ->
                                    Text(
                                        text = tr.translatedText,
                                        style = banglaSafeBodyStyle(14f, tr.languageCode),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                TabRow(selectedTabIndex = selectedTab) {
                    tabTitles.forEachIndexed { idx, title ->
                        Tab(
                            selected = selectedTab == idx,
                            onClick = { selectedTab = idx },
                            text = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            modifier = Modifier.testTag("explorer_tab_$idx")
                        )
                    }
                }

                when (selectedTab) {
                    0 -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(state.surahs, key = { it.id }) { surah ->
                                val localizedName = BidiUtils.extractLocalizedSurahName(
                                    surah.localizedNamesJson,
                                    state.preferences.uiLanguageCode
                                ).ifBlank { surah.nameTransliteration }
                                val isDownloading = state.downloadingSurahId == surah.id

                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("surah_row_${surah.id}")
                                        .clickable { onOpenReader(surah.id, 1) }
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Text(
                                                text = BidiUtils.formatNumber(surah.id, state.preferences.uiLanguageCode),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = localizedName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "${surah.revelationPlace.replaceFirstChar { it.uppercase() }} • ${BidiUtils.formatNumber(surah.ayahCount, state.preferences.uiLanguageCode)} Ayahs",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }

                                    Text(
                                        text = surah.nameArabic,
                                        fontFamily = arabicScriptFont(state.preferences.scriptType),
                                        fontSize = 20.sp,
                                        color = MaterialTheme.colorScheme.primary
                                    )

                                    Spacer(modifier = Modifier.width(8.dp))

                                    if (isDownloading) {
                                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                                    } else if (surah.isDownloaded) {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = stringResource(R.string.downloaded_badge),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    } else {
                                        IconButton(
                                            onClick = { viewModel.downloadSurah(surah.id) },
                                            modifier = Modifier.testTag("download_surah_${surah.id}")
                                        ) {
                                            Icon(
                                                Icons.Default.CloudDownload,
                                                contentDescription = stringResource(R.string.download_surah),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), thickness = 0.5.dp)
                            }
                        }
                    }

                    1 -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(JUZ_MAP, key = { it.juz }) { juzItem ->
                                OutlinedCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("juz_card_${juzItem.juz}")
                                        .clickable { onOpenReader(juzItem.surahId, juzItem.ayahNumber) }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Juz ${BidiUtils.formatNumber(juzItem.juz, state.preferences.uiLanguageCode)}",
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        Text(
                                            text = juzItem.label,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    2 -> {
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 84.dp),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(604) { idx ->
                                val page = idx + 1
                                AssistChip(
                                    onClick = {
                                        val resolvedSurah = state.surahs
                                            .lastOrNull { it.startPage <= page }?.id
                                            ?: when {
                                                page == 1 -> 1
                                                page <= 49 -> 2
                                                page >= 604 -> 114
                                                else -> ((page * 114) / 604).coerceIn(1, 114)
                                            }
                                        onOpenReader(resolvedSurah, 1)
                                    },
                                    label = { Text("P. $page") },
                                    modifier = Modifier.testTag("mushaf_page_$page")
                                )
                            }
                        }
                    }

                    3 -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(RABBANA_DUAS, key = { it.verseKey }) { dua ->
                                ElevatedCard(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("dua_card_${dua.verseKey}")
                                        .clickable { onOpenReader(dua.surahId, dua.ayahNumber) }
                                ) {
                                    Column(modifier = Modifier.padding(16.dp)) {
                                        Text(
                                            text = BidiUtils.wrapVerseKey(dua.verseKey),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(text = dua.title, style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
