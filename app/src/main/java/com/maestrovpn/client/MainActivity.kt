package com.maestrovpn.client

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.maestrovpn.client.core.engine.EngineManager
import com.maestrovpn.client.ui.deeplink.DeepLink
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

// Главная Activity (срез 1): навигация по всем экранам клиента,
// системный диалог разрешения VPN, обработка deeplink'ов maestro:// (пока — тост).
class MainActivity : ComponentActivity() {
    /** Ссылка на навигатор текущего композиции (для deeplink после setContent). */
    private var navControllerRef: androidx.navigation.NavHostController? = null

    private val vpnPrepareLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            EngineManager.startService()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingDeepLink = intent
        setContent {
            MaestroClientTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val navController = rememberNavController()
                    navControllerRef = navController
                    LaunchedEffect(navController) {
                        pendingDeepLink?.let { pending ->
                            pendingDeepLink = null
                            handleDeepLink(pending, navController)
                        }
                    }
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
                        composable(
                            route = "${Routes.SUBSCRIPTION_ADD}?url={url}",
                            arguments = listOf(
                                navArgument("url") {
                                    type = NavType.StringType
                                    defaultValue = ""
                                },
                            ),
                        ) { backStackEntry ->
                            SubscriptionAddScreen(
                                navController = navController,
                                initialUrl = backStackEntry.arguments?.getString("url").orEmpty(),
                            )
                        }
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

    /** Intent, пришедший до построения UI (onCreate/onNewIntent). */
    private var pendingDeepLink: Intent? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent, navControllerRef)
    }

    // Deeplink (спецификация §4): add / connect / disconnect / toggle.
    // Неподдержанные хосты (routing, send_to_device, crypt*) — раздел §9.2:
    // до уточнения формата показываем причину, а не молча ничего не делаем.
    private fun handleDeepLink(intent: Intent?, navController: androidx.navigation.NavController?) {
        val uri = intent?.data ?: return
        if (!uri.scheme.equals(BuildConfig.DEEP_LINK_SCHEME, ignoreCase = true)) return
        when (val link = DeepLink.parse(uri.toString())) {
            is DeepLink.Add -> {
                // Экран добавления подписки с уже заполненным URL.
                navController?.navigate(
                    "${Routes.SUBSCRIPTION_ADD}?url=${Uri.encode(link.url)}",
                )
            }
            is DeepLink.Connect -> EngineManager.startService()
            is DeepLink.Disconnect -> EngineManager.stopService()
            is DeepLink.Toggle -> {
                if (EngineManager.status.value == com.maestrovpn.client.core.engine.EngineStatus.RUNNING) {
                    EngineManager.stopService()
                } else {
                    EngineManager.startService()
                }
            }
            is DeepLink.Unsupported -> Toast.makeText(
                this,
                "Команда не поддержана: ${link.command}",
                Toast.LENGTH_SHORT,
            ).show()
            null -> Unit
        }
    }
}
