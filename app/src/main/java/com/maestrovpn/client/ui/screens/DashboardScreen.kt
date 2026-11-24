package com.maestrovpn.client.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.maestrovpn.client.R
import com.maestrovpn.client.core.engine.EngineManager
import com.maestrovpn.client.core.engine.EngineStatus
import com.maestrovpn.client.core.subscription.ImportResult
import com.maestrovpn.client.core.subscription.ParsedSubscription
import com.maestrovpn.client.core.subscription.ServerKind
import com.maestrovpn.client.core.subscription.SubscriptionFormat
import com.maestrovpn.client.core.subscription.SubscriptionManager
import com.maestrovpn.client.ui.Coin
import com.maestrovpn.client.ui.TelegramButtons
import com.maestrovpn.client.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Главный экран (срез 3): монета + список серверов + премиальное меню.
//
// Монета НЕ тронута: тот же Coin 220dp, тот же статус, та же логика тапа
// (подтверждена Евгением в срезе 2 как в Maestro).
//
// Дефект среза 2 (меню): кнопки лежали неровной сеткой, «Проверить пинг»
// переносился на три строки, размеры разные. Теперь: ровная сетка 2×N из
// одинаковых кнопок (56dp, одна строка, без переносов), иерархия — главное
// действие (подключение монеткой + «Добавить подписку») визуально главнее
// второстепенных, секции разделены подписями и отступами.
//
// Дефект среза 2 (подписки): выбор подписки ставил сырое тело как конфиг,
// что для Xray-формата всегда падало. Теперь выбор сервера — через
// buildConfigFor (парсер + сборка sing-box конфига + проверка движком)
// и prepareServer (поднять/остановить Xray-раннер под выбранный тег).
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
    var parsedById by remember { mutableStateOf<Map<String, ParsedSubscription>>(emptyMap()) }
    var selectedSubId by remember { mutableStateOf<String?>(null) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var selectingTag by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    val connected = status == EngineStatus.RUNNING
    val startedAt = EngineManager.startedAtMillis
    LaunchedEffect(connected) {
        while (connected) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }

    // Парсинг сохранённых подписок — в фоне, при каждом изменении списка.
    LaunchedEffect(subscriptions) {
        parsedById = withContext(Dispatchers.IO) {
            subscriptions.associate { it.id to manager.servers(it) }
        }
    }

    // Восстановить выбор сервера после рестарта: собрать конфиг и поставить
    // его текущим. Раннер при этом не трогаем — его поднимет restore()
    // в applySelectedContent при старте службы.
    LaunchedEffect(Unit) {
        if (EngineManager.currentConfigJson.isNullOrBlank()) {
            val selection = withContext(Dispatchers.IO) { manager.loadSelection() }
            if (selection != null) {
                val sub = subscriptions.firstOrNull { it.id == selection.subscriptionId }
                if (sub != null) {
                    val config = withContext(Dispatchers.IO) {
                        manager.buildConfigFor(sub, selection.tag).getOrNull()
                    }
                    if (config != null) {
                        EngineManager.setConfig(config)
                        selectedSubId = selection.subscriptionId
                        selectedTag = selection.tag
                    }
                }
            }
        }
    }

    fun selectServer(subId: String, tag: String) {
        val sub = subscriptions.firstOrNull { it.id == subId } ?: return
        selectingTag = tag
        scope.launch {
            val config = withContext(Dispatchers.IO) {
                manager.buildConfigFor(sub, tag)
            }
            val configJson = config.getOrNull()
            if (configJson == null) {
                selectingTag = null
                snackbar.showSnackbar(
                    context.getString(
                        R.string.server_build_failed,
                        config.exceptionOrNull()?.message ?: "?",
                    ),
                )
                return@launch
            }
            val prepared = withContext(Dispatchers.IO) {
                EngineManager.engine.prepareServer(tag, configJson)
            }
            selectingTag = null
            if (!prepared) {
                snackbar.showSnackbar(context.getString(R.string.server_prepare_failed, tag))
                return@launch
            }
            EngineManager.setConfig(configJson)
            withContext(Dispatchers.IO) { manager.saveSelection(subId, tag) }
            selectedSubId = subId
            selectedTag = tag
            snackbar.showSnackbar(context.getString(R.string.server_selected, tag))
        }
    }

    fun refreshSubscription(subId: String) {
        val sub = subscriptions.firstOrNull { it.id == subId } ?: return
        scope.launch {
            val result = withContext(Dispatchers.IO) { manager.refresh(sub) }
            subscriptions = manager.list()
            snackbar.showSnackbar(
                when (result) {
                    is ImportResult.Success -> context.getString(R.string.sub_saved)
                    is ImportResult.Failure -> result.reason
                },
            )
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
            }

            if (subscriptions.isEmpty()) {
                item {
                    Text(stringResource(R.string.no_server_title))
                    Text(stringResource(R.string.no_server_hint))
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DashboardActionButton(
                            text = stringResource(R.string.import_clipboard),
                            onClick = { navController.navigate(Routes.IMPORT_CLIPBOARD) },
                        )
                        DashboardActionButton(
                            text = stringResource(R.string.import_qr),
                            onClick = { navController.navigate(Routes.QR_SCAN) },
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        onClick = { navController.navigate(Routes.SUBSCRIPTION_ADD) },
                    ) {
                        Text(
                            stringResource(R.string.add_subscription),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            } else {
                item {
                    SectionCaption(stringResource(R.string.dashboard_servers))
                    Spacer(Modifier.height(8.dp))
                }
                items(subscriptions, key = { it.id }) { sub ->
                    val parsed = parsedById[sub.id]
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(sub.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                                    Text(
                                        fmt.format(Date(sub.lastUpdatedMillis)),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { refreshSubscription(sub.id) }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "refresh")
                                }
                            }
                            if (parsed != null) {
                                when {
                                    parsed.format == SubscriptionFormat.SING_BOX_CONFIG -> {
                                        ServerRow(
                                            tag = sub.name,
                                            detail = "sing-box",
                                            selected = selectedSubId == sub.id && selectedTag == sub.name,
                                            busy = selectingTag == sub.name,
                                            onClick = { selectServer(sub.id, sub.name) },
                                        )
                                    }
                                    parsed.servers.isEmpty() -> {
                                        Text(
                                            stringResource(R.string.sub_invalid),
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    else -> {
                                        parsed.servers.forEach { server ->
                                            ServerRow(
                                                tag = server.tag,
                                                detail = server.protocol +
                                                    (if (server.kind == ServerKind.XHTTP_RUNNER) " · xhttp" else "") +
                                                    " · " + server.address,
                                                selected = selectedSubId == sub.id && selectedTag == server.tag,
                                                busy = selectingTag == server.tag,
                                                onClick = { selectServer(sub.id, server.tag) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item {
                    Spacer(Modifier.height(12.dp))
                    OutlinedButton(
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        onClick = { navController.navigate(Routes.SUBSCRIPTION_ADD) },
                    ) {
                        Text(
                            stringResource(R.string.add_subscription),
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }

            item {
                SectionCaption(stringResource(R.string.dashboard_actions))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DashboardActionButton(
                        text = stringResource(R.string.import_clipboard),
                        onClick = { navController.navigate(Routes.IMPORT_CLIPBOARD) },
                    )
                    DashboardActionButton(
                        text = stringResource(R.string.import_qr),
                        onClick = { navController.navigate(Routes.QR_SCAN) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DashboardActionButton(
                        text = stringResource(R.string.subscriptions_title),
                        onClick = { navController.navigate(Routes.SUBSCRIPTIONS) },
                    )
                    DashboardActionButton(
                        text = stringResource(R.string.servers_title),
                        onClick = { navController.navigate(Routes.SERVERS) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    onClick = {
                        scope.launch {
                            snackbar.showSnackbar(context.getString(R.string.ping_not_measured))
                        }
                    },
                ) {
                    Text(
                        stringResource(R.string.test_ping),
                        maxLines = 1,
                        softWrap = false,
                    )
                }
                Spacer(Modifier.height(20.dp))
                SectionCaption(stringResource(R.string.dashboard_contact))
                Spacer(Modifier.height(8.dp))
                TelegramButtons()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/** Подпись секции меню: мелкий приглушённый текст, слева. */
@Composable
private fun SectionCaption(text: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Кнопка премиальной сетки: одинаковая высота 56dp, одна строка без переносов,
 * равная ширина в ряду (через weight). Дефект среза 2 — кнопки были разной
 * высоты и «Проверить пинг» переносился на три строки.
 */
@Composable
private fun RowScope.DashboardActionButton(text: String, onClick: () -> Unit) {
    OutlinedButton(
        modifier = Modifier.weight(1f).height(56.dp),
        onClick = onClick,
    ) {
        Text(
            text,
            maxLines = 1,
            softWrap = false,
            fontSize = 15.sp,
        )
    }
}

/** Строка сервера в подписке: тег + протокол/адрес, галочка у выбранного. */
@Composable
private fun ServerRow(
    tag: String,
    detail: String,
    selected: Boolean,
    busy: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            busy -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            selected -> Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
