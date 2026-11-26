package com.maestrovpn.client.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.maestrovpn.client.R
import com.maestrovpn.client.core.engine.EngineManager
import com.maestrovpn.client.core.subscription.ManualServer
import com.maestrovpn.client.core.subscription.ManualServerStore
import com.maestrovpn.client.core.subscription.SubscriptionManager
import com.maestrovpn.client.ui.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Список ручных серверов (срез 5, issue #1): выбор активного (галочка, как
// у подписок в срезе 3), редактирование, удаление. Выбор сервера — тот же путь,
// что у подписок: buildConfigFor -> prepareServer (no-op у Xray)
// -> EngineManager.setConfig. Монета и кнопки Telegram не тронуты.
@Composable
fun ServersScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ManualServerStore(context, EngineManager.engine) }
    val subManager = remember { SubscriptionManager(context, EngineManager.engine) }
    var servers by remember { mutableStateOf(store.list()) }
    var activeTag by remember { mutableStateOf(store.loadSelection()?.tag) }
    var message by remember { mutableStateOf<String?>(null) }
    var selectingTag by remember { mutableStateOf<String?>(null) }

    fun selectServer(server: ManualServer) {
        selectingTag = server.tag
        scope.launch {
            val built = withContext(Dispatchers.IO) { store.buildConfigFor(server) }
            val configJson = built.getOrNull()
            if (configJson == null) {
                selectingTag = null
                message = context.getString(
                    R.string.server_build_failed,
                    built.exceptionOrNull()?.message ?: "?",
                )
                return@launch
            }
            val prepared = withContext(Dispatchers.IO) {
                EngineManager.engine.prepareServer(server.tag, configJson)
            }
            selectingTag = null
            if (!prepared) {
                message = context.getString(R.string.server_prepare_failed, server.tag)
                return@launch
            }
            EngineManager.setConfig(configJson)
            withContext(Dispatchers.IO) {
                store.saveSelection(server.id, server.tag)
                // Один активный сервер на всё приложение: выбор ручного
                // сервера снимает выбор подписки (и наоборот — см. Dashboard).
                subManager.clearSelection()
            }
            activeTag = server.tag
            message = context.getString(R.string.server_selected, server.tag)
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Text(stringResource(R.string.servers_title))
        Spacer(Modifier.height(12.dp))
        Button(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            onClick = { navController.navigate(Routes.serverEditorRoute(null, "vless")) },
        ) {
            Text(stringResource(R.string.server_add), maxLines = 1, softWrap = false)
        }
        message?.let {
            Spacer(Modifier.height(12.dp))
            Text(it)
        }
        Spacer(Modifier.height(12.dp))
        if (servers.isEmpty()) {
            Text(stringResource(R.string.servers_empty))
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(servers, key = { it.id }) { server ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = selectingTag == null) { selectServer(server) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(server.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${server.protocol} · ${server.address}:${server.port}" +
                                        if (server.network == "xhttp") " · xhttp" else "",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (selectingTag == server.tag) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                )
                            } else if (activeTag == server.tag) {
                                Icon(
                                    Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(onClick = {
                                navController.navigate(Routes.serverEditorRoute(server.id, null))
                            }) {
                                Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.server_edit))
                            }
                            IconButton(onClick = {
                                val wasActive = activeTag == server.tag
                                store.delete(server)
                                servers = store.list()
                                if (wasActive) {
                                    activeTag = null
                                    EngineManager.setConfig(null)
                                }
                                message = context.getString(R.string.server_deleted, server.name)
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.server_delete))
                            }
                        }
                    }
                }
            }
        }
    }
}

