package com.maestrovpn.client.bg

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import androidx.lifecycle.MutableLiveData
import com.maestrovpn.client.App
import com.maestrovpn.client.constant.Alert
import com.maestrovpn.client.constant.Status
import com.maestrovpn.client.core.engine.XrayEngine
import com.maestrovpn.client.core.engine.XrayNative
import com.maestrovpn.client.database.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * VPN-режим: TUN-интерфейс + Xray дочерним процессом (срез 7, issue #8).
 *
 * Поток запуска:
 * 1. VpnService.Builder строит tun (адрес/MTU — из tun inbound конфига,
 *    запасные значения — константы ниже; MTU 1280: на мобильном пути 1500
 *    без MSS-clamping молча роняет трафик, измерено в Maestro 17.11.2026).
 * 2. Нативный спавнер (XrayNative) делает fork/exec и передаёт tun fd
 *    через XRAY_TUN_FD — ProcessBuilder так не умеет (закрывает fd >= 3).
 * 3. Спавн вызывается из выделенного потока-монитора: он же блокируется
 *    в waitpid, а PR_SET_PDEATHSIG в спавнере убивает ядро, если процесс
 *    приложения умрёт раньше (иначе остался бы сирота с мёртвым tun).
 * 4. Собственный UID приложения всегда вне туннеля (PackagePolicy):
 *    подписки, панель и обновление APK должны работать при мёртвом туннеле.
 *
 * Готовность: процесс жив 5 секунд после спавна. Реальный статус — через
 * AIDL (ServiceBinder); статистика сессии — Stats API, итоги в уведомлении.
 */
class VPNService : VpnService() {

    companion object {
        const val ACTION_STOP = XrayNotification.ACTION_STOP
        const val EXTRA_CONFIG_PATH = "config_path"

        @Volatile
        var current: VPNService? = null
            private set

        // Запасные значения tun; источник правды — tun inbound конфига.
        const val TUN_ADDRESS = "172.19.0.1"
        const val TUN_PREFIX = 30
        const val TUN_MTU = 1280
        val DNS_SERVERS = listOf("1.1.1.1", "8.8.8.8")

        private const val READY_TIMEOUT_MS = 5_000L
        private const val STOP_GRACE_MS = 3_000L
        private const val STATS_INTERVAL_MS = 5_000L
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
            return Service.START_NOT_STICKY
        }
        if (starting.get() || status.value == Status.Started) return Service.START_STICKY
        val configPath = intent?.getStringExtra(EXTRA_CONFIG_PATH)
            ?: File(filesDir, XrayEngine.CONFIG_FILE_NAME).absolutePath
        startForeground(
            XrayNotification.NOTIFICATION_ID,
            XrayNotification.starting(this, VPNService::class.java),
        )
        status.postValue(Status.Starting)
        scope.launch { startTunnel(configPath) }
        return Service.START_STICKY
    }

    private fun startTunnel(configPath: String) {
        if (!starting.compareAndSet(false, true)) return
        stopping.set(false)
        var tunPfd: ParcelFileDescriptor? = null
        var rawFd = -1
        try {
            val config = JSONObject(File(configPath).readText())
            val tun = findTunInbound(config)
            val address = tun?.optJSONObject("settings")?.optString("address", TUN_ADDRESS)
                ?.takeIf { it.isNotBlank() } ?: TUN_ADDRESS
            val prefix = address.substringAfter("/", "").toIntOrNull() ?: TUN_PREFIX
            val host = address.substringBefore("/")
            val mtu = tun?.optJSONObject("settings")?.optInt("mtu", TUN_MTU)
                ?.takeIf { it > 0 } ?: TUN_MTU

            val builder = Builder()
                .setSession("Maestro Client")
                .setMtu(mtu)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                builder.setMetered(false)
            }
            if (Settings.allowBypass) builder.allowBypass()
            builder.addAddress(host, prefix)
            builder.addRoute("0.0.0.0", 0)
            DNS_SERVERS.forEach { builder.addDnsServer(it) }
            val policy = PackagePolicy.decide(
                packageName,
                Settings.perAppProxyEnabled,
                Settings.getEffectivePerAppProxyMode() == Settings.PER_APP_PROXY_INCLUDE,
                Settings.getEffectivePerAppProxyList(),
            )
            policy.include.forEach { pkg ->
                try {
                    builder.addAllowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }
            policy.exclude.forEach { pkg ->
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: PackageManager.NameNotFoundException) {
                }
            }

            tunPfd = builder.establish()
                ?: throw IllegalStateException("VPN не разрешён или отозван")
            rawFd = tunPfd.detachFd()

            val engine = XrayEngine()
            val binary = engine.binaryFile()
                ?: throw IllegalStateException("Xray binary not found")
            val logFile = engine.logFile()

            val pidLatch = CountDownLatch(1)
            var spawnPid = -1L
            monitorThread = thread(name = "xray-monitor", isDaemon = true) {
                // Спавн — только из этого потока: PR_SET_PDEATHSIG привязан
                // к потоку, который вызвал fork.
                spawnPid = XrayNative.nativeSpawn(rawFd, binary.absolutePath, configPath, logFile.absolutePath)
                pidLatch.countDown()
                if (spawnPid > 0) {
                    val exitCode = XrayNative.nativeWaitpid(spawnPid)
                    onChildExit(exitCode)
                }
            }
            if (!pidLatch.await(10, TimeUnit.SECONDS)) {
                throw IllegalStateException("Xray spawn timed out")
            }
            // Дочерний процесс уже сделал dup() fd — исходный можно закрыть.
            // Os.close берёт FileDescriptor, а не int: adoptFd забирает fd
            // во владение, close() закрывает его.
            try {
                ParcelFileDescriptor.adoptFd(rawFd).close()
            } catch (_: Exception) {
            }
            rawFd = -1
            if (spawnPid <= 0) {
                throw IllegalStateException(
                    "Не удалось запустить ядро: ${XrayNative.nativeLastError()}",
                )
            }
            xrayPid = spawnPid

            // Готовность: процесс должен прожить первые секунды. Если конфиг
            // валиден, а tun мёртв — ядро падает сразу, причину видно в логе.
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
            scope.launch { statsLoop() }
        } catch (e: Exception) {
            val message = e.message ?: e.javaClass.simpleName
            binder.broadcast { it.onServiceAlert(Alert.CreateService.ordinal, message) }
            stopTunnel()
        } finally {
            starting.set(false)
        }
    }

    private suspend fun statsLoop() {
        val engine = XrayEngine()
        while (status.value == Status.Started && !stopping.get()) {
            delay(STATS_INTERVAL_MS)
            val stats = engine.queryStats() ?: continue
            val notification = XrayNotification.running(
                this,
                VPNService::class.java,
                stats.first,
                stats.second,
            )
            runCatching {
                (getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager)
                    .notify(XrayNotification.NOTIFICATION_ID, notification)
            }
        }
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

    /** Идемпотентная остановка: SIGTERM, пауза, SIGKILL, снятие foreground. */
    fun stopTunnel() {
        if (!stopping.compareAndSet(false, true)) return
        status.postValue(Status.Stopping)
        scope.launch {
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
            status.postValue(Status.Stopped)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            scope.cancel()
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val power = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "maestro-client:tunnel",
        ).apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.release() }
        wakeLock = null
    }

    private fun findTunInbound(config: JSONObject): JSONObject? {
        val inbounds = config.optJSONArray("inbounds") ?: return null
        for (i in 0 until inbounds.length()) {
            val inbound = inbounds.optJSONObject(i) ?: continue
            if (inbound.optString("protocol") == "tun") return inbound
        }
        return null
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

    override fun onRevoke() {
        stopTunnel()
    }

    override fun onDestroy() {
        if (current === this) current = null
        stopTunnel()
        binder.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent) ?: binder
    }
}
