package dev.cmuxandroid.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.cmuxandroid.CmuxApplication

sealed interface LaunchTarget {
    data class Pair(val url: String, val code: String) : LaunchTarget
    data class Terminal(val macId: String, val workspaceId: String, val surfaceId: String) : LaunchTarget
}

private object Routes {
    const val MACS = "macs"
    const val PAIR = "pair?url={url}&code={code}"
    const val WORKSPACES = "mac/{macId}"
    const val TERMINAL = "terminal/{macId}/{workspaceId}/{surfaceId}"

    fun pair(url: String = "", code: String = "") = "pair?url=${Uri.encode(url)}&code=${Uri.encode(code)}"
    fun workspaces(macId: String) = "mac/${Uri.encode(macId)}"
    fun terminal(macId: String, workspaceId: String, surfaceId: String) =
        "terminal/${Uri.encode(macId)}/${Uri.encode(workspaceId)}/${Uri.encode(surfaceId)}"
}

@Composable
fun AppNavigation(app: CmuxApplication, launchTarget: LaunchTarget?, onLaunchTargetHandled: () -> Unit) {
    val navController = rememberNavController()

    LaunchedEffect(launchTarget) {
        when (launchTarget) {
            null -> return@LaunchedEffect
            is LaunchTarget.Pair -> navController.navigate(Routes.pair(launchTarget.url, launchTarget.code))
            is LaunchTarget.Terminal -> {
                navController.popBackStack(Routes.MACS, inclusive = false)
                navController.navigate(Routes.workspaces(launchTarget.macId))
                navController.navigate(Routes.terminal(launchTarget.macId, launchTarget.workspaceId, launchTarget.surfaceId))
            }
        }
        onLaunchTargetHandled()
    }

    NavHost(navController = navController, startDestination = Routes.MACS) {
        composable(Routes.MACS) {
            MacsScreen(
                app = app,
                onOpenMac = { navController.navigate(Routes.workspaces(it.id)) },
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
                onOpenTerminal = { workspaceId, surfaceId -> navController.navigate(Routes.terminal(macId, workspaceId, surfaceId)) },
            )
        }
        composable(
            Routes.TERMINAL,
            arguments = listOf(
                navArgument("macId") { type = NavType.StringType },
                navArgument("workspaceId") { type = NavType.StringType },
                navArgument("surfaceId") { type = NavType.StringType },
            ),
        ) { entry ->
            TerminalScreen(
                app = app,
                macId = entry.arguments?.getString("macId").orEmpty(),
                workspaceId = entry.arguments?.getString("workspaceId").orEmpty(),
                surfaceId = entry.arguments?.getString("surfaceId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}
