package com.maestrovpn.client.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.maestrovpn.client.R
import com.maestrovpn.client.core.transfer.ScannedPayload
import com.maestrovpn.client.ui.navigation.Routes
import com.maestrovpn.client.ui.qr.QrAnalyzer
import java.util.concurrent.Executors

// QR-сканер (issue #6): камера ищет QR-код и разбирает его:
//   maestro-pair://receive?… → экран отправки подписки на ТВ (приёмник из QR);
//   http(s)://…              → добавление подписки;
//   vless/vmess/ss/trojan/…  → ссылка на сервер в буфер обмена;
//   иное                     → причина и повторное сканирование.
// Камера — CameraX, декод — zxing (без Google Play Services).
@Composable
fun QrScannerScreen(navController: NavController) {
    val context = LocalContext.current
    val hasCamera = remember {
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var requested by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val analyzer = remember { QrAnalyzer() }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
        if (!allowed) message = context.getString(R.string.scanner_permission)
    }

    LaunchedEffect(Unit) {
        if (hasCamera && !granted && !requested) {
            requested = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            executor.shutdown()
            runCatching { cameraProvider?.unbindAll() }
        }
    }

    LaunchedEffect(scanned) {
        val raw = scanned ?: return@LaunchedEffect
        when (val payload = ScannedPayload.classify(raw)) {
            is ScannedPayload.Pairing -> navController.navigate(
                Routes.TRANSFER_SEND +
                    "?host=" + Uri.encode(payload.link.host) +
                    "&port=" + payload.link.port +
                    "&token=" + Uri.encode(payload.link.token),
            )
            is ScannedPayload.Subscription -> navController.navigate(
                Routes.SUBSCRIPTION_ADD + "?url=" + Uri.encode(payload.url),
            )
            is ScannedPayload.ServerLink -> {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("maestro", raw))
                message = context.getString(R.string.scanner_server_link)
                scanned = null
                analyzer.resume()
            }
            is ScannedPayload.Unknown -> {
                message = context.getString(R.string.scanner_unknown, payload.reason)
                scanned = null
                analyzer.resume()
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(stringResource(R.string.scanner_title), fontSize = 22.sp)

        if (!hasCamera) {
            Text(stringResource(R.string.scanner_no_camera), fontSize = 15.sp, textAlign = TextAlign.Center)
        } else if (!granted) {
            Text(stringResource(R.string.scanner_permission), fontSize = 15.sp, textAlign = TextAlign.Center)
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text(stringResource(R.string.scanner_permission_button))
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxWidth().weight(1f),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        runCatching {
                            val provider = future.get()
                            cameraProvider = provider
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val analysis = ImageAnalysis.Builder()
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()
                            analysis.setAnalyzer(executor, analyzer)
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis,
                            )
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
            )
            Text(stringResource(R.string.scanner_hint), fontSize = 14.sp, textAlign = TextAlign.Center)
        }

        message?.let { Text(it, fontSize = 15.sp, textAlign = TextAlign.Center) }

        OutlinedButton(
            onClick = {
                message = null
                scanned = null
                analyzer.resume()
            },
        ) {
            Text(stringResource(R.string.scanner_rescan))
        }
        OutlinedButton(onClick = { navController.popBackStack() }) {
            Text(stringResource(R.string.done))
        }
    }
}
