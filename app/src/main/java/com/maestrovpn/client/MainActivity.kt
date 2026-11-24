package com.maestrovpn.client

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.maestrovpn.client.core.engine.EngineManager
import com.maestrovpn.client.ui.navigation.Routes
import com.maestrovpn.client.ui.screens.AboutScreen
import com.maestrovpn.client.ui.screens.DashboardScreen
import com.maestrovpn.client.ui.screens.ServerEditorScreen
import com.maestrovpn.client.ui.screens.ServersScreen
import com.maestrovpn.client.ui.screens.SettingsScreen
import com.maestrovpn.client.ui.screens.StubScreen
import com.maestrovpn.client.ui.screens.SubscriptionAddScreen
import com.maestrovpn.client.ui.screens.SubscriptionsScreen
import com.maestrovpn.client.ui.theme.MaestroClientTheme

// Главная Activity (срез 1): навигация по всем экранам Happ (заглушки),
// системный диалог разрешения VPN, обработка deeplink'ов maestro:// (пока — тост).
class MainActivity : ComponentActivity() {
    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            EngineManager.startService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        setContent {
            MaestroClientTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    NavHost(navController = navController, startDestination = Routes.MAIN) {
                        composable(Routes.MAIN) {
                            DashboardScreen(
                                navController = navController,
                                onPowerClick = {
                                    val prepare = EngineManager.prepareIntent(this@MainActivity)
                                    if (prepare == null) {
                                        EngineManager.startService()
                                    } else {
                                        vpnPrepareLauncher.launch(prepare)
                                    }
                                },
                            )
                        }
                        composable(Routes.SETTINGS) { SettingsScreen(navController) }
                        composable(Routes.ABOUT) { AboutScreen() }
                        composable(Routes.SUBSCRIPTIONS) { SubscriptionsScreen(navController) }
                        composable(Routes.SUBSCRIPTION_ADD) { SubscriptionAddScreen(navController) }
                        // Серверы и редактор сервера — настоящие экраны (срез 5, issue #1).
                        composable(Routes.SERVERS) { ServersScreen(navController) }
                        composable(
                            route = "${Routes.SERVER_EDITOR}?id={id}&protocol={protocol}",
                            arguments = listOf(
                                navArgument("id") {
                                    type = NavType.StringType
                                    defaultValue = ""
                                },
                                navArgument("protocol") {
                                    type = NavType.StringType
                                    defaultValue = "vless"
                                },
                            ),
                        ) { backStackEntry ->
                            ServerEditorScreen(
                                navController = navController,
                                serverId = backStackEntry.arguments
                                    ?.getString("id")?.takeIf { it.isNotEmpty() },
                                protocolArg = backStackEntry.arguments?.getString("protocol"),
                            )
                        }
                        for (spec in Routes.stubs) {
                            composable(spec.route) { StubScreen(spec) }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    // TODO (срез 2): настоящий разбор maestro://add, maestro://routing,
    // maestro://toggle/connect/disconnect (таблица соответствия §8.3–8.4).
    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme == BuildConfig.DEEP_LINK_SCHEME) {
            Toast.makeText(this, "maestro:// ${uri.host}${uri.path}", Toast.LENGTH_SHORT).show()
        }
    }
}
