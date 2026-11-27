package com.maestrovpn.client.core.engine

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.maestrovpn.client.bg.ProxyService
import com.maestrovpn.client.bg.ServiceConnection
import com.maestrovpn.client.bg.VPNService
import com.maestrovpn.client.constant.Alert
import com.maestrovpn.client.constant.Status
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

// Единая точка управления VPN (срез 2, переписана в срезе 7, issue #8).
// Единственный движок — XrayEngine (Xray-core); libbox сторонних ядер
// удалён полностью. Статус зеркалится из службы через AIDL-биндинг
// (ServiceConnection): бинд открывается на старте и закрывается на стопе;
// постоянного бинда из Application нет (bindService с BIND_AUTO_CREATE
// создавал бы службу навсегда).
//
// Конфиг живёт в двух местах: currentConfigJson — только в памяти процесса,
// и файл filesDir/xray-config.json — персистентная копия, которую пишет
// XrayEngine.start(). После перезагрузки процесса/устройства конфиг
// восстанавливается из файла (плитка, автозапуск) — Profile DB больше нет.
object EngineManager {
    private val _status = MutableStateFlow(EngineStatus.STOPPED)
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage: StateFlow<String?> = _statusMessage.asStateFlow()

    @Volatile
    var startedAtMillis: Long? = null
        private set

    lateinit var engine: VpnEngine
        private set

    private lateinit var appContext: Context
    private var serviceConnection: ServiceConnection? = null

    // Служба стартует асинхронно, а валидация конфига занимает секунды —
    // и то и другое не должно идти в UI-потоке (раньше шло).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startWatchdog: Job? = null
    private var stopWatchdog: Job? = null

    /** Сколько ждём ответа службы, прежде чем признать старт неудачным. */
    private const val START_TIMEOUT_MS = 25_000L

    /** Сколько даём синхронной уборке службы, прежде чем добивать снаружи. */
    private const val STOP_CONFIRM_MS = 1_500L

    /** Пауза после принудительной зачистки перед повторной проверкой. */
    private const val STOP_RETRY_MS = 700L

    fun init(app: Application) {
        appContext = app.applicationContext
        engine = XrayEngine()
        engine.init(app)
    }

    /** Есть ли выбранный сервер/конфиг для подключения (конфиг хранится здесь). */
    @Volatile
    var currentConfigJson: String? = null

    fun setConfig(configJson: String?) {
        currentConfigJson = configJson
    }

    /** Служба для текущего конфига: VPNService (tun inbound) или ProxyService. */
    fun serviceClass(): Class<*> {
        val xray = engine as? XrayEngine ?: return com.maestrovpn.client.bg.VPNService::class.java
        return xray.serviceClass(resolveConfig())
    }

    /**
     * Intent системного диалога разрешения VPN или null, если разрешение уже есть.
     * Диалог запускает MainActivity через ActivityResultLauncher; при RESULT_OK
     * она вызывает [startService].
     */
    fun prepareIntent(context: Context): Intent? = VpnService.prepare(context)

    fun startService() {
        val config = resolveConfig()
        if (config.isNullOrBlank()) {
            _status.value = EngineStatus.ERROR
            _statusMessage.value = "Сначала выберите сервер"
            return
        }
        if (_status.value == EngineStatus.STARTING) return
        currentConfigJson = config
        _status.value = EngineStatus.STARTING
        _statusMessage.value = null
        armWatchdog()
        scope.launch {
            val ok = try {
                engine.start(config)
            } catch (e: Exception) {
                _statusMessage.value = e.message
                false
            }
            if (ok) {
                connectService()
            } else {
                startWatchdog?.cancel()
                _status.value = EngineStatus.ERROR
                _statusMessage.value = engine.lastError ?: "Не удалось запустить движок"
            }
        }
    }

    /**
     * Остановка туннеля со сторожем подтверждения.
     *
     * Раньше исключение из engine.stop() глоталось, а Stopped выставлялся
     * сразу — и живое ядро с tun fd выглядело как «Отключено» (26.11.2026).
     * Теперь статус STOPPING, а STOPPED появляется только после проверки:
     * служба мертва и процесс ядра не жив. Не подтвердилось — принудительная
     * зачистка, затем либо STOPPED, либо честный ERROR с причиной.
     */
    fun stopService() {
        startWatchdog?.cancel()
        val previous = _statusMessage.value
        if (_status.value != EngineStatus.STOPPING) {
            _statusMessage.value = null
            _status.value = EngineStatus.STOPPING
        }
        try {
            engine.stop()
        } catch (e: Exception) {
            VpnLog.e("engine.stop() не удался", e)
            _statusMessage.value = "Ошибка остановки: " + (e.message ?: e.javaClass.simpleName) +
                (previous?.let { " (было: " + it + ")" } ?: "")
        }
        armStopWatchdog()
    }

