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

// Единая точка управления VPN (срез 2). Движок выбирается так:
// 1) SingBoxEngine из src/libbox/java (через reflection, чтобы основной код
//    компилировался без libbox.aar) — реальный sing-box + BoxService;
// 2) иначе StubEngine.
// Статус зеркалится из службы через AIDL-биндинг (ServiceConnection): бинд
// открывается на старте и закрывается на стопе; постоянного бинда из Application
// нет (bindService с BIND_AUTO_CREATE создавал бы службу навсегда).
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
        engine = loadEngine()
        engine.init(app)
    }

    private fun loadEngine(): VpnEngine {
        return try {
            val clazz = Class.forName("com.maestrovpn.client.core.engine.SingBoxEngine")
            clazz.getDeclaredConstructor().newInstance() as VpnEngine
        } catch (_: Exception) {
            StubEngine()
        }
    }

    /** Есть ли выбранный сервер/конфиг для подключения (конфиг хранится здесь). */
    @Volatile
    var currentConfigJson: String? = null

    fun setConfig(configJson: String?) {
        currentConfigJson = configJson
    }

    /**
     * Intent системного диалога разрешения VPN или null, если разрешение уже есть.
     * Диалог запускает MainActivity через ActivityResultLauncher; при RESULT_OK
     * она вызывает [startService].
     */
    fun prepareIntent(context: Context): Intent? = VpnService.prepare(context)

    fun startService() {
        val config = currentConfigJson
        if (config.isNullOrBlank()) {
            _status.value = EngineStatus.ERROR
            _statusMessage.value = "Сначала выберите сервер"
            return
        }
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
