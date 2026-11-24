package com.maestrovpn.client.core.engine

import android.app.Application

// Состояние движка VPN (срез 1).
enum class EngineStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
}

// Минимальный интерфейс движка: его реализуют StubEngine (срез 1) и
// SingBoxEngine (src/libbox/java, только когда собран libbox.aar).
// Интерфейс намеренно не зависит от io.nekohasekai.libbox, чтобы основной
// код компилировался без AAR.
//
// Срез 2: start() больше не принимает VpnService — туннель целиком внутри
// BoxService (она сама стартует VPNService/ProxyService и вызывает openTun);
// движку передаётся только конфиг.
interface VpnEngine {
    val version: String

    /** Инициализация ядра (Libbox.setup). По умолчанию — no-op (StubEngine). */
    fun init(app: Application) {}

    /** Быстрая проверка конфига до сохранения/запуска (validate-before-save). */
    fun validateConfig(configJson: String): Boolean

    /**
     * Запустить туннель с готовым конфигом. Возвращает false + [lastError],
     * если старт не удался.
     */
    fun start(configJson: String): Boolean

    fun stop()

    val lastError: String?

    /**
     * Подготовить выбранный сервер до старта движка — например, поднять
     * Xray-раннер для xhttp-узла или остановить его при выборе нативного
     * узла. Возвращает false, если сервер подготовить нельзя (выбор отклонён).
     * По умолчанию — no-op (StubEngine).
     */
    suspend fun prepareServer(tag: String, configJson: String): Boolean = true

    /** Вызывается после изменения подписок/манифеста раннера. */
    fun onSubscriptionChanged() {}
}

// Заглушка движка: sing-box (libbox.aar) не подключён в этой сборке.
// Честно сообщает, что подключения нет, вместо имитации работы.
class StubEngine : VpnEngine {
    override val version: String = "stub (sing-box не подключён)"
    override var lastError: String? = "Движок sing-box ещё не подключён в этой сборке"
        private set

    override fun validateConfig(configJson: String): Boolean {
        // Минимум здравого смысла без ядра: непустой JSON с outbounds.
        val trimmed = configJson.trim()
        return trimmed.startsWith("{") && trimmed.contains("\"outbounds\"")
    }

    override fun start(configJson: String): Boolean {
        lastError = "Движок sing-box ещё не подключён в этой сборке"
        return false
    }

    override fun stop() {
        // Нечего останавливать.
    }
}
