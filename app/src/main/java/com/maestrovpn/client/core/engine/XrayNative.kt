package com.maestrovpn.client.core.engine

/**
 * JNI-мост к нативному спавнеру (app/src/main/cpp/xray_spawn.c).
 *
 * Нативный fork/exec — единственный способ передать дочернему Xray tun fd:
 * ProcessBuilder закрывает все fd >= 3 при exec, а fork наследует всё без
 * FD_CLOEXEC. Все вызовы — быстрые, кроме [nativeWaitpid] (блокирующий,
 * только с фонового потока).
 */
object XrayNative {
    init {
        System.loadLibrary("xrayspawn")
    }

    const val SIGTERM = 15
    const val SIGKILL = 9

    /**
     * Запустить Xray-бинарь дочерним процессом.
     * @param tunFd сырой tun fd (ParcelFileDescriptor.detachFd) или -1 без tun.
     * @return pid дочернего процесса или -1 (причина — [nativeLastError]).
     *   Исходный tunFd после вызова можно закрывать: спавнер делает dup().
     */
    external fun nativeSpawn(
        tunFd: Int,
        binaryPath: String,
        configPath: String,
        logPath: String,
    ): Long

    /** Послать сигнал процессу: 0 — ок, -1 — ошибка ([nativeLastError]). */
    external fun nativeSignal(pid: Long, sig: Int): Int

    /** Жив ли процесс (kill(pid, 0)). */
    external fun nativeIsAlive(pid: Long): Boolean

    /**
     * Блокирующее ожидание завершения. Возвращает код выхода; -100-N —
     * процесс убит сигналом N; -1 — ошибка waitpid.
     */
    external fun nativeWaitpid(pid: Long): Int

    /** Человекочитаемая причина последней ошибки нативного вызова. */
    external fun nativeLastError(): String
}
