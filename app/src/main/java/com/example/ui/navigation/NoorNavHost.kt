package com.example.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.R
import com.example.core.database.UserBookmarkEntity
import com.example.core.database.UserCollectionEntity
import com.example.core.database.UserNoteEntity
import com.example.core.di.AppContainer
import com.example.core.firebase.authStateFlow
import com.example.feature.explorer.ExplorerScreen
import com.example.feature.explorer.ExplorerViewModel
import com.example.feature.home.HomeScreen
import com.example.feature.home.HomeViewModel
import com.example.feature.library.LibraryScreen
import com.example.feature.library.LibraryViewModel
import com.example.feature.reader.ReaderScreen
import com.example.feature.reader.ReaderViewModel
import com.example.feature.settings.SettingsScreen
import com.example.feature.settings.SettingsViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

const val ROUTE_HOME = "home"
const val ROUTE_EXPLORER = "explorer"
const val ROUTE_SEARCH = "search"
const val ROUTE_LIBRARY = "library"
const val ROUTE_SETTINGS = "settings"
const val ROUTE_READER = "reader/{surahId}/{ayahNumber}"

private data class TopNavDestination(
    val route: String,
    val labelRes: Int,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)

@Composable
fun NoorAppRoot(
    appContainer: AppContainer,
    uiLanguageCode: String,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val currentUser by Firebase.auth.authStateFlow().collectAsStateWithLifecycle(initialValue = Firebase.auth.currentUser)

    // Sync cloud bookmarks/notes/collections strictly ONLY when signed in with Google
    val uid = currentUser?.uid
    if (uid != null) {
        key(uid) {
            LaunchedEffect(uid) {
                val dao = appContainer.database.userLibraryDao()
                launch {
                    appContainer.firestoreSyncRepository.observeCloudCollections(uid)
                        .catch { /* error already logged cleanly inside repository */ }
                        .collect { cloudCols ->
                            val localCols = cloudCols.map {
                                UserCollectionEntity(
                                    id = it.id,
                                    name = it.name.ifBlank { "Collection" },
                                    colorHex = it.colorHex.ifBlank { "#0F5C4D" },
                                    createdAtEpochMs = it.createdAt?.toDate()?.time ?: System.currentTimeMillis()
                                )
                            }
                            if (localCols.isNotEmpty()) dao.insertCollections(localCols)
                        }
                }
                launch {
                    appContainer.firestoreSyncRepository.observeCloudBookmarks(uid)
                        .catch { /* logged in repo */ }
                        .collect { cloudBms ->
                            val localBms = cloudBms.map {
                                UserBookmarkEntity(
                                    id = it.id,
                                    verseKey = it.verseKey,
                                    surahId = it.surahId.toInt(),
                                    ayahNumber = it.ayahNumber.toInt(),
                                    collectionId = it.collectionId.ifBlank { "favorites" },
                                    label = it.label,
                                    createdAtEpochMs = it.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                                    updatedAtEpochMs = it.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                                )
                            }
                            if (localBms.isNotEmpty()) dao.insertBookmarks(localBms)
                        }
                }
                launch {
                    appContainer.firestoreSyncRepository.observeCloudNotes(uid)
                        .catch { /* logged in repo */ }
                        .collect { cloudNotes ->
                            val localNotes = cloudNotes.map {
                                UserNoteEntity(
                                    id = it.id,
                                    verseKey = it.verseKey,
                                    surahId = it.surahId.toInt(),
                                    ayahNumber = it.ayahNumber.toInt(),
                                    noteText = it.noteText,
                                    createdAtEpochMs = it.createdAt?.toDate()?.time ?: System.currentTimeMillis(),
                                    updatedAtEpochMs = it.updatedAt?.toDate()?.time ?: System.currentTimeMillis()
                                )
                            }
                            if (localNotes.isNotEmpty()) dao.insertNotes(localNotes)
                        }
                }
            }
        }
    }

    val topDestinations = listOf(
        TopNavDestination(ROUTE_HOME, R.string.nav_home, Icons.Default.Home),
        TopNavDestination(ROUTE_EXPLORER, R.string.nav_quran, Icons.AutoMirrored.Filled.MenuBook),
        TopNavDestination(ROUTE_SEARCH, R.string.nav_search, Icons.Default.Search),
        TopNavDestination(ROUTE_LIBRARY, R.string.nav_bookmarks, Icons.Default.Bookmark),
        TopNavDestination(ROUTE_SETTINGS, R.string.nav_settings, Icons.Default.Settings)
    )
    val isTopDestination = topDestinations.any { it.route == currentRoute }

    val layoutDirection = if (uiLanguageCode in setOf("ar", "ur")) LayoutDirection.Rtl else LayoutDirection.Ltr

    CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
        BoxWithConstraints(modifier = modifier.fillMaxSize()) {
            val useNavRail = maxWidth >= 600.dp

            val navigateTopLevel: (String) -> Unit = { route ->
                navController.navigate(route) {
                    popUpTo(navController.graph.findStartDestination().id) {
                        saveState = true
                    }
                    launchSingleTop = true
                    restoreState = true
                }
            }

            val openReader: (Int, Int) -> Unit = { surahId, ayahNumber ->
                navController.navigate("reader/$surahId/$ayahNumber")
            }

            Scaffold(
                bottomBar = {
                    if (isTopDestination && !useNavRail) {
                        NavigationBar {
                            topDestinations.forEach { dest ->
                                val selected = currentRoute == dest.route
                                val label = stringResource(dest.labelRes)
                                NavigationBarItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(dest.route) },
                                    icon = { Icon(dest.icon, contentDescription = label) },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("nav_bar_${dest.route}")
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    if (isTopDestination && useNavRail) {
                        NavigationRail {
                            topDestinations.forEach { dest ->
                                val selected = currentRoute == dest.route
                                val label = stringResource(dest.labelRes)
                                NavigationRailItem(
                                    selected = selected,
                                    onClick = { navigateTopLevel(dest.route) },
                                    icon = { Icon(dest.icon, contentDescription = label) },
                                    label = { Text(label) },
                                    modifier = Modifier.testTag("nav_rail_${dest.route}")
                                )
                            }
                        }
                    }

                    Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                        NavHost(
                            navController = navController,
                            startDestination = ROUTE_HOME
                        ) {
                            composable(ROUTE_HOME) {
                                val vm: HomeViewModel = viewModel(
                                    factory = viewModelFactory {
                                        initializer {
                                            HomeViewModel(
                                                database = appContainer.database,
                                                preferencesRepository = appContainer.preferencesRepository,
                                                firestoreSyncRepository = appContainer.firestoreSyncRepository
                                            )
                                        }
                                    }
                                )
                                HomeScreen(
                                    viewModel = vm,
                                    onOpenReader = openReader,
                                    onOpenExplorer = { navigateTopLevel(ROUTE_EXPLORER) },
                                    onOpenLibrary = { navigateTopLevel(ROUTE_LIBRARY) }
                                )
                            }

                            composable(ROUTE_EXPLORER) {
                                val vm: ExplorerViewModel = viewModel(
                                    key = "explorer_main",
                                    factory = viewModelFactory {
                                        initializer {
                                            ExplorerViewModel(
                                                database = appContainer.database,
                                                preferencesRepository = appContainer.preferencesRepository,
                                                syncRepository = appContainer.quranSyncRepository,
                                                aiRepository = appContainer.groundedAiRepository
                                            )
                                        }
                                    }
                                )
                                ExplorerScreen(
                                    viewModel = vm,
                                    initialFocusSearch = false,
                                    onOpenReader = openReader
                                )
                            }

                            composable(ROUTE_SEARCH) {
                                val vm: ExplorerViewModel = viewModel(
                                    key = "explorer_search",
                                    factory = viewModelFactory {
                                        initializer {
                                            ExplorerViewModel(
                                                database = appContainer.database,
                                                preferencesRepository = appContainer.preferencesRepository,
                                                syncRepository = appContainer.quranSyncRepository,
                                                aiRepository = appContainer.groundedAiRepository
                                            )
                                        }
                                    }
                                )
                                ExplorerScreen(
                                    viewModel = vm,
                                    initialFocusSearch = true,
                                    onOpenReader = openReader
                                )
                            }

                            composable(ROUTE_LIBRARY) {
                                val vm: LibraryViewModel = viewModel(
                                    factory = viewModelFactory {
                                        initializer {
                                            LibraryViewModel(
                                                database = appContainer.database,
                                                preferencesRepository = appContainer.preferencesRepository,
                                                firestoreSyncRepository = appContainer.firestoreSyncRepository,
                                                aiRepository = appContainer.groundedAiRepository
                                            )
                                        }
                                    }
                                )
                                LibraryScreen(
                                    viewModel = vm,
                                    onOpenReader = openReader
                                )
                            }

                            composable(ROUTE_SETTINGS) {
                                val vm: SettingsViewModel = viewModel(
                                    factory = viewModelFactory {
                                        initializer {
                                            SettingsViewModel(
                                                database = appContainer.database,
                                                preferencesRepository = appContainer.preferencesRepository,
                                                syncRepository = appContainer.quranSyncRepository,
                                                credentialManager = appContainer.credentialManager
                                            )
                                        }
                                    }
                                )
                                SettingsScreen(viewModel = vm)
                            }

                            composable(
                                route = ROUTE_READER,
                                arguments = listOf(
                                    navArgument("surahId") { type = NavType.IntType },
                                    navArgument("ayahNumber") { type = NavType.IntType }
                                )
                            ) { entry ->
                                val surahId = entry.arguments?.getInt("surahId") ?: 1
                                val ayahNumber = entry.arguments?.getInt("ayahNumber") ?: 1
                                val vm: ReaderViewModel = viewModel(
                                    key = "reader_$surahId",
                                    factory = viewModelFactory {
                                        initializer {
                                            ReaderViewModel(
                                                initialSurahId = surahId,
                                                database = appContainer.database,
                                                preferencesRepository = appContainer.preferencesRepository,
                                                syncRepository = appContainer.quranSyncRepository,
                                                audioController = appContainer.audioController,
                                                aiRepository = appContainer.groundedAiRepository,
                                                firestoreSyncRepository = appContainer.firestoreSyncRepository
                                            )
                                        }
                                    }
                                )
                                ReaderScreen(
                                    surahId = surahId,
                                    targetAyah = ayahNumber,
                                    viewModel = vm,
                                    onNavigateBack = { navController.popBackStack() }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
