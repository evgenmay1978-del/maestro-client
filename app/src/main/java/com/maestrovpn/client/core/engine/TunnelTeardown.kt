package com.maestrovpn.client.core.engine

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Синхронная уборка туннеля: SIGTERM -> ожидание <= graceMs -> SIGKILL ->
 * проверка живости -> освобождение ресурсов (wake lock).
 *
 * Почему не корутина. Старый stopTunnel() запускал уборку через scope.launch,
 * а onDestroy() сразу после него звал scope.cancel(): корутина не успевала
 * стартовать, сигналы не уходили, Xray оставался жив с tun fd, Android
 * продолжал держать VPN, а экран показывал "Отключено" (26.11.2026).
 * Уборка обязана быть блокирующей и не зависеть от жизненного цикла корутин.
 *
 * Все внешние эффекты — лямбды (сигнал, проверка живости, освобождение
 * ресурсов, сон), поэтому класс — чистый Kotlin и проверяется JVM-тестом
 * без Android и без нативного кода.
 */
class TunnelTeardown(
    private val signal: (pid: Long, sig: Int) -> Unit,
    private val isAlive: (pid: Long) -> Boolean,
    private val releaseResources: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: (ms: Long) -> Unit = { ms -> Thread.sleep(ms) },
    private val graceMs: Long = DEFAULT_GRACE_MS,
    private val killGraceMs: Long = DEFAULT_KILL_GRACE_MS,
    private val pollMs: Long = DEFAULT_POLL_MS,
    private val log: (message: String) -> Unit = {},
) {

    /** Итог уборки: нужен и логу, и тесту, и вызову из EngineManager. */
    data class Outcome(
        val pid: Long,
        val alreadyDone: Boolean,
        val signalledTerm: Boolean,
        val escalatedToKill: Boolean,
        val terminated: Boolean,
        val resourcesReleased: Boolean,
    ) {
        fun describe(): String =
            "pid=" + pid +
                " alreadyDone=" + alreadyDone +
                " sigterm=" + signalledTerm +
                " sigkill=" + escalatedToKill +
                " terminated=" + terminated +
                " resourcesReleased=" + resourcesReleased
    }

    private val done = AtomicBoolean(false)

    /**
     * Убрать процесс и ресурсы. Идемпотентно: повторный вызов ничего не
     * делает (сигналы не шлёт, ресурсы повторно не освобождает).
     * Исключения внешних лямбд не выходят наружу — уборка доводится до конца
     * даже на пути ошибки.
     */
    fun run(pid: Long): Outcome {
        if (!done.compareAndSet(false, true)) {
            log("teardown: уже выполнен, повторно не убираем (pid=" + pid + ")")
            return Outcome(
                pid = pid,
                alreadyDone = true,
                signalledTerm = false,
                escalatedToKill = false,
                terminated = false,
                resourcesReleased = false,
            )
        }
        var signalled = false
        var escalated = false
        var terminated = pid <= 0L
        var released = false
        try {
            if (pid > 0L) {
                signalled = send(pid, SIGTERM)
                terminated = awaitExit(pid, graceMs)
                if (!terminated) {
                    escalated = send(pid, SIGKILL)
                    terminated = awaitExit(pid, killGraceMs)
                }
                if (!terminated) {
                    log("teardown: процесс " + pid + " жив после SIGKILL")
                }
            }
        } finally {
            released = try {
                releaseResources()
                true
            } catch (e: Exception) {
                log("teardown: освобождение ресурсов упало: " + (e.message ?: e.javaClass.simpleName))
                false
            }
        }
        val outcome = Outcome(pid, false, signalled, escalated, terminated, released)
        log("teardown: " + outcome.describe())
        return outcome
    }

    private fun send(pid: Long, sig: Int): Boolean = try {
        signal(pid, sig)
        true
    } catch (e: Exception) {
        log("teardown: сигнал " + sig + " не отправлен: " + (e.message ?: e.javaClass.simpleName))
        false
    }

    /** Ждём выхода процесса не дольше timeoutMs. true — процесса больше нет. */
    private fun awaitExit(pid: Long, timeoutMs: Long): Boolean {
        val deadline = now() + timeoutMs
        while (now() < deadline) {
            if (!alive(pid)) return true
            try {
                sleep(pollMs)
            } catch (e: InterruptedException) {
                // Прервали — состояние всё равно проверяем явно.
                Thread.currentThread().interrupt()
                return !alive(pid)
            }
        }
        return !alive(pid)
    }

    /**
     * Проверка живости не должна сама ломать уборку: сбой kill(pid, 0)
     * считаем "процесса нет" — иначе цикл ожидания никогда не завершится.
     */
    private fun alive(pid: Long): Boolean = try {
        isAlive(pid)
    } catch (e: Exception) {
        log("teardown: проверка живости упала: " + (e.message ?: e.javaClass.simpleName))
        false
    }

    companion object {
        const val SIGTERM = 15
        const val SIGKILL = 9

        /** Пауза на штатный выход ядра после SIGTERM (успевает закрыть сокеты). */
        const val DEFAULT_GRACE_MS = 300L

        /** Пауза после SIGKILL: процесс должен исчезнуть из таблицы. */
        const val DEFAULT_KILL_GRACE_MS = 300L
        const val DEFAULT_POLL_MS = 50L
    }
}
