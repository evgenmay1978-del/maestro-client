package com.maestrovpn.client.core.engine

import android.app.Application

// Состояние движка VPN.
enum class EngineStatus {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
}

// Единственный движок клиента — Xray-core (срез 7, issue #8), как в Happ:
// один нативный бинарь app/src/main/jniLibs/<abi>/libmaestro_subxhttp.so,
// запуск дочерним процессом через нативный fork/exec (XrayNative).
// sing-box/libbox удалён полностью: XHTTP он не поддерживает ни в одной
// версии, а узлы Maestro и Akonit — VLESS/XHTTP/REALITY.
//
// Интерфейс намеренно не зависит от нативного кода: XrayEngine — обычная
// реализация, а службы VPNService/ProxyService владеют процессом ядра.
interface VpnEngine {
    val version: String

    /** Инициализация ядра. По умолчанию — no-op. */
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
     * Подготовить выбранный сервер до старта движка. Xray не требует
     * подготовки: конфиг уже содержит все outbound'ы, раннера нет.
     * По умолчанию — no-op, возвращает true.
     */
    suspend fun prepareServer(tag: String, configJson: String): Boolean = true

    /** Вызывается после изменения подписок. Xray не требует действий. */
    fun onSubscriptionChanged() {}
}