// Редактор сервера (срез 5, issue #1): формы по протоколам.
// Секции показываются только для своего протокола/транспорта, чтобы форма
// не превращалась в свалку: например, XHTTP Extra и FinalMask — только при
// network == xhttp, Reality-поля — только при security == reality.
@Composable
fun ServerEditorScreen(navController: NavController, serverId: String?, protocolArg: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ManualServerStore(context, EngineManager.engine) }
    var server by remember {
        mutableStateOf(
            serverId?.takeIf { it.isNotEmpty() }?.let { store.get(it) }
                ?: ManualServer(protocol = protocolArg?.takeIf { it in ManualServer.PROTOCOLS } ?: "vless"),
        )
    }
    var errors by remember { mutableStateOf<List<String>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val isNew = serverId.isNullOrEmpty()

    fun update(mutator: (ManualServer) -> ManualServer) {
        server = mutator(server)
        errors = emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(if (isNew) stringResource(R.string.server_add) else stringResource(R.string.server_edit_title))
        SectionCaption(stringResource(R.string.server_protocol))
        OptionsRow(
            options = ManualServer.PROTOCOLS.map { it to it.uppercase() },
            selected = server.protocol,
            onSelect = { v -> update { it.copy(protocol = v) } },
        )
        EditorField(
            label = stringResource(R.string.server_name),
            value = server.name,
            onChange = { v -> update { it.copy(name = v) } },
        )
        EditorField(
            label = stringResource(R.string.server_address),
            value = server.address,
            onChange = { v -> update { it.copy(address = v) } },
        )
        EditorField(
            label = stringResource(R.string.server_port),
            value = server.port.toString(),
            onChange = { v ->
                v.filter { it.isDigit() }.toIntOrNull()?.let { p -> update { it.copy(port = p) } }
                    ?: update { it.copy(port = 0) }
            },
            keyboardType = KeyboardType.Number,
        )

        when (server.protocol) {
            "vless", "vmess" -> {
                EditorField(
                    label = "UUID",
                    value = server.uuid,
                    onChange = { v -> update { it.copy(uuid = v) } },
                    password = true,
                )
                if (server.protocol == "vless") {
                    SectionCaption("Flow")
                    OptionsRow(
                        options = ManualServer.FLOWS.map {
                            it to if (it.isEmpty()) "—" else it
                        },
                        selected = server.flow,
                        onSelect = { v -> update { it.copy(flow = v) } },
                    )
                }
                if (server.protocol == "vmess") {
                    SectionCaption(stringResource(R.string.server_vmess_security))
                    OptionsRow(
                        options = ManualServer.VMESS_SECURITIES.map { it to it },
                        selected = server.method.lowercase().ifBlank { "auto" },
                        onSelect = { v -> update { it.copy(method = v) } },
                    )
                    EditorField(
                        label = "AlterId",
                        value = server.alterId.toString(),
                        onChange = { v ->
                            v.filter { it.isDigit() }.toIntOrNull()
                                ?.let { p -> update { it.copy(alterId = p) } }
                        },
                        keyboardType = KeyboardType.Number,
                    )
                }
            }
            "trojan", "hysteria2" -> {
                EditorField(
                    label = stringResource(R.string.server_password),
                    value = server.password,
                    onChange = { v -> update { it.copy(password = v) } },
                    password = true,
                )
            }
            "shadowsocks" -> {
                SectionCaption(stringResource(R.string.server_ss_method))
                OptionsRow(
                    options = ManualServer.SS_METHODS.map { it to it },
                    selected = server.method,
                    onSelect = { v -> update { it.copy(method = v) } },
                )
                EditorField(
                    label = stringResource(R.string.server_password),
                    value = server.password,
                    onChange = { v -> update { it.copy(password = v) } },
                    password = true,
                )
            }
            "socks" -> {
                EditorField(
                    label = stringResource(R.string.server_username_optional),
                    value = server.username,
                    onChange = { v -> update { it.copy(username = v) } },
                )
                EditorField(
                    label = stringResource(R.string.server_password_optional),
                    value = server.password,
                    onChange = { v -> update { it.copy(password = v) } },
                    password = true,
                )
            }
            "wireguard" -> {
                EditorField(
                    label = stringResource(R.string.server_wg_public_key),
                    value = server.publicKey,
                    onChange = { v -> update { it.copy(publicKey = v) } },
                    password = true,
                )
                EditorField(
                    label = stringResource(R.string.server_wg_private_key),
                    value = server.privateKey,
                    onChange = { v -> update { it.copy(privateKey = v) } },
                    password = true,
                )
                EditorField(
                    label = stringResource(R.string.server_wg_preshared_key),
                    value = server.preSharedKey,
                    onChange = { v -> update { it.copy(preSharedKey = v) } },
                    password = true,
                )
                EditorField(
                    label = stringResource(R.string.server_wg_local_address),
                    value = server.localAddress,
                    onChange = { v -> update { it.copy(localAddress = v) } },
                )
                EditorField(
                    label = stringResource(R.string.server_wg_allowed_ips),
                    value = server.allowedIps,
                    onChange = { v -> update { it.copy(allowedIps = v) } },
                )
                EditorField(
                    label = "MTU",
                    value = server.mtu.toString(),
                    onChange = { v ->
                        v.filter { it.isDigit() }.toIntOrNull()
                            ?.let { p -> update { it.copy(mtu = p) } }
                    },
                    keyboardType = KeyboardType.Number,
                )
                EditorField(
                    label = stringResource(R.string.server_wg_reserved),
                    value = server.reserved,
                    onChange = { v -> update { it.copy(reserved = v) } },
                )
            }
            "hysteria2" -> {
                EditorField(
                    label = stringResource(R.string.server_hy2_up),
                    value = server.upMbps,
                    onChange = { v -> update { it.copy(upMbps = v) } },
                )
                EditorField(
                    label = stringResource(R.string.server_hy2_down),
                    value = server.downMbps,
                    onChange = { v -> update { it.copy(downMbps = v) } },
                )
                EditorField(
                    label = stringResource(R.string.server_hy2_obfs),
                    value = server.obfsPassword,
                    onChange = { v -> update { it.copy(obfsPassword = v) } },
                    password = true,
                )
                EditorField(
                    label = stringResource(R.string.server_hy2_hop_ports),
                    value = server.hopPorts,
                    onChange = { v -> update { it.copy(hopPorts = v) } },
                )
                EditorField(
                    label = stringResource(R.string.server_hy2_hop_interval),
                    value = server.hopInterval,
                    onChange = { v -> update { it.copy(hopInterval = v) } },
                )
                EditorField(
                    label = stringResource(R.string.server_hy2_udp_timeout),
                    value = server.udpIdleTimeout,
                    onChange = { v -> update { it.copy(udpIdleTimeout = v) } },
                )
                Text(
                    stringResource(R.string.server_hy2_note),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Транспорт — только VLESS/VMess/Trojan (у остальных транспорт фиксирован).
        if (server.protocol in setOf("vless", "vmess", "trojan")) {
            SectionCaption(stringResource(R.string.server_transport))
            OptionsRow(
                options = ManualServer.NETWORKS.map { it to it.uppercase() },
                selected = server.network,
                onSelect = { v -> update { it.copy(network = v) } },
            )
            if (server.network in setOf("ws", "grpc", "httpupgrade", "xhttp")) {
                EditorField(
                    label = stringResource(R.string.server_path),
                    value = server.path,
                    onChange = { v -> update { it.copy(path = v) } },
                )
                EditorField(
                    label = "Host",
                    value = server.host,
                    onChange = { v -> update { it.copy(host = v) } },
                )
            }
            if (server.network == "grpc") {
                EditorField(
                    label = "ServiceName",
                    value = server.serviceName,
                    onChange = { v -> update { it.copy(serviceName = v) } },
                )
                SectionCaption("gRPC mode")
                OptionsRow(
                    options = ManualServer.GRPC_MODES.map { it to it },
                    selected = server.grpcMode,
                    onSelect = { v -> update { it.copy(grpcMode = v) } },
                )
            }
            if (server.network == "xhttp") {
                SectionCaption("XHTTP mode")
                OptionsRow(
                    options = ManualServer.XHTTP_MODES.map { it to it },
                    selected = server.xhttpMode,
                    onSelect = { v -> update { it.copy(xhttpMode = v) } },
                )
                EditorField(
                    label = "XHTTP Extra (JSON)",
                    value = server.xhttpExtra,
                    onChange = { v -> update { it.copy(xhttpExtra = v) } },
                )
                EditorField(
                    label = "FinalMask (JSON)",
                    value = server.finalMask,
                    onChange = { v -> update { it.copy(finalMask = v) } },
                )
                Text(
                    stringResource(R.string.server_xhttp_note),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        // Безопасность потока — VLESS/VMess/Trojan; у Hysteria2 свой TLS-блок ниже.
        if (server.protocol in setOf("vless", "vmess", "trojan")) {
            SectionCaption(stringResource(R.string.server_security))
            OptionsRow(
                options = ManualServer.SECURITIES.map { it to it },
                selected = server.security,
                onSelect = { v -> update { it.copy(security = v) } },
            )
            if (server.security != "none") {
                EditorField(
                    label = "SNI",
                    value = server.sni,
                    onChange = { v -> update { it.copy(sni = v) } },
                )
                EditorField(
                    label = "ALPN",
                    value = server.alpn,
                    onChange = { v -> update { it.copy(alpn = v) } },
                )
                SectionCaption(stringResource(R.string.server_fingerprint))
                OptionsRow(
                    options = ManualServer.FINGERPRINTS.map {
                        it to if (it.isEmpty()) "—" else it
                    },
                    selected = server.fingerprint,
                    onSelect = { v -> update { it.copy(fingerprint = v) } },
                )
                CheckRow(
                    label = stringResource(R.string.server_allow_insecure),
                    checked = server.allowInsecure,
                    onChange = { v -> update { it.copy(allowInsecure = v) } },
                )
            }
            if (server.security == "reality") {
                EditorField(
                    label = "PublicKey (pbk)",
                    value = server.realityPublicKey,
                    onChange = { v -> update { it.copy(realityPublicKey = v) } },
                    password = true,
                )
                EditorField(
                    label = "ShortId (sid)",
                    value = server.realityShortId,
                    onChange = { v -> update { it.copy(realityShortId = v) } },
                )
            }
        }
        if (server.protocol == "hysteria2") {
            SectionCaption(stringResource(R.string.server_security))
            EditorField(
                label = "SNI",
                value = server.sni,
                onChange = { v -> update { it.copy(sni = v) } },
            )
            CheckRow(
                label = stringResource(R.string.server_allow_insecure),
                checked = server.allowInsecure,
                onChange = { v -> update { it.copy(allowInsecure = v) } },
            )
        }

        // Mux — всем outbound'ам, кроме WireGuard (у endpoint'а mux нет).
        if (server.protocol != "wireguard") {
            SectionCaption("Mux")
            CheckRow(
                label = stringResource(R.string.server_mux_enable),
                checked = server.muxEnabled,
                onChange = { v -> update { it.copy(muxEnabled = v) } },
            )
            if (server.muxEnabled) {
                EditorField(
                    label = stringResource(R.string.server_mux_concurrency),
                    value = server.muxConcurrency.toString(),
                    onChange = { v ->
                        v.filter { it.isDigit() }.toIntOrNull()
                            ?.let { p -> update { it.copy(muxConcurrency = p) } }
                    },
                    keyboardType = KeyboardType.Number,
                )
            }
        }

        if (errors.isNotEmpty()) {
            errors.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Button(
            modifier = Modifier.fillMaxWidth().height(56.dp),
            enabled = !busy,
            onClick = {
                val validation = server.validate()
                if (validation.isNotEmpty()) {
                    errors = validation
                    return@Button
                }
                busy = true
                scope.launch {
                    val error = withContext(Dispatchers.IO) { store.save(server) }
                    busy = false
                    if (error == null) {
                        navController.popBackStack()
                    } else {
                        errors = listOf(error)
                    }
                }
            },
        ) {
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                Text(stringResource(R.string.server_save), maxLines = 1, softWrap = false)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Подпись секции формы: мелкий приглушённый текст, слева (как в Dashboard). */
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

@Composable
private fun EditorField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
    )
}

@Composable
private fun OptionsRow(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { (value, label) ->
            FilterChip(
                selected = selected == value,
                onClick = { onSelect(value) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(label)
    }
}
