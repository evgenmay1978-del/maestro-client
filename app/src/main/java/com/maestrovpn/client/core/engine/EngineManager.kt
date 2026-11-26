package com.maestrovpn.client.core.engine

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.maestrovpn.client.bg.ServiceConnection
import com.maestrovpn.client.constant.Alert
import com.maestrovpn.client.constant.Status
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        currentConfigJson = config
        _status.value = EngineStatus.STARTING
        val ok = try {
            engine.start(config)
        } catch (e: Exception) {
            _statusMessage.value = e.message
            false
        }
        if (ok) {
            connectService()
        } else {
            _status.value = EngineStatus.ERROR
            _statusMessage.value = engine.lastError ?: "Не удалось запустить движок"
        }
    }

    fun stopService() {
        try {
            engine.stop()
        } catch (_: Exception) {
        }
        disconnectService()
        startedAtMillis = null
        _status.value = EngineStatus.STOPPED
        _statusMessage.value = null
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
                        startedAtMillis = null
                        _status.value = EngineStatus.STOPPED
                    }
                    Status.Starting -> _status.value = EngineStatus.STARTING
                    Status.Started -> {
                        if (startedAtMillis == null) startedAtMillis = System.currentTimeMillis()
                        _status.value = EngineStatus.RUNNING
                        _statusMessage.value = null
                    }
                    Status.Stopping -> _status.value = EngineStatus.STARTING
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
