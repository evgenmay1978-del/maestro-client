package com.maestrovpn.client.ui.navigation

// Маршруты приложения. Срез 1: главный экран — настоящий, остальные экраны —
// заглушки с заголовком и описанием; логика — следующие срезы.
data class ScreenSpec(
    val route: String,
    val title: String,
    val mapRef: String,
    val description: String,
)

object Routes {
    const val MAIN = "main"
    const val SERVERS = "servers"
    const val SERVER_EDITOR = "server_editor"
    const val SERVER_CUSTOM_CONFIG = "server_custom_config"
    const val SUBSCRIPTIONS = "subscriptions"
    const val SUBSCRIPTION_ADD = "subscription_add"
    const val SUBSCRIPTION_PROPERTIES = "subscription_properties"
    const val SETTINGS = "settings"
    const val SETTINGS_ADVANCED = "settings_advanced"
    const val SETTINGS_TUNNEL = "settings_tunnel"
    const val SETTINGS_UI = "settings_ui"
    const val SETTINGS_LANGUAGE = "settings_language"
    const val VPN_SETTINGS = "vpn_settings"
    const val INBOUND_AUTH = "inbound_auth"
    const val EXCLUDED_ROUTES = "excluded_routes"
    const val PER_APP_PROXY = "per_app_proxy"
    const val PING_SETTINGS = "ping_settings"
    const val STATISTICS = "statistics"
    const val LOGS = "logs"
    const val LOGS_VIEW = "logs_view"
    const val ROUTING = "routing"
    const val ROUTING_EDIT = "routing_edit"
    const val ROUTING_SETTINGS_EDIT_SEARCH = "routing_search"
    const val ROUTING_RULES = "routing_rules"
    const val ROUTING_PROFILE = "routing_profile"
    const val SUB_ROUTING = "sub_routing"
    const val SCANNER = "scanner"
    const val SCANNER_SC = "scanner_sc"
    const val SCANNER_SWITCH = "scanner_switch"
    const val URL_SCHEMES = "url_schemes"
    const val FAQ = "faq"
    const val ABOUT = "about"
    const val REPORT = "report"
    const val RESET = "reset"
    const val APP_UPDATE = "app_update"
    const val IMPORT_CLIPBOARD = "import_clipboard"
    const val QR_SCAN = "qr_scan"

    // Редактор сервера — настоящий экран с аргументами (срез 5, issue #1).
    fun serverEditorRoute(id: String?, protocol: String?): String {
        val params = listOfNotNull(
            id?.let { "id=$it" },
            protocol?.let { "protocol=$it" },
        ).joinToString("&")
        return if (params.isEmpty()) SERVER_EDITOR else "$SERVER_EDITOR?$params"
    }

    // Все экраны-заглушки (32 своих экрана, 27 полностью описаны).
    val stubs: List<ScreenSpec> = listOf(
        ScreenSpec(SERVER_CUSTOM_CONFIG, "Произвольный конфиг", "карта §2.7", "полный Xray JSON, предупреждение о несохранённом"),
        ScreenSpec(SUBSCRIPTION_PROPERTIES, "Свойства подписки", "карта §3", "таймаут, User-Agent, автообновление, autoswitch"),
        ScreenSpec(SETTINGS_TUNNEL, "Настройки туннеля", "карта §4", "TUN MTU, IP-тип, Fake DNS, Xray TUN"),
        ScreenSpec(SETTINGS_UI, "Интерфейс", "карта §4", "размер шрифта, скорость в уведомлении, live-обновления"),
        ScreenSpec(SETTINGS_LANGUAGE, "Язык", "карта §4", "Auto / English / Русский / 中文 / فارسی"),
        ScreenSpec(VPN_SETTINGS, "Настройки VPN", "карта §5.1", "TUN MTU, Tunnel DNS, тип IP"),
        ScreenSpec(INBOUND_AUTH, "Inbound-авторизация", "карта §5.2", "HTTP/SOCKS auth: Auto / Manual / From JSON / Disable"),
        ScreenSpec(EXCLUDED_ROUTES, "Исключённые маршруты", "карта §5.3", "список CIDR, импорт/экспорт"),
        ScreenSpec(PER_APP_PROXY, "Per-app proxy", "карта §5.4", "только выбранные / кроме выбранных, пресеты"),
        ScreenSpec(PING_SETTINGS, "Настройки пинга", "карта §6.1", "тип, режим, таймаут 5–15 с"),
        ScreenSpec(STATISTICS, "Статистика", "карта §6.3", "время сессии, трафик up/down, proxy/direct"),
        ScreenSpec(LOGS, "Логи", "карта §6.4", "уровни, пауза, очистка, экспорт"),
        ScreenSpec(LOGS_VIEW, "Просмотр логов", "карта §6.4", "постраничная навигация по логам"),
        ScreenSpec(ROUTING, "Маршрутизация", "карта §7", "профили маршрутизации, привязка к подписке"),
        ScreenSpec(ROUTING_EDIT, "Редактор маршрутизации", "карта §7", "создание профиля, default rules, LAN"),
        ScreenSpec(ROUTING_RULES, "Правила маршрутизации", "карта §7", "порядок block/proxy/direct, geosite/geoip"),
        ScreenSpec(ROUTING_PROFILE, "Профиль маршрутизации", "карта §7", "DNS-зоны, стратегия доменов"),
        ScreenSpec(SUB_ROUTING, "Маршрутизация подписки", "карта §7", "игнорировать импорт маршрутов, привязка профиля"),
        ScreenSpec(SCANNER, "Сканер QR", "карта §8.1", "камера, альбом, фонарик"),
        ScreenSpec(SCANNER_SC, "ScScanner", "карта §8.1", "служебный сканер"),
        ScreenSpec(SCANNER_SWITCH, "ScSwitch", "карта §8.1", "служебный переключатель сканера"),
        ScreenSpec(URL_SCHEMES, "URL-схемы", "карта §8.3", "справка по своим схемам maestro://"),
        ScreenSpec(FAQ, "FAQ", "карта §8.5", "локальный FAQ без сторонних ссылок"),
        ScreenSpec(REPORT, "Сообщить о проблеме", "карта §8.7", "отчёт — в бот @MaestroSecureVPN_bot, без HWID"),
        ScreenSpec(RESET, "Сброс", "карта §8.8", "сброс настроек / полный сброс приложения"),
        ScreenSpec(APP_UPDATE, "Обновление приложения", "карта §8.9", "свой OTA (панель + зеркало), не GitHub/F-Droid"),
        ScreenSpec(IMPORT_CLIPBOARD, "Импорт из буфера", "карта §1.1", "вставка конфига/ссылки из буфера обмена"),
        ScreenSpec(QR_SCAN, "Сканирование QR", "карта §8.1", "QR-сканер для импорта сервера/подписки"),
    )
}