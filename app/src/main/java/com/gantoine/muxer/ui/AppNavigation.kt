package com.gantoine.muxer.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.gantoine.muxer.MuxerApplication
import com.gantoine.muxer.share.ShareRoute
import com.gantoine.muxer.share.ShareRouting
import com.gantoine.muxer.share.ShareTarget
import kotlinx.coroutines.flow.first

sealed interface LaunchTarget {
    data class Pair(val url: String, val code: String) : LaunchTarget
    data class Terminal(val macId: String, val workspaceId: String, val surfaceId: String) : LaunchTarget
    data object PairComputer : LaunchTarget
    data class Share(val text: String, val shortcutId: String?) : LaunchTarget
}

private object Routes {
    const val MACS = "macs"
    const val PAIR = "pair?url={url}&code={code}"
    const val WORKSPACES = "mac/{macId}"
    const val SETTINGS = "mac/{macId}/settings"
    const val TERMINAL = "terminal/{macId}/{workspaceId}/{surfaceId}?message={message}"
    const val SHARE = "share?text={text}"

    fun pair(url: String = "", code: String = "") = "pair?url=${Uri.encode(url)}&code=${Uri.encode(code)}"
    fun workspaces(macId: String) = "mac/${Uri.encode(macId)}"
    fun settings(macId: String) = "mac/${Uri.encode(macId)}/settings"
    fun terminal(macId: String, workspaceId: String, surfaceId: String, message: String = "") =
        "terminal/${Uri.encode(macId)}/${Uri.encode(workspaceId)}/${Uri.encode(surfaceId)}?message=${Uri.encode(message)}"
    fun share(text: String) = "share?text=${Uri.encode(text)}"
}

@Composable
fun AppNavigation(app: MuxerApplication, launchTarget: LaunchTarget?, onLaunchTargetHandled: () -> Unit) {
    val navController = rememberNavController()

    LaunchedEffect(launchTarget) {
        when (launchTarget) {
            null -> return@LaunchedEffect
            is LaunchTarget.Pair -> navController.navigate(Routes.pair(launchTarget.url, launchTarget.code))
            LaunchTarget.PairComputer -> {
                navController.popBackStack(Routes.MACS, inclusive = false)
                navController.navigate(Routes.pair())
            }
            is LaunchTarget.Terminal -> {
                navController.popBackStack(Routes.MACS, inclusive = false)
                navController.navigate(Routes.workspaces(launchTarget.macId))
                navController.navigate(Routes.terminal(launchTarget.macId, launchTarget.workspaceId, launchTarget.surfaceId))
            }
            is LaunchTarget.Share -> {
                val route = ShareRouting.route(
                    shortcutId = launchTarget.shortcutId,
                    snapshots = app.snapshots.current().values,
                    pairedMacIds = app.store.macs.first().map { it.id },
                )
                when (route) {
                    is ShareRoute.Open -> navController.openShared(route.target, launchTarget.text)
                    ShareRoute.Pick -> {
                        navController.popBackStack(Routes.MACS, inclusive = false)
                        navController.navigate(Routes.share(launchTarget.text))
                    }
                }
            }
        }
        onLaunchTargetHandled()
    }

    NavHost(navController = navController, startDestination = Routes.MACS) {
        composable(Routes.MACS) {
            MacsScreen(
                app = app,
                onOpenMac = { navController.navigate(Routes.workspaces(it.id)) },
                onOpenSettings = { navController.navigate(Routes.settings(it.id)) },
                onAddMac = { navController.navigate(Routes.pair()) },
            )
        }
        composable(
            Routes.PAIR,
            arguments = listOf(
                navArgument("url") { type = NavType.StringType; defaultValue = "" },
                navArgument("code") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            PairScreen(
                app = app,
                initialUrl = entry.arguments?.getString("url").orEmpty(),
                initialCode = entry.arguments?.getString("code").orEmpty(),
                onBack = { navController.popBackStack() },
                onPaired = { macId ->
                    navController.popBackStack(Routes.MACS, inclusive = false)
                    navController.navigate(Routes.workspaces(macId))
                },
            )
        }
        composable(Routes.WORKSPACES, arguments = listOf(navArgument("macId") { type = NavType.StringType })) { entry ->
            val macId = entry.arguments?.getString("macId").orEmpty()
            WorkspacesScreen(
                app = app,
                macId = macId,
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.navigate(Routes.settings(macId)) },
                onOpenTerminal = { workspaceId, surfaceId -> navController.navigate(Routes.terminal(macId, workspaceId, surfaceId)) },
            )
        }
        composable(Routes.SETTINGS, arguments = listOf(navArgument("macId") { type = NavType.StringType })) { entry ->
            ComputerSettingsScreen(
                app = app,
                macId = entry.arguments?.getString("macId").orEmpty(),
                onBack = { navController.popBackStack() },
                onRemoved = { navController.popBackStack(Routes.MACS, inclusive = false) },
            )
        }
        composable(
            Routes.TERMINAL,
            arguments = listOf(
                navArgument("macId") { type = NavType.StringType },
                navArgument("workspaceId") { type = NavType.StringType },
                navArgument("surfaceId") { type = NavType.StringType },
                navArgument("message") { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            TerminalScreen(
                app = app,
                macId = entry.arguments?.getString("macId").orEmpty(),
                workspaceId = entry.arguments?.getString("workspaceId").orEmpty(),
                surfaceId = entry.arguments?.getString("surfaceId").orEmpty(),
                initialMessage = entry.arguments?.getString("message").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SHARE, arguments = listOf(navArgument("text") { type = NavType.StringType; defaultValue = "" })) { entry ->
            val text = entry.arguments?.getString("text").orEmpty()
            ShareScreen(
                app = app,
                text = text,
                onBack = { navController.popBackStack() },
                onPick = { target -> navController.openShared(target, text) },
            )
        }
    }
}

private fun NavHostController.openShared(target: ShareTarget, text: String) {
    popBackStack(Routes.MACS, inclusive = false)
    navigate(Routes.workspaces(target.macId))
    navigate(Routes.terminal(target.macId, target.workspace.id, target.surfaceId, text))
}