    /** Сторож остановки: подтверждение, при провале — принудительная зачистка. */
    private fun armStopWatchdog() {
        stopWatchdog?.cancel()
        stopWatchdog = scope.launch {
            delay(STOP_CONFIRM_MS)
            if (isSomethingAlive()) {
                VpnLog.e("остановка не подтвердилась за " + STOP_CONFIRM_MS + " мс — добиваю принудительно")
                forceStopServices()
                delay(STOP_RETRY_MS)
            }
            if (isSomethingAlive()) {
                VpnLog.e("ядро всё ещё живо после принудительной зачистки")
                _statusMessage.value = "Не удалось остановить ядро: процесс остался жив"
                _status.value = EngineStatus.ERROR
                return@launch
            }
            confirmStopped()
        }
    }

    /** Что-то из двух: объект службы или процесс ядра. */
    private fun isSomethingAlive(): Boolean =
        VPNService.current != null || ProxyService.current != null || TunnelProcessRegistry.isAlive()

    /** Аварийный путь: синхронная уборка службы, SIGKILL ядру, stopService. */
    private fun forceStopServices() {
        runCatching { VPNService.current?.stopTunnel() }
            .onFailure { VpnLog.e("принудительная уборка VPNService упала", it) }
        runCatching { ProxyService.current?.stopTunnel() }
            .onFailure { VpnLog.e("принудительная уборка ProxyService упала", it) }
        if (TunnelProcessRegistry.isAlive()) {
            val pid = TunnelProcessRegistry.currentPid()
            VpnLog.e("добиваю процесс ядра pid=" + pid + " напрямую")
            if (TunnelProcessRegistry.killNow()) {
                VpnLog.i("процесс ядра pid=" + pid + " убит")
            }
        }
        runCatching { appContext.stopService(Intent(appContext, VPNService::class.java)) }
        runCatching { appContext.stopService(Intent(appContext, ProxyService::class.java)) }
    }

    /** Подтверждённая остановка: единственное место, где выставляется STOPPED. */
    private fun confirmStopped() {
        startedAtMillis = null
        disconnectService()
        _statusMessage.value = null
        _status.value = EngineStatus.STOPPED
        stopWatchdog = null
    }

    /**
     * Страховка от «вечного Подключение…»: если за 25 секунд служба не
     * отчиталась ни Started, ни ошибкой — показываем причину и гасим её.
     * Без этого 26.11.2026 экран висел бесконечно и отключить было нечем.
     */
    private fun armWatchdog() {
        startWatchdog?.cancel()
        startWatchdog = scope.launch {
            delay(START_TIMEOUT_MS)
            if (_status.value != EngineStatus.STARTING) return@launch
            _statusMessage.value =
                "Служба VPN не ответила за ${START_TIMEOUT_MS / 1000} с — попробуйте ещё раз"
            _status.value = EngineStatus.ERROR
            try {
                engine.stop()
            } catch (_: Exception) {
            }
            disconnectService()
        }
    }

    /** Конфиг из памяти или, если процесс перезапустился, из файла. */
    private fun resolveConfig(): String? {
        currentConfigJson?.takeIf { it.isNotBlank() }?.let { return it }
        val file = File(appContext.filesDir, XrayEngine.CONFIG_FILE_NAME)
        return runCatching { file.readText() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private fun connectService() {
        if (serviceConnection != null) return
        val connection = ServiceConnection(appContext, object : ServiceConnection.Callback {
            override fun onServiceStatusChanged(status: Status) {
                when (status) {
                    Status.Stopped -> {
                        startWatchdog?.cancel()
                        // Причину ошибки старта не затираем «Отключено»:
                        // экран должен показать, почему не поднялось.
                        if (_status.value != EngineStatus.ERROR) confirmStopped()
                    }
                    // Пока идёт остановка, поздние Starting/Started от службы
                    // не должны возвращать экран в «Подключено».
                    Status.Starting -> if (_status.value != EngineStatus.STOPPING) {
                        _status.value = EngineStatus.STARTING
                    }
                    Status.Started -> {
                        startWatchdog?.cancel()
                        if (_status.value == EngineStatus.STOPPING) return
                        if (startedAtMillis == null) startedAtMillis = System.currentTimeMillis()
                        _status.value = EngineStatus.RUNNING
                        _statusMessage.value = null
                    }
                    // Stopping больше не показываем как «Подключение…»:
                    // именно это превращало неудачный старт в вечный спиннер.
                    Status.Stopping -> _status.value = EngineStatus.STOPPED
                }
            }

            override fun onServiceAlert(type: Alert, message: String?) {
                _status.value = EngineStatus.ERROR
                _statusMessage.value = message ?: type.name
            }
        })
        serviceConnection = connection
        runCatching { connection.connect() }
    }

    private fun disconnectService() {
        val connection = serviceConnection ?: return
        serviceConnection = null
        runCatching { connection.disconnect() }
    }
}
