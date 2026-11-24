package com.maestrovpn.client.constant

// Только ключи, которые реально читаются/пишутся (срез 7, issue #8):
// 37 Maestro-ключей (обновления, F-Droid, AIVPN, privilege settings,
// OOM killer, дашборд, remote control, Tailscale SSH, кэш обновлений,
// selected_profile/service_mode/dynamic_notification) удалены —
// ни один не использовался в коде.
object SettingsKey {
    const val AUTO_REDIRECT = "auto_redirect"
    const val PER_APP_PROXY_ENABLED = "per_app_proxy_enabled"
    const val PER_APP_PROXY_MODE = "per_app_proxy_mode"
    const val PER_APP_PROXY_LIST = "per_app_proxy_list"
    const val PER_APP_PROXY_MANAGED_MODE = "per_app_proxy_managed_mode"
    const val PER_APP_PROXY_MANAGED_LIST = "per_app_proxy_managed_list"

    const val ALLOW_BYPASS = "allow_bypass"
    const val SYSTEM_PROXY_ENABLED = "system_proxy_enabled"

    const val STARTED_BY_USER = "started_by_user"

    // Whether we've already offered (once) to add the quick-settings tile to the shade (Android 13+).
    const val QS_TILE_PROMPTED = "qs_tile_prompted"
}
