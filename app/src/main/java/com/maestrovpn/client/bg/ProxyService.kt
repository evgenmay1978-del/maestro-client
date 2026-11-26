package com.maestrovpn.client.bg

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.lifecycle.MutableLiveData
import com.maestrovpn.client.constant.Alert
import com.maestrovpn.client.constant.Status
import com.maestrovpn.client.core.engine.VpnLog
import com.maestrovpn.client.core.engine.XrayEngine
import com.maestrovpn.client.core.engine.XrayNative
import com.maestrovpn.client.database.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Режим «только прокси» (срез 7, issue #8): Xray дочерним процессом без
 * VPN и без tun — SOCKS :10808 + HTTP :10809 на 127.0.0.1, как
 * xray_config.json. Остальное — как в [VPNService]: тот же
 * нативный спавнер, тот же поток-монитор (waitpid + PR_SET_PDEATHSIG),
 * та же статистика в уведомлении.
 */
class ProxyService : Service() {

    companion object {
        const val ACTION_STOP = XrayNotification.ACTION_STOP
        const val EXTRA_CONFIG_PATH = "config_path"

        @Volatile
        var current: ProxyService? = null
            private set

        private const val READY_TIMEOUT_MS = 5_000L
        private const val STOP_GRACE_MS = 3_000L
    }

    private val status = MutableLiveData(Status.Stopped)
    private val binder = ServiceBinder(status)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val starting = AtomicBoolean(false)
    private val stopping = AtomicBoolean(false)

    @Volatile
    private var xrayPid: Long = -1
    private var monitorThread: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        current = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            return START_NOT_STICKY
        }
        if (starting.get() || status.value == Status.Started) return START_STICKY
        val configPath = intent?.getStringExtra(EXTRA_CONFIG_PATH)
            ?: File(filesDir, XrayEngine.CONFIG_FILE_NAME).absolutePath
        VpnLog.i("ProxyService onStartCommand startId=$startId config=$configPath")
        try {
            startForeground(
                XrayNotification.NOTIFICATION_ID,
                XrayNotification.starting(this, ProxyService::class.java),
            )
        } catch (e: Exception) {
            VpnLog.e("ProxyService startForeground failed", e)
            fail("Не удалось вывести службу на передний план: ${e.message}")
            return START_NOT_STICKY
        }
        status.postValue(Status.Starting)
        scope.launch { startProxy(configPath) }
        return START_STICKY
    }

    private fun startProxy(configPath: String) {
        if (!starting.compareAndSet(false, true)) return
        stopping.set(false)
        try {
            val engine = XrayEngine()
            val binary = engine.binaryFile()
                ?: throw IllegalStateException("Xray binary not found")
            val logFile = engine.logFile()

            val pidLatch = CountDownLatch(1)
            var spawnPid = -1L
            monitorThread = thread(name = "xray-monitor", isDaemon = true) {
                spawnPid = XrayNative.nativeSpawn(-1, binary.absolutePath, configPath, logFile.absolutePath)
                pidLatch.countDown()
                if (spawnPid > 0) {
                    val exitCode = XrayNative.nativeWaitpid(spawnPid)
                    onChildExit(exitCode)
                }
            }
            if (!pidLatch.await(10, TimeUnit.SECONDS)) {
                throw IllegalStateException("Xray spawn timed out")
            }
            if (spawnPid <= 0) {
                throw IllegalStateException(
                    "Не удалось запустить ядро: ${XrayNative.nativeLastError()}",
                )
            }
            xrayPid = spawnPid

            val deadline = System.currentTimeMillis() + READY_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                if (!XrayNative.nativeIsAlive(spawnPid)) {
                    throw IllegalStateException("Ядро завершилось при старте: ${readLogTail(logFile)}")
                }
                Thread.sleep(200)
            }

            acquireWakeLock()
            Settings.startedByUser = true
            status.postValue(Status.Started)
            VpnLog.i("прокси-ядро поднято pid=$spawnPid")
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName, e)
        } finally {
            starting.set(false)
        }
    }

    private fun fail(message: String, error: Throwable? = null) {
        VpnLog.e("не удалось поднять прокси-ядро: $message", error)
        binder.broadcast { it.onServiceAlert(Alert.CreateService.ordinal, message) }
        stopTunnel()
    }

    private fun onChildExit(exitCode: Int) {
        if (stopping.get()) return
        val engine = XrayEngine()
        val detail = readLogTail(engine.logFile())
        val message = "Ядро Xray неожиданно завершилось (код $exitCode)" +
            (detail.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        binder.broadcast { it.onServiceAlert(Alert.CreateService.ordinal, message) }
        status.postValue(Status.Stopped)
        stopSelf()
    }

    fun stopTunnel() {
        if (!stopping.compareAndSet(false, true)) return
        status.postValue(Status.Stopped)
        VpnLog.i("ProxyService stopTunnel")
        scope.launch {
            try {
                val pid = xrayPid
                if (pid > 0) {
                    XrayNative.nativeSignal(pid, XrayNative.SIGTERM)
                    val deadline = System.currentTimeMillis() + STOP_GRACE_MS
                    while (System.currentTimeMillis() < deadline && XrayNative.nativeIsAlive(pid)) {
                        delay(100)
                    }
                    if (XrayNative.nativeIsAlive(pid)) {
                        XrayNative.nativeSignal(pid, XrayNative.SIGKILL)
                    }
                }
                monitorThread?.join(1_000)
                releaseWakeLock()
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                delay(300)
                VpnLog.i("ProxyService stopSelf")
                stopSelf()
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "maestro-client:proxy",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    private fun readLogTail(logFile: File): String {
        return try {
            if (!logFile.exists()) return ""
            val bytes = logFile.readBytes()
            val tail = bytes.takeLast(2048).toByteArray().toString(Charsets.UTF_8)
            tail.lineSequence().filter { it.isNotBlank() }.lastOrNull().orEmpty().take(300)
        } catch (_: Exception) {
            ""
        }
    }

    override fun onDestroy() {
        VpnLog.i("ProxyService onDestroy")
        if (current === this) current = null
        stopTunnel()
        scope.cancel()
        binder.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
