package com.maestrovpn.client.core.routing

import com.maestrovpn.client.App
import java.io.File

/**
 * Геоданные (§8): в приложении лежат в assets как `geoip.dat` и `geosite.dat`,
 * а Xray-core ищет их **рядом с бинарём ядра** (проверено: `xray run -test`
 * открывает `<binaryDir>/geosite.dat`).
 *
 * Копирование идемпотентно: если фаилы уже на месте и размер совпадает —
 * не трогаем.
 */
object GeoDataFiles {

    private val NAMES = listOf("geoip.dat", "geosite.dat")

    /**
     * Положить геоданные в [binaryDir]. Возвращает false, если assets
     * недоступны (например в JVM-тесте без Android) — вызывающий не обязан
     * проверять результат: без геоданных правила geoip:/geosite: не работают.
     */
    fun install(binaryDir: File): Boolean {
        val assets = runCatching { App.application.assets }.getOrNull() ?: return false
        if (!binaryDir.exists() && !binaryDir.mkdirs()) return false
        var ok = true
        for (name in NAMES) {
            val target = File(binaryDir, name)
            try {
                assets.open(name).use { input ->
                    val expected = input.available().toLong()
                    if (expected <= 0L) return@use
                    if (target.exists() && target.length() == expected) return@use
                    target.outputStream().use { output -> input.copyTo(output) }
                }
            } catch (_: Exception) {
                ok = false
            }
        }
        return ok
    }
}
