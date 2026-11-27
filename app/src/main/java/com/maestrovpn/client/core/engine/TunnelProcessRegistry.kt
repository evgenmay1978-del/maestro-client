package com.maestrovpn.client.core.engine

/**
 * Единственный источник правды о pid дочернего ядра.
 *
 * Зачем: уборка жила внутри службы, а служба может умереть раньше ядра
 * (onDestroy не успел, процесс приложения убили, scope отменился). Тогда
 * снаружи нечем ответить на вопрос "ядро ещё живо?" и нечем его добить.
 * Здесь pid регистрируют службы, отсюда его читают EngineManager (сторож
 * остановки) и тест-хуки диагностики.
 *
 * Только Android-путь: трогает [XrayNative] (JNI), в JVM-тестах не
 * используется — уборка проверяется через [TunnelTeardown] с лямбдами.
 */
object TunnelProcessRegistry {

    @Volatile
    private var pid: Long = -1L

    @Volatile
    private var owner: String = ""

    /** Запомнить pid живого ядра. pid <= 0 — сброс. */
    fun register(newPid: Long, newOwner: String) {
        if (newPid <= 0L) {
            clear(-1L)
            return
        }
        pid = newPid
        owner = newOwner
        VpnLog.i("реестр: ядро pid=" + newPid + " (" + newOwner + ")")
    }

    /** Снять запись, если она про этот pid (чужой pid не затираем). */
    fun clear(expectedPid: Long) {
        if (expectedPid <= 0L || pid == expectedPid) {
            pid = -1L
            owner = ""
        }
    }

    fun currentPid(): Long = pid

    fun currentOwner(): String = owner

    /** Жив ли записанный процесс. false — записи нет или kill(pid, 0) сказал "нет". */
    fun isAlive(): Boolean {
        val p = pid
        if (p <= 0L) return false
        return try {
            XrayNative.nativeIsAlive(p)
        } catch (e: Exception) {
            VpnLog.e("реестр: проверка живости pid=" + p + " упала", e)
            false
        }
    }

    /**
     * Принудительно добить ядро (аварийный путь сторожа остановки):
     * SIGTERM, пауза, SIGKILL, проверка. Синхронно, поэтому вызывать
     * только с фонового потока. true — процесса больше нет.
     */
    fun killNow(): Boolean {
        val p = pid
        if (p <= 0L) return true
        val teardown = TunnelTeardown(
            signal = { target, sig -> XrayNative.nativeSignal(target, sig) },
            isAlive = { target -> XrayNative.nativeIsAlive(target) },
            releaseResources = { },
            log = { VpnLog.i("реестр: " + it) },
        )
        val outcome = teardown.run(p)
        if (outcome.terminated) clear(p)
        return outcome.terminated
    }
}
