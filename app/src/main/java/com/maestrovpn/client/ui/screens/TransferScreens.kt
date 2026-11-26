package com.maestrovpn.client.ui.screens

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.maestrovpn.client.R
import com.maestrovpn.client.core.engine.EngineManager
import com.maestrovpn.client.core.subscription.ImportResult
import com.maestrovpn.client.core.subscription.SubscriptionInfo
import com.maestrovpn.client.core.subscription.SubscriptionManager
import com.maestrovpn.client.core.transfer.LocalAddresses
import com.maestrovpn.client.core.transfer.PairingLink
import com.maestrovpn.client.core.transfer.TransferClient
import com.maestrovpn.client.core.transfer.TransferProtocol
import com.maestrovpn.client.core.transfer.TransferReceipt
import com.maestrovpn.client.core.transfer.TransferServer
import com.maestrovpn.client.ui.qr.QrBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

// Перенос подписки с телефона на ТВ по локальной сети (issue #6).
// ТВ (приёмник) показывает QR со ссылкой maestro-pair://receive?host&port&token;
// телефон сканирует его и отправляет ссылку подписки POST-запросом.
// Приёмник: экран на ТВ — QR, адрес и статус приёма.
@Composable
fun TransferReceiveScreen(navController: NavController) {
    val context = LocalContext.current
    val manager = remember { SubscriptionManager(context, EngineManager.engine) }
    var started by remember { mutableStateOf<TransferServer.Started?>(null) }
    var host by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var openError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val main = Handler(Looper.getMainLooper())
        val server = TransferServer(
            onSubscription = { payload ->
                main.post { busy = true }
                val result = runBlocking { manager.importSubscription(payload.name, payload.url) }
                when (result) {
                    is ImportResult.Success -> {
                        main.post {
                            busy = false
                            status = if (payload.name.isBlank()) {
                                context.getString(R.string.receive_received_unnamed, result.servers)
                            } else {
                                context.getString(R.string.receive_received, payload.name, result.servers)
                            }
                        }
                        Result.success(result.servers)
                    }
                    is ImportResult.Failure -> {
                        main.post {
                            busy = false
                            status = context.getString(R.string.receive_failed, result.reason)
                        }
                        Result.failure(IllegalStateException(result.reason))
                    }
                }
            },
        )
        val localHost = LocalAddresses.ipv4().firstOrNull()
        server.start().fold(
            onSuccess = { value ->
                started = value
                host = localHost.orEmpty()
            },
            onFailure = { error ->
                openError = context.getString(R.string.receive_port_failed, error.message ?: "?")
            },
        )
        onDispose { server.close() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.receive_title), fontSize = 26.sp, textAlign = TextAlign.Center)
        Text(stringResource(R.string.receive_hint), fontSize = 16.sp, textAlign = TextAlign.Center)

        val error = openError
        val current = started
        if (error != null) {
            Text(error, fontSize = 16.sp, textAlign = TextAlign.Center)
        } else if (current == null) {
            CircularProgressIndicator()
        } else {
            val link = remember(host, current.port, current.token) {
                PairingLink(host, current.port, current.token)
            }
            if (host.isEmpty()) {
                Text(stringResource(R.string.receive_no_network), fontSize = 16.sp, textAlign = TextAlign.Center)
            } else {
                val bitmap = remember(link.toUri()) { QrBitmap.encode(link.toUri(), 640) }
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.receive_title),
                        modifier = Modifier.size(300.dp),
                    )
                }
                Text(
                    stringResource(R.string.receive_address, link.display()),
                    fontSize = 18.sp,
                    textAlign = TextAlign.Center,
                )
            }
            if (busy) {
                CircularProgressIndicator()
            } else {
                Text(status ?: stringResource(R.string.receive_waiting), fontSize = 16.sp, textAlign = TextAlign.Center)
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { navController.popBackStack() }) {
            Text(stringResource(R.string.done))
        }
    }
}

// Отправитель (телефон после сканирования QR приёмника): выбрать подписку и отправить.
@Composable
fun TransferSendScreen(
    navController: NavController,
    host: String,
    port: Int,
    token: String,
) {
    val context = LocalContext.current
    val manager = remember { SubscriptionManager(context, EngineManager.engine) }
    val subscriptions: List<SubscriptionInfo> = remember { manager.list() }
    val link = remember(host, port, token) { PairingLink(host, port, token) }
    var selectedId by remember { mutableStateOf(subscriptions.firstOrNull()?.id) }
    var sending by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val valid = PairingLink.isValidHost(host) &&
        PairingLink.isValidPort(port) &&
        PairingLink.isValidToken(token)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.send_title), fontSize = 22.sp)
        Text(stringResource(R.string.send_address, link.display()))

        if (!valid) {
            Text(stringResource(R.string.send_failed, "адрес приёма повреждён"), fontSize = 15.sp)
        } else if (subscriptions.isEmpty()) {
            Text(stringResource(R.string.send_empty), fontSize = 15.sp)
        } else {
            Text(stringResource(R.string.send_choose), fontSize = 15.sp)
            subscriptions.forEach { info ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedId = info.id }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = info.id == selectedId, onClick = { selectedId = info.id })
                    Column {
                        Text(info.name, fontSize = 16.sp)
                        Text(info.url, fontSize = 12.sp, maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Button(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !sending && selectedId != null,
                onClick = {
                    val info = subscriptions.firstOrNull { it.id == selectedId } ?: return@Button
                    scope.launch {
                        sending = true
                        status = context.getString(R.string.send_sending)
                        val receipt = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            TransferClient.send(
                                link,
                                TransferProtocol.SubscriptionPayload(info.url, info.name),
                            )
                        }
                        sending = false
                        status = when (receipt) {
                            is TransferReceipt.Ok -> receipt.servers?.let {
                                context.getString(R.string.send_ok, it)
                            } ?: context.getString(R.string.send_ok_unknown)
                            is TransferReceipt.Refused ->
                                context.getString(R.string.send_refused, receipt.reason)
                            is TransferReceipt.Failed ->
                                context.getString(R.string.send_failed, receipt.reason)
                        }
                    }
                },
            ) {
                Text(stringResource(R.string.send_button), fontSize = 17.sp)
            }
            status?.let { Text(it, fontSize = 15.sp) }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { navController.popBackStack() }) {
            Text(stringResource(R.string.done))
        }
    }
}
