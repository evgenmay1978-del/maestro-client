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

    // Продвинутые настройки (issue #3). Читаются при сборке Xray-конфига
    // (AdvancedSettings.snapshot() -> XrayConfigBuilder.build), ключи
    // удаляются целиком кнопкой «Сбросить» в AdvancedSettingsScreen.
    const val ADVANCED_REMOTE_DNS_PRIMARY = "advanced_remote_dns_primary"
    const val ADVANCED_REMOTE_DNS_SECONDARY = "advanced_remote_dns_secondary"
    const val ADVANCED_QUERY_STRATEGY = "advanced_query_strategy"
    const val ADVANCED_TUN_MTU = "advanced_tun_mtu"
    const val ADVANCED_SOCKS_PORT = "advanced_socks_port"
    const val ADVANCED_HTTP_PORT = "advanced_http_port"
    const val ADVANCED_MUX_ENABLED = "advanced_mux_enabled"
    const val ADVANCED_MUX_CONCURRENCY = "advanced_mux_concurrency"
    const val ADVANCED_MUX_XUDP_CONCURRENCY = "advanced_mux_xudp_concurrency"
    const val ADVANCED_MUX_XUDP_BEHAVIOR = "advanced_mux_xudp_behavior"
}
