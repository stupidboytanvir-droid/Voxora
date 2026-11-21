package com.voxora.player.navigation

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.voxora.player.VoxoraApplication
import com.voxora.player.data.update.UpdateUiState
import com.voxora.player.ui.components.InAppUpdateDialog
import com.voxora.player.ui.screens.FolderDetailScreen
import com.voxora.player.ui.screens.FoldersScreen
import com.voxora.player.ui.screens.HistoryScreen
import com.voxora.player.ui.screens.HomeScreen
import com.voxora.player.ui.screens.PlayerScreen
import com.voxora.player.ui.screens.SettingsScreen
import com.voxora.player.ui.screens.VideosScreen
import com.voxora.player.ui.theme.LocalVoxoraColors
import com.voxora.player.ui.viewmodel.LibraryViewModel
import com.voxora.player.ui.viewmodel.PlayerViewModel
import com.voxora.player.ui.viewmodel.SettingsViewModel
import com.voxora.player.ui.viewmodel.UpdateViewModel
import kotlinx.serialization.Serializable
import java.io.File

@Serializable data object HomeRoute
@Serializable data object VideosRoute
@Serializable data object FoldersRoute
@Serializable data class FolderDetailRoute(val bucketId: String, val folderName: String)
@Serializable data object HistoryRoute
@Serializable data object SettingsRoute
@Serializable data class PlayerRoute(
    val uriString: String,
    val title: String,
    val startPositionMs: Long = -1L
)

private data class BottomNavTab(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val route: Any,
    val testTag: String
)

