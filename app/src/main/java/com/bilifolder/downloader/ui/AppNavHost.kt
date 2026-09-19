package com.bilifolder.downloader.ui

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bilifolder.downloader.ui.screens.DownloadScreen
import com.bilifolder.downloader.ui.screens.FolderScreen
import com.bilifolder.downloader.ui.screens.LoginScreen
import com.bilifolder.downloader.ui.screens.OnboardingScreen
import com.bilifolder.downloader.ui.screens.SettingsScreen
import com.bilifolder.downloader.ui.screens.VideoLibraryScreen
import com.bilifolder.downloader.ui.screens.VideoSelectionScreen
import com.bilifolder.downloader.ui.screens.WebLoginScreen

/** 导航路由 */
object Routes {
    const val ONBOARDING = "onboarding"
    const val LOGIN = "login"
    const val WEB_LOGIN = "web_login"
    const val FOLDERS = "folders"
    const val VIDEOS = "videos/{mediaId}/{title}"
    const val DOWNLOAD = "download"
    const val SETTINGS = "settings"
    const val LIBRARY = "library"

    fun videos(mediaId: Long, title: String): String =
        "videos/$mediaId/${android.net.Uri.encode(title)}"
}

/**
 * 导航骨架（设计 4.0/4.7.3）。
 * 首启未同意合规 → onboarding；未登录 → login；否则 → folders。
 * ViewModel 以 Activity 为 owner，所有页面共享同一实例。
 */
@Composable
fun AppNavHost(activity: Activity) {
    val navController = rememberNavController()
    val viewModel: MainViewModel = viewModel(
        viewModelStoreOwner = activity as ViewModelStoreOwner,
        factory = MainViewModelFactory(activity),
    )
    val onboardingAgreed by viewModel.container.recordStore.onboardingAgreed
        .collectAsStateWithLifecycle(initialValue = false)
    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle()

    val startDestination = when {
        !onboardingAgreed -> Routes.ONBOARDING
        !isLoggedIn -> Routes.LOGIN
        else -> Routes.FOLDERS
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                viewModel = viewModel,
                onDone = {
                    if (viewModel.container.cookieStore.mid() != null) {
                        navController.navigate(Routes.FOLDERS) { popUpTo(0) }
                    } else {
                        navController.navigate(Routes.LOGIN) { popUpTo(0) }
                    }
                },
            )
        }
        composable(Routes.LOGIN) {
            LoginScreen(
                viewModel = viewModel,
                onLoggedIn = {
                    viewModel.onLoginSucceeded()
                    navController.navigate(Routes.FOLDERS) { popUpTo(0) }
                },
                onOpenWebLogin = { navController.navigate(Routes.WEB_LOGIN) },
            )
        }
        composable(Routes.WEB_LOGIN) {
            WebLoginScreen(
                client = viewModel.container.biliApiClient,
                onLoggedIn = {
                    viewModel.onLoginSucceeded()
                    navController.navigate(Routes.FOLDERS) { popUpTo(0) }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.FOLDERS) {
            FolderScreen(
                viewModel = viewModel,
                onOpenVideos = { folder ->
                    navController.navigate(Routes.videos(folder.mediaId, folder.title))
                },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenLibrary = { navController.navigate(Routes.LIBRARY) },
                onLogout = {
                    viewModel.container.cookieStore.clearSession()
                    viewModel.refreshLoginState()
                    navController.navigate(Routes.LOGIN) { popUpTo(0) }
                },
            )
        }
        composable(
            route = Routes.VIDEOS,
            arguments = listOf(
                navArgument("mediaId") { type = NavType.LongType },
                navArgument("title") { type = NavType.StringType },
            ),
        ) { backStackEntry ->
            val mediaId = backStackEntry.arguments?.getLong("mediaId") ?: 0L
            val title = backStackEntry.arguments?.getString("title") ?: ""
            VideoSelectionScreen(
                viewModel = viewModel,
                mediaId = mediaId,
                folderTitle = title,
                onBack = { navController.popBackStack() },
                onStartDownload = { requests ->
                    navController.navigate(Routes.DOWNLOAD)
                    com.bilifolder.downloader.service.DownloadService.buildStartIntent(activity, requests).let {
                        activity.startForegroundService(it)
                    }
                },
            )
        }
        composable(Routes.DOWNLOAD) {
            DownloadScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onStop = { viewModel.container.downloadManager.stop() },
                onFinished = {
                    // 任务结束（自然完成或停止后收尾）→ 回到主页面（收藏夹）
                    navController.popBackStack(Routes.FOLDERS, inclusive = false)
                },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LIBRARY) {
            VideoLibraryScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onRerun = { folder ->
                    navController.navigate(Routes.videos(folder.mediaId, folder.title))
                },
            )
        }
    }
}
