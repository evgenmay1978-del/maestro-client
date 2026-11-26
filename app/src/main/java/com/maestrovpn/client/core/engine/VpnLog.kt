package com.maestrovpn.client.core.engine

import android.util.Log

/**
 * Журнал VPN-контура в logcat (тег MaestroVpn).
 *
 * Зачем: 26.11.2026 включение VPN зависало в «Подключение…», и по logcat
 * нельзя было понять, на каком шаге оно встало — приложение почти ничего
 * не писало. Теперь каждый шаг старта/остановки виден в logcat:
 *   adb logcat -d | grep MaestroVpn
 */
object VpnLog {
    const val TAG = "MaestroVpn"

    fun i(message: String) = Log.i(TAG, message)

    fun w(message: String) = Log.w(TAG, message)

    fun e(message: String, error: Throwable? = null) {
        if (error == null) Log.e(TAG, message) else Log.e(TAG, message, error)
    }
}
