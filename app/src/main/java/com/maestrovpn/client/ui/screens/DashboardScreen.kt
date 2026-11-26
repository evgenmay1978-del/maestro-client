package com.maestrovpn.client.ui.screens

import android.content.res.Configuration
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.maestrovpn.client.R
import com.maestrovpn.client.core.engine.EngineManager
import com.maestrovpn.client.core.engine.EngineStatus
import com.maestrovpn.client.core.subscription.ImportResult
import com.maestrovpn.client.core.subscription.ParsedSubscription
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
// Монета НЕ тронута: тот же Coin, тот же статус, та же логика тапа
// (подтверждена Евгением в срезе 2 как в Maestro).
//
// Дефект среза 2 (меню): кнопки лежали неровной сеткой, «Проверить пинг»
// переносился на три строки, размеры разные. Теперь: ровная сетка 2×N из
// одинаковых кнопок (одна строка, без переносов), иерархия — главное
// действие (подключение монеткой + «Добавить подписку») визуально главнее
// второстепенных, секции разделены подписями и отступами.
//
// Issue #9 (срез 7): «Буфер обмена»/«QR-код» дублировались — в блоке пустого
// состояния и в «Быстрых действиях». В пустом состоянии остался только
// «Добавить подписку»; clipboard/QR живут только в «Быстрых действиях».
//
// Дефект среза 2 (подписки): выбор подписки ставил сырое тело как конфиг,
// что для Xray-формата всегда падало. Теперь выбор сервера — через
// buildConfigFor (парсер + сборка Xray-конфига + проверка движком)
// и prepareServer (Xray не требует подготовки: no-op).
//
// TV (issue #7): отдельного экрана нет — размеры берутся из DashboardMetrics,
// который читает uiMode. Один код на телефон и телевизор: дубль экрана
// разъезжается при каждой правке логики, а D-pad фокус Compose даёт сам
// (кликабельные элементы фокусируемы по умолчанию), поэтому FocusRequester
// и leanback-библиотеки не нужны.
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
    val manualStore = remember { com.maestrovpn.client.core.subscription.ManualServerStore(context, EngineManager.engine) }
    var subscriptions by remember { mutableStateOf(manager.list()) }
    var parsedById by remember { mutableStateOf<Map<String, ParsedSubscription>>(emptyMap()) }
    var selectedSubId by remember { mutableStateOf<String?>(null) }
    var selectedTag by remember { mutableStateOf<String?>(null) }
    var selectingTag by remember { mutableStateOf<String?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    val metrics = dashboardMetrics()

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
    // его текущим. Сначала выбор подписки, затем (срез 5) — выбор ручного сервера.
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
        if (EngineManager.currentConfigJson.isNullOrBlank()) {
            val manualSelection = withContext(Dispatchers.IO) { manualStore.loadSelection() }
            if (manualSelection != null) {
                val manual = withContext(Dispatchers.IO) {
                    manualStore.get(manualSelection.serverId)
                }
                if (manual != null) {
                    val built = withContext(Dispatchers.IO) {
                        manualStore.buildConfigFor(manual)
                    }
                    built.getOrNull()?.let { EngineManager.setConfig(it) }
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
            withContext(Dispatchers.IO) {
                manager.saveSelection(subId, tag)
                // Один активный сервер на всё приложение: выбор подписки
                // снимает выбор ручного сервера (и наоборот — см. ServersScreen).
                manualStore.clearSelection()
            }
            selectedSubId = subId
            selectedTag = tag
            snackbar.showSnackbar(context.getString(R.string.server_selected, tag))
        }
    }

    fun refreshSubscription(subId: String) {
        val sub = subscriptions.firstOrNull { it.id == subId } ?: return
        scope.launch {
            val result = manager.refresh(sub)
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
                .padding(horizontal = metrics.horizontalPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.dashboard_title),
                        fontSize = metrics.titleFont,
                    )
                    IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings_title))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Coin(
                    size = metrics.coinSize,
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
                    fontSize = metrics.statusFont,
                )
                if (startedAt != null && connected) {
                    val seconds = (now - startedAt) / 1000
                    Text(
                        stringResource(R.string.session_time) + ": %02d:%02d".format(seconds / 60, seconds % 60),
                        fontSize = metrics.sessionFont,
                    )
                }
                if (status == EngineStatus.ERROR && statusMessage != null) {
                    Text(statusMessage!!, fontSize = metrics.detailFont)
                }
                Text(
                    if (connected) stringResource(R.string.tap_coin_to_disconnect)
                    else stringResource(R.string.tap_coin_to_connect),
                    fontSize = metrics.detailFont,
                )
                Spacer(Modifier.height(16.dp))
            }

            if (subscriptions.isEmpty()) {
                item {
                    Text(stringResource(R.string.no_server_title), fontSize = metrics.statusFont)
                    Text(stringResource(R.string.no_server_hint), fontSize = metrics.detailFont)
                    Spacer(Modifier.height(16.dp))
                    Button(
                        modifier = Modifier.fillMaxWidth().height(metrics.buttonHeight),
                        onClick = { navController.navigate(Routes.SUBSCRIPTION_ADD) },
                    ) {
                        Text(
                            stringResource(R.string.add_subscription),
                            maxLines = 1,
                            softWrap = false,
                            fontSize = metrics.buttonTextFont,
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                }
            } else {
                item {
                    SectionCaption(stringResource(R.string.dashboard_servers), metrics.captionFont)
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
                                    Text(
                                        sub.name,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        fontSize = metrics.rowTitleFont,
                                    )
                                    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                                    Text(
                                        fmt.format(Date(sub.lastUpdatedMillis)),
                                        fontSize = metrics.detailFont,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                IconButton(onClick = { refreshSubscription(sub.id) }) {
                                    Icon(Icons.Filled.Refresh, contentDescription = "refresh")
                                }
                            }
                            if (parsed != null) {
                                when {
                                    parsed.servers.isEmpty() -> {
                                        Text(
                                            stringResource(R.string.sub_invalid),
                                            fontSize = metrics.detailFont,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    else -> {
                                        parsed.servers.forEach { server ->
                                            ServerRow(
                                                tag = server.tag,
                                                detail = server.protocol +
                                                    (if (server.network == "xhttp") " · xhttp" else "") +
                                                    " · " + server.address,
                                                selected = selectedSubId == sub.id && selectedTag == server.tag,
                                                busy = selectingTag == server.tag,
                                                rowTitleFont = metrics.rowTitleFont,
                                                detailFont = metrics.detailFont,
                                                spinnerSize = metrics.spinnerSize,
                                                verticalPadding = metrics.rowVerticalPadding,
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
                        modifier = Modifier.fillMaxWidth().height(metrics.buttonHeight),
                        onClick = { navController.navigate(Routes.SUBSCRIPTION_ADD) },
                    ) {
                        Text(
                            stringResource(R.string.add_subscription),
                            maxLines = 1,
                            softWrap = false,
                            fontSize = metrics.buttonTextFont,
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }

            item {
                SectionCaption(stringResource(R.string.dashboard_actions), metrics.captionFont)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DashboardActionButton(
                        text = stringResource(R.string.import_clipboard),
                        height = metrics.buttonHeight,
                        fontSize = metrics.buttonTextFont,
                        onClick = { navController.navigate(Routes.IMPORT_CLIPBOARD) },
                    )
                    DashboardActionButton(
                        text = stringResource(R.string.import_qr),
                        height = metrics.buttonHeight,
                        fontSize = metrics.buttonTextFont,
                        onClick = { navController.navigate(Routes.QR_SCAN) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DashboardActionButton(
                        text = stringResource(R.string.subscriptions_title),
                        height = metrics.buttonHeight,
                        fontSize = metrics.buttonTextFont,
                        onClick = { navController.navigate(Routes.SUBSCRIPTIONS) },
                    )
                    DashboardActionButton(
                        text = stringResource(R.string.servers_title),
                        height = metrics.buttonHeight,
                        fontSize = metrics.buttonTextFont,
                        onClick = { navController.navigate(Routes.SERVERS) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth().height(metrics.buttonHeight),
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
                        fontSize = metrics.buttonTextFont,
                    )
                }
                Spacer(Modifier.height(20.dp))
                SectionCaption(stringResource(R.string.dashboard_contact), metrics.captionFont)
                Spacer(Modifier.height(8.dp))
                TelegramButtons()
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

/**
 * Размеры главного экрана. Телефон — как подтверждено в срезах 1–7,
 * телевизор (10-foot UI) — крупнее: монета 280dp, кнопки 64dp, шрифты +40–75%.
 * TextUnit.Unspecified = размер из темы (значит «не переопределять»).
 */
private data class DashboardMetrics(
    val horizontalPadding: Dp,
    val coinSize: Dp,
    val titleFont: TextUnit,
    val statusFont: TextUnit,
    val sessionFont: TextUnit,
    val detailFont: TextUnit,
    val captionFont: TextUnit,
    val rowTitleFont: TextUnit,
    val buttonHeight: Dp,
    val buttonTextFont: TextUnit,
    val rowVerticalPadding: Dp,
    val spinnerSize: Dp,
)

@Composable
private fun dashboardMetrics(): DashboardMetrics {
    val uiMode = LocalConfiguration.current.uiMode
    val isTv = uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    return if (isTv) {
        DashboardMetrics(
            horizontalPadding = 48.dp,
            coinSize = 280.dp,
            titleFont = 28.sp,
            statusFont = 22.sp,
            sessionFont = 18.sp,
            detailFont = 14.sp,
            captionFont = 16.sp,
            rowTitleFont = 20.sp,
            buttonHeight = 64.dp,
            buttonTextFont = 18.sp,
            rowVerticalPadding = 12.dp,
            spinnerSize = 24.dp,
        )
    } else {
        DashboardMetrics(
            horizontalPadding = 20.dp,
            coinSize = 220.dp,
            titleFont = TextUnit.Unspecified,
            statusFont = TextUnit.Unspecified,
            sessionFont = TextUnit.Unspecified,
            detailFont = 12.sp,
            captionFont = 12.sp,
            rowTitleFont = TextUnit.Unspecified,
            buttonHeight = 56.dp,
            buttonTextFont = 15.sp,
            rowVerticalPadding = 8.dp,
            spinnerSize = 20.dp,
        )
    }
}

/** Подпись секции меню: мелкий приглушённый текст, слева. */
@Composable
private fun SectionCaption(text: String, fontSize: TextUnit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text,
            fontSize = fontSize,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Кнопка премиальной сетки: одинаковая высота, одна строка без переносов,
 * равная ширина в ряду (через weight). Дефект среза 2 — кнопки были разной
 * высоты и «Проверить пинг» переносился на три строки.
 */
@Composable
private fun RowScope.DashboardActionButton(
    text: String,
    height: Dp,
    fontSize: TextUnit,
    onClick: () -> Unit,
) {
    OutlinedButton(
        modifier = Modifier.weight(1f).height(height),
        onClick = onClick,
    ) {
        Text(
            text,
            maxLines = 1,
            softWrap = false,
            fontSize = fontSize,
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
    rowTitleFont: TextUnit,
    detailFont: TextUnit,
    spinnerSize: Dp,
    verticalPadding: Dp,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy, onClick = onClick)
            .padding(vertical = verticalPadding),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(tag, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = rowTitleFont)
            Text(
                detail,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = detailFont,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            busy -> CircularProgressIndicator(modifier = Modifier.size(spinnerSize), strokeWidth = 2.dp)
            selected -> Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
