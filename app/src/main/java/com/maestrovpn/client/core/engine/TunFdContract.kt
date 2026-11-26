package com.maestrovpn.client.core.engine

/**
 * Контракт передачи tun fd дочернему Xray.
 *
 * Собранный бинарь (Xray 26.11.9 android/arm64, jniLibs) читает номер fd из
 * переменной окружения **xray.tun.fd** — Xray-core proxy/tun/tun_android.go:
 * `strconv.Atoi(platform.NewEnvFlag(platform.TunFdKey).GetValue(...))`.
 *
 * 26.11.2026 спавнер отдавал XRAY_TUN_FD: ядро переменной не видело, брало
 * fd по умолчанию, создавало tun само и падало через netlink с
 * «fail to get system interface information». Расхождение имён проверено на
 * самом бинаре: xray.tun.fd=<номер> меняет путь старта («bad file descriptor»
 * на негодном номере), XRAY_TUN_FD=<номер> — игнорируется.
 *
 * Литерал зашит и в app/src/main/cpp/xray_spawn.c (#define XRAY_TUN_FD_ENV);
 * совпадение проверяет TunFdContractTest — молчаливое расхождение имён уже
 * стоило одного неработающего старта.
 */
object TunFdContract {

    /** Имя переменной, которое читает ядро. */
    const val ENV_NAME = "xray.tun.fd"

    /** Младший номер fd, который отдаём ядру: 0/1/2 — журнал процесса. */
    const val MIN_FD = 3

    /** Значение переменной для номера fd — десятичное число без префиксов. */
    fun envValue(fd: Int): String = fd.toString()

    /** Годен ли номер для передачи ядру. */
    fun isValidFd(fd: Int): Boolean = fd >= MIN_FD

    /** Строка журнала: номер fd и переменная, по которой его найдёт ядро. */
    fun describe(fd: Int): String = "tun fd=" + fd + ", " + ENV_NAME + "=" + envValue(fd)
}