@Composable
fun VoxoraApp(
    incomingVideoUri: Uri?,
    onIncomingUriConsumed: () -> Unit,
    openUpdateDialogRequest: Boolean = false,
    onUpdateDialogRequestConsumed: () -> Unit = {},
    isInPipMode: Boolean = false,
    onEnterPipMode: (videoWidth: Int, videoHeight: Int) -> Boolean = { _, _ -> false }
) {
    val context = LocalContext.current
    val app = context.applicationContext as VoxoraApplication
    val lifecycleOwner = LocalLifecycleOwner.current
    val navController = rememberNavController()
    val voxoraColors = LocalVoxoraColors.current

    val libraryViewModel: LibraryViewModel = viewModel(factory = LibraryViewModel.Factory(app))
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory(app))
    val updateViewModel: UpdateViewModel = viewModel(factory = UpdateViewModel.Factory(app))

    val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
    val preferences by settingsViewModel.preferences.collectAsStateWithLifecycle()
    val statusBanner by settingsViewModel.statusBanner.collectAsStateWithLifecycle()
    val updateState by updateViewModel.updateState.collectAsStateWithLifecycle()
    val showUpdateDialog by updateViewModel.showUpdateDialog.collectAsStateWithLifecycle()
    val pendingInstallAfterPermission by updateViewModel.pendingInstallAfterPermission.collectAsStateWithLifecycle()

    var hasNotificationPermission by remember {
        mutableStateOf(app.updateNotificationHelper.hasNotificationPermission())
    }
    var canInstallPackages by remember {
        mutableStateOf(app.appUpdateManager.canRequestPackageInstalls())
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasNotificationPermission = granted || app.updateNotificationHelper.hasNotificationPermission()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        libraryViewModel.onPermissionResult()
    }

    val safVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            libraryViewModel.openSafVideo(uri) { video ->
                navController.navigate(
                    PlayerRoute(
                        uriString = video.uriString,
                        title = video.title,
                        startPositionMs = -1L
                    )
                )
            }
        }
    }

    fun launchApkInstaller(apkPath: String) {
        val file = File(apkPath)
        if (!file.exists()) return
        if (app.appUpdateManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(app.appUpdateManager.createInstallApkIntent(file))
            }
        } else {
            updateViewModel.markPendingInstallAfterPermission(true)
            runCatching {
                context.startActivity(app.appUpdateManager.createUnknownAppSourcesIntent())
            }
        }
    }

    // Request notification permission once on Android 13+ so update alerts appear reliably
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotificationPermission) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    // Check GitHub Releases automatically on launch when enabled
    LaunchedEffect(preferences.autoCheckUpdates) {
        updateViewModel.checkOnStartupIfEnabled(preferences.autoCheckUpdates)
    }

    // Handle tapping an update notification
    LaunchedEffect(openUpdateDialogRequest) {
        if (openUpdateDialogRequest) {
            val current = updateState
            if (current is UpdateUiState.Idle || current is UpdateUiState.UpToDate) {
                updateViewModel.checkForUpdates(isManual = true)
            }
            updateViewModel.openUpdateDialog()
            onUpdateDialogRequestConsumed()
        }
    }

    // Refresh library and re-check install/notification permissions on resume
    DisposableEffect(lifecycleOwner, updateState, pendingInstallAfterPermission) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                libraryViewModel.refreshLibrary()
                hasNotificationPermission = app.updateNotificationHelper.hasNotificationPermission()
                val nowCanInstall = app.appUpdateManager.canRequestPackageInstalls()
                canInstallPackages = nowCanInstall
                val readyState = updateState as? UpdateUiState.ReadyToInstall
                if (pendingInstallAfterPermission && nowCanInstall && readyState != null) {
                    updateViewModel.markPendingInstallAfterPermission(false)
                    launchApkInstaller(readyState.apkFilePath)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Handle external ACTION_VIEW video intent
    LaunchedEffect(incomingVideoUri) {
        if (incomingVideoUri != null) {
            libraryViewModel.openSafVideo(incomingVideoUri) { video ->
                navController.navigate(
                    PlayerRoute(
                        uriString = video.uriString,
                        title = video.title,
                        startPositionMs = -1L
                    )
                )
                onIncomingUriConsumed()
            }
        }
    }

    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val isPlayerRoute = currentDestination?.hasRoute<PlayerRoute>() == true

    val tabs = listOf(
        BottomNavTab("Home", Icons.Rounded.Home, Icons.Outlined.Home, HomeRoute, "nav_home"),
        BottomNavTab("Videos", Icons.Rounded.VideoLibrary, Icons.Outlined.VideoLibrary, VideosRoute, "nav_videos"),
        BottomNavTab("Folders", Icons.Rounded.Folder, Icons.Outlined.Folder, FoldersRoute, "nav_folders"),
        BottomNavTab("History", Icons.Rounded.History, Icons.Outlined.History, HistoryRoute, "nav_history"),
        BottomNavTab("Settings", Icons.Rounded.Settings, Icons.Outlined.Settings, SettingsRoute, "nav_settings")
    )

    fun navigateToTopTab(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    fun launchPermissionPrompt() {
        permissionLauncher.launch(libraryViewModel.getRequiredPermissions())
    }

    fun launchSafVideoPicker() {
        safVideoLauncher.launch(arrayOf("video/*"))
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = if (isPlayerRoute || isInPipMode) WindowInsets(0) else WindowInsets.statusBars,
        bottomBar = {
            if (!isPlayerRoute && !isInPipMode) {
                NavigationBar(
                    containerColor = voxoraColors.glassSurface,
                    tonalElevation = 0.dp
                ) {
                    tabs.forEach { tab ->
                        val selected = when (tab.route) {
                            HomeRoute -> currentDestination?.hasRoute<HomeRoute>() == true
                            VideosRoute -> currentDestination?.hasRoute<VideosRoute>() == true
                            FoldersRoute -> currentDestination?.hasRoute<FoldersRoute>() == true ||
                                currentDestination?.hasRoute<FolderDetailRoute>() == true
                            HistoryRoute -> currentDestination?.hasRoute<HistoryRoute>() == true
                            SettingsRoute -> currentDestination?.hasRoute<SettingsRoute>() == true
                            else -> false
                        }
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateToTopTab(tab.route) },
                            icon = {
                                Icon(
                                    imageVector = if (selected) tab.selectedIcon else tab.unselectedIcon,
                                    contentDescription = tab.label
                                )
                            },
                            label = {
                                Text(
                                    text = tab.label,
                                    maxLines = 1
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            modifier = Modifier.testTag(tab.testTag)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        val contentModifier = if (isPlayerRoute || isInPipMode) {
            Modifier.fillMaxSize()
        } else {
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
        }

        NavHost(
            navController = navController,
            startDestination = HomeRoute,
            modifier = contentModifier
        ) {
            composable<HomeRoute> {
                HomeScreen(
                    uiState = libraryState,
                    updateState = updateState,
                    onOpenUpdateDialog = updateViewModel::openUpdateDialog,
                    onPlayVideo = { video ->
                        navController.navigate(
                            PlayerRoute(video.uriString, video.title, -1L)
                        )
                    },
                    onResumeHistory = { history ->
                        navController.navigate(
                            PlayerRoute(history.uriString, history.title, history.lastPositionMs)
                        )
                    },
                    onOpenFolder = { folder ->
                        navController.navigate(
                            FolderDetailRoute(folder.bucketId, folder.name)
                        )
                    },
                    onSeeAllVideos = { navigateToTopTab(VideosRoute) },
                    onSeeAllFolders = { navigateToTopTab(FoldersRoute) },
                    onSeeAllHistory = { navigateToTopTab(HistoryRoute) },
                    onSearchClick = { navigateToTopTab(VideosRoute) },
                    onSettingsClick = { navigateToTopTab(SettingsRoute) },
                    onRequestPermission = ::launchPermissionPrompt,
                    onOpenSafVideo = ::launchSafVideoPicker,
                    onRefresh = libraryViewModel::refreshLibrary
                )
            }

            composable<VideosRoute> {
                VideosScreen(
                    uiState = libraryState,
                    onSearchQueryChange = libraryViewModel::onSearchQueryChange,
                    onSortSelected = libraryViewModel::onSortOptionSelected,
                    onRefresh = libraryViewModel::refreshLibrary,
                    onRequestPermission = ::launchPermissionPrompt,
                    onOpenSafVideo = ::launchSafVideoPicker,
                    onPlayVideo = { video ->
                        navController.navigate(
                            PlayerRoute(video.uriString, video.title, -1L)
                        )
                    }
                )
            }

            composable<FoldersRoute> {
                FoldersScreen(
                    uiState = libraryState,
                    onOpenFolder = { folder ->
                        navController.navigate(
                            FolderDetailRoute(folder.bucketId, folder.name)
                        )
                    },
                    onRefresh = libraryViewModel::refreshLibrary,
                    onRequestPermission = ::launchPermissionPrompt,
                    onOpenSafVideo = ::launchSafVideoPicker
                )
            }

            composable<FolderDetailRoute> { entry ->
                val route = entry.toRoute<FolderDetailRoute>()
                val folderVideos = libraryViewModel.videosInFolder(route.bucketId)
                FolderDetailScreen(
                    folderName = route.folderName,
                    videos = folderVideos,
                    uiState = libraryState,
                    onBack = { navController.popBackStack() },
                    onSortSelected = libraryViewModel::onSortOptionSelected,
                    onPlayVideo = { video ->
                        navController.navigate(
                            PlayerRoute(video.uriString, video.title, -1L)
                        )
                    }
                )
            }

            composable<HistoryRoute> {
                HistoryScreen(
                    uiState = libraryState,
                    onResumeHistory = { history ->
                        navController.navigate(
                            PlayerRoute(history.uriString, history.title, history.lastPositionMs)
                        )
                    },
                    onRemoveItem = libraryViewModel::removeHistoryItem,
                    onClearHistory = libraryViewModel::clearHistory
                )
            }

            composable<SettingsRoute> {
                SettingsScreen(
                    preferences = preferences,
                    statusBanner = statusBanner,
                    viewModel = settingsViewModel,
                    updateState = updateState,
                    hasNotificationPermission = hasNotificationPermission,
                    onCheckForUpdates = { updateViewModel.checkForUpdates(isManual = true) },
                    onOpenUpdateDialog = updateViewModel::openUpdateDialog,
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                )
            }

            composable<PlayerRoute> { entry ->
                val route = entry.toRoute<PlayerRoute>()
                val playerViewModel: PlayerViewModel = viewModel(
                    viewModelStoreOwner = entry,
                    factory = PlayerViewModel.Factory(app)
                )
                PlayerScreen(
                    uriString = route.uriString,
                    videoTitle = route.title,
                    startPositionMs = route.startPositionMs,
                    viewModel = playerViewModel,
                    isInPipMode = isInPipMode,
                    onEnterPipMode = onEnterPipMode,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }

    if (showUpdateDialog) {
        InAppUpdateDialog(
            state = updateState,
            canInstallPackages = canInstallPackages,
            onDownload = updateViewModel::startDownload,
            onCancelDownload = updateViewModel::cancelDownload,
            onInstallApk = ::launchApkInstaller,
            onOpenUnknownSourcesSettings = {
                updateViewModel.markPendingInstallAfterPermission(true)
                runCatching {
                    context.startActivity(app.appUpdateManager.createUnknownAppSourcesIntent())
                }
            },
            onOpenReleasePage = { url ->
                runCatching {
                    context.startActivity(app.appUpdateManager.createOpenReleasePageIntent(url))
                }
            },
            onRetryCheck = { updateViewModel.checkForUpdates(isManual = true) },
            onDismiss = updateViewModel::dismissUpdateDialog
        )
    }
}
