package com.maestrovpn.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.maestrovpn.client.R
import com.maestrovpn.client.core.engine.EngineManager
import com.maestrovpn.client.core.engine.EngineStatus
import com.maestrovpn.client.core.subscription.SubscriptionManager
import com.maestrovpn.client.ui.Coin
import com.maestrovpn.client.ui.TelegramButtons
import com.maestrovpn.client.ui.navigation.Routes
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Главный экран (срез 1): монета + кнопка питания + список подписок.
// Стиль — как у Maestro: тёмный фон, золотая монета, статус.
// Серверы из подписок появятся после парсера (срез 2); пока список подписок.
@Composable
fun DashboardScreen(
    navController: NavController,
    onPowerClick: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val status by EngineManager.status.collectAsState()
    val statusMessage by EngineManager.statusMessage.collectAsState()
    val manager = remember { SubscriptionManager(context, EngineManager.engine) }
    var subscriptions by remember { mutableStateOf(manager.list()) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    val connected = status == EngineStatus.RUNNING
    val startedAt = EngineManager.startedAtMillis
    LaunchedEffect(connected) {
        while (connected) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.dashboard_title))
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Coin(
                    size = 220.dp,
                    connected = connected,
                    connecting = status == EngineStatus.STARTING,
                    onClick = {
                        when {
                            connected -> EngineManager.stopService()
                            EngineManager.currentConfigJson.isNullOrBlank() -> {
                                scope.launch {
                                    snackbar.showSnackbar(context.getString(R.string.no_server_selected))
                                }
                            }
                            else -> onPowerClick()
                        }
                    },
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    when (status) {
                        EngineStatus.RUNNING -> stringResource(R.string.status_connected)
                        EngineStatus.STARTING -> stringResource(R.string.status_connecting)
                        EngineStatus.ERROR -> stringResource(R.string.status_error)
                        EngineStatus.STOPPED -> stringResource(R.string.status_disconnected)
                    },
                )
                if (startedAt != null && connected) {
                    val seconds = (now - startedAt) / 1000
                    Text(
                        stringResource(R.string.session_time) + ": %02d:%02d".format(seconds / 60, seconds % 60),
                    )
                }
                if (status == EngineStatus.ERROR && statusMessage != null) {
                    Text(statusMessage!!)
                }
                Text(
                    if (connected) stringResource(R.string.tap_coin_to_disconnect)
                    else stringResource(R.string.tap_coin_to_connect),
                )
                Spacer(Modifier.height(16.dp))
                if (subscriptions.isEmpty()) {
                    Text(stringResource(R.string.no_server_title))
                    Text(stringResource(R.string.no_server_hint))
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = { navController.navigate(Routes.IMPORT_CLIPBOARD) }) {
                            Text(stringResource(R.string.import_clipboard))
                        }
                        OutlinedButton(onClick = { navController.navigate(Routes.QR_SCAN) }) {
                            Text(stringResource(R.string.import_qr))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { navController.navigate(Routes.SUBSCRIPTION_ADD) }) {
                        Text(stringResource(R.string.add_subscription))
                    }
                }
                Spacer(Modifier.height(16.dp))
                TelegramButtons()
                Spacer(Modifier.height(16.dp))
            }

            items(subscriptions, key = { it.id }) { sub ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(sub.name + if (sub.id == selectedId) " ✓" else "")
                            Text(
                                sub.url,
                                maxLines = 1,
                            )
                            val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                            Text(fmt.format(Date(sub.lastUpdatedMillis)))
                        }
                        IconButton(onClick = {
                            scope.launch {
                                val result = manager.refresh(sub)
                                subscriptions = manager.list()
                                snackbar.showSnackbar(
                                    if (result.isSuccess) context.getString(R.string.sub_saved)
                                    else context.getString(R.string.sub_error),
                                )
                            }
                        }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "refresh")
                        }
                    }
                }
                // Выбор подписки как активного конфига (срез 1: сырой текст,
                // парсер серверов — срез 2).
                    OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val content = manager.loadContent(sub)
                        if (content != null) {
                            EngineManager.setConfig(content)
                            selectedId = sub.id
                            scope.launch { snackbar.showSnackbar(sub.name) }
                        }
                    },
                ) {
                    Text(stringResource(R.string.select_active))
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { navController.navigate(Routes.SUBSCRIPTIONS) }) {
                        Text(stringResource(R.string.subscriptions_title))
                    }
                    OutlinedButton(onClick = { navController.navigate(Routes.SERVERS) }) {
                        Text(stringResource(R.string.servers_title))
                    }
                    OutlinedButton(onClick = {
                        scope.launch {
                            snackbar.showSnackbar(context.getString(R.string.ping_not_measured))
                        }
                    }) {
                        Text(stringResource(R.string.test_ping))
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
