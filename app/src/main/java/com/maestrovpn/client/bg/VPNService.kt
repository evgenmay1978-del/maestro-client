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
import com.maestrovpn.client.core.engine.VpnLog
import com.maestrovpn.client.core.engine.TunFdContract
import com.maestrovpn.client.core.engine.TunnelProcessRegistry
import com.maestrovpn.client.core.engine.TunnelTeardown
import com.maestrovpn.client.core.engine.XrayDiagnostics
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
 *    переменной xray.tun.fd (TunFdContract) — ProcessBuilder так не умеет
 *    (закрывает fd >= 3), а расхождение имени переменной стоит неработающего
 *    старта: ядро тогда создаёт tun само и падает на netlink.
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
        private const val STATS_INTERVAL_MS = 5_000L

        /** Пауза после уборки: broadcast статуса должен успеть дойти до UI. */
        private const val STOP_SETTLE_MS = 300L

        /** Сколько ждём поток-монитор (waitpid) после сигналов уборки. */
        private const val MONITOR_JOIN_MS = 700L
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

    @Volatile
    private var stopWorker: Thread? = null

    /**
     * Синхронная уборка ядра: сигналы -> живость -> wake lock.
     * Лямбды — чтобы [TunnelTeardown] оставался чистым Kotlin и проверялся
     * JVM-тестом без нативного кода.
     */
    private val teardown: TunnelTeardown by lazy {
        TunnelTeardown(
            signal = { pid, sig -> XrayNative.nativeSignal(pid, sig) },
            isAlive = { pid -> XrayNative.nativeIsAlive(pid) },
            releaseResources = { releaseWakeLock() },
            log = { message -> VpnLog.i(message) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        current = this
        VpnLog.i("VPNService onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            return Service.START_NOT_STICKY
        }
        if (starting.get() || status.value == Status.Started) return Service.START_STICKY
        val configPath = intent?.getStringExtra(EXTRA_CONFIG_PATH)
            ?: File(filesDir, XrayEngine.CONFIG_FILE_NAME).absolutePath
        VpnLog.i("VPNService onStartCommand startId=$startId config=$configPath")
        try {
            startForeground(
                XrayNotification.NOTIFICATION_ID,
                XrayNotification.starting(this, VPNService::class.java),
            )
        } catch (e: Exception) {
            // Без foreground-уведомления служба жить не может; молча висело бы
            // «Подключение…» — поэтому причина уходит и в logcat, и в экран.
            VpnLog.e("startForeground failed", e)
            fail("Не удалось вывести службу на передний план: ${e.message}")
            return Service.START_NOT_STICKY
        }
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

            VpnLog.i("tun: address=$host/$prefix mtu=$mtu dns=$DNS_SERVERS")
            tunPfd = builder.establish()
                ?: throw IllegalStateException(
                    "VPN не разрешён или отозван (возможно, включён другой VPN-клиент)",
                )
            rawFd = tunPfd.detachFd()
            VpnLog.i("tun: established " + TunFdContract.describe(rawFd))

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
            TunnelProcessRegistry.register(spawnPid, "vpn")
            VpnLog.i("ядро запущено pid=$spawnPid")

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
            VpnLog.i("туннель поднят, статус Started")
            scope.launch { statsLoop() }
        } catch (e: Exception) {
            fail(e.message ?: e.javaClass.simpleName, e)
        } finally {
            starting.set(false)
        }
    }

    /**
     * Единая точка отказа старта: причина уходит в logcat, в экран (alert)
     * и статус немедленно становится Stopped — иначе интерфейс навсегда
     * остаётся в «Подключение…» и кнопка отключения ничего не делает.
     */
    private fun fail(message: String, error: Throwable? = null) {
        VpnLog.e("не удалось поднять туннель: $message", error)
        writeFailureEvidence("старт туннеля", message)
        binder.broadcast { it.onServiceAlert(Alert.CreateService.ordinal, message) }
        stopTunnel()
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
        // Процесс уже мёртв: снимаем запись и отпускаем wake lock сразу,
        // не полагаясь на onDestroy (он может не успеть).
        TunnelProcessRegistry.clear(xrayPid)
        releaseWakeLock()
        if (stopping.get()) return
        val engine = XrayEngine()
        val detail = readLogTail(engine.logFile())
        writeFailureEvidence("ядро завершилось (код $exitCode)", detail)
        val message = "Ядро Xray неожиданно завершилось (код $exitCode)" +
            (detail.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")
        binder.broadcast { it.onServiceAlert(Alert.CreateService.ordinal, message) }
        status.postValue(Status.Stopped)
        stopSelf()
    }

    /**
     * Идемпотентная остановка. Уборка идёт в собственном потоке, а не в
     * scope: onDestroy() отменяет scope, и корутина уборки не успевала даже
     * начаться — ядро переживало службу с tun fd (26.11.2026).
     */
    fun stopTunnel() {
        if (!stopping.compareAndSet(false, true)) return
        // Stopped выставляем ДО уборки и синхронно: если служба умрёт раньше,
        // чем колбэк дойдёт до приложения, экран останется в «Подключение…».
        // Действительно ли уборка удалась, проверяет EngineManager по
        // реестру процессов — ложное «Отключено» больше не проходит.
        status.postValue(Status.Stopped)
        VpnLog.i("VPNService stopTunnel")
        val worker = thread(name = "vpn-stop", isDaemon = true) {
            try {
                teardownBlocking()
                monitorThread?.join(MONITOR_JOIN_MS)
            } finally {
                runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
                // Пауза перед stopSelf: broadcast статуса идёт на Main и не
                // успевает уйти, если служба исчезнет сразу.
                runCatching { Thread.sleep(STOP_SETTLE_MS) }
                VpnLog.i("VPNService stopSelf")
                stopSelf()
            }
        }
        stopWorker = worker
    }

    /**
     * Синхронная уборка: сигналы -> проверка живости -> wake lock.
     * Не зависит ни от корутин, ни от того, жива ли ещё служба.
     */
    private fun teardownBlocking() {
        val pid = xrayPid
        teardown.run(pid)
        TunnelProcessRegistry.clear(pid)
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

    /**
     * Полный разбор сбоя: files/xray-failure.log (полная ошибка + конфиг с
     * замаскированными секретами + хвост журнала ядра) и logcat. Экран
     * показывает одну строку, но причина сбоя не теряется.
     */
    private fun writeFailureEvidence(stage: String, message: String) {
        val engine = XrayEngine()
        val config = runCatching { engine.configFile().readText() }.getOrNull()
        val tail = XrayDiagnostics.tail(engine.logFile())
        val file = XrayDiagnostics.writeFailure(filesDir, stage, message, config, tail)
        VpnLog.e("сбой (" + stage + ")" + (file?.let { ", отчёт: " + it.absolutePath } ?: ""))
        // logcat режет строку на 4000 символов — отправляем частями.
        message.chunked(3000).forEach { VpnLog.e("ошибка: " + it) }
        if (tail.isNotBlank()) tail.chunked(3000).forEach { VpnLog.e("xray.log: " + it) }
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
        VpnLog.i("VPNService onDestroy")
        if (current === this) current = null
        stopping.set(true)
        // Синхронная уборка ДО отмены scope: уборка внутри корутины не
        // стартовала после cancel, и ядро оставалось жить (26.11.2026).
        teardownBlocking()
        runCatching { stopWorker?.join(MONITOR_JOIN_MS) }
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        // Scope гасим только при реальном уничтожении службы. Раньше он
        // отменялся в stopTunnel(), а служба оставалась живой из-за
        // bindService(BIND_AUTO_CREATE) — и следующая попытка включить VPN
        // навсегда зависала в «Подключение…» (нашлось 26.11.2026).
        scope.cancel()
        binder.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent) ?: binder
    }
}
