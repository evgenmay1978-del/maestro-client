package com.maestrovpn.client.vendor

/**
 * Заглушка Vendor для standalone-клиента (срез 2).
 *
 * У Maestro Vendor — это объект из vendor/VendorInterface.kt (Shizuku/root,
 * расписание автообновления приложения). В новом клиенте root/Shizuku нет:
 * per-app proxy через RootClient/RootServer не переносится, поэтому
 * isPerAppProxyAvailable() = false — BoxService просто не включает per-app
 * списки (PackagePolicy при этом всё равно держит само приложение вне туннеля).
 * scheduleAutoUpdate() — no-op: автообновления самого приложения в срезе 2 нет.
 */
object Vendor {
    fun isPerAppProxyAvailable(): Boolean = false

    fun scheduleAutoUpdate() {
        // Нет автообновления приложения в срезе 2.
    }
}
