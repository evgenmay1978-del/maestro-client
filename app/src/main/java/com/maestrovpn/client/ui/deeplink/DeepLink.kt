package com.maestrovpn.client.ui.deeplink

import java.net.URLDecoder

/**
 * Deeplink-схема приложения (сейчас `maestro://`, спецификация §4).
 *
 * Команды:
 * - `maestro://add?url=<http(s)>` — открыть добавление подписки с URL;
 * - `maestro://connect` — подключиться (выбор сервера уже сделан);
 * - `maestro://disconnect` — отключиться;
 * - `maestro://toggle` — переключить состояние.
 *
 * Остальные хосты (§9.2: routing, send_to_device, crypt*) не реализованы:
 * неизвестный формат не должен молча ничего делать — отдаём Unsupported.
 */
sealed class DeepLink {
    /** Открыть экран добавления подписки с этим URL. */
    data class Add(val url: String) : DeepLink()

    /** Подключиться. */
    data object Connect : DeepLink()

    /** Отключиться. */
    data object Disconnect : DeepLink()

    /** Переключить состояние. */
    data object Toggle : DeepLink()

    /** Схема наша, но команда не поддержана. */
    data class Unsupported(val command: String) : DeepLink()

    companion object {
        const val SCHEME = "maestro"

        /**
         * Разбор URI. Чужая схема → null (вызывающий проверяет scheme сам).
         * Отсутствующий/невалидный url у `add` → Unsupported с причиной.
         */
        fun parse(raw: String?): DeepLink? {
            if (raw.isNullOrBlank()) return null
            val schemeEnd = raw.indexOf("://")
            if (schemeEnd < 0) return null
            val scheme = raw.substring(0, schemeEnd)
            if (!scheme.equals(SCHEME, ignoreCase = true)) return null
            val rest = raw.substring(schemeEnd + 3)
            val host = rest.substringBefore('/').substringBefore('?').lowercase()
            if (host.isEmpty()) return Unsupported("(пусто)")
            return when (host) {
                "add" -> {
                    val url = queryParam(rest, "url")?.trim()
                    when {
                        url.isNullOrEmpty() -> Unsupported("add без url")
                        !url.startsWith("http://") && !url.startsWith("https://") ->
                            Unsupported("add: url не http(s)")
                        else -> Add(url)
                    }
                }
                "connect" -> Connect
                "disconnect" -> Disconnect
                "toggle" -> Toggle
                else -> Unsupported(host)
            }
        }

        /**
         * Разбор query-параметра из остатка URI (`host/path?a=b&c=d`).
         * Пустое/отсутствующее значение → null.
         */
        private fun queryParam(rest: String, name: String): String? {
            val query = rest.substringAfter('?', "")
            if (query.isEmpty()) return null
            for (pair in query.split('&')) {
                val kv = pair.split('=', limit = 2)
                if (kv.size < 2) continue
                if (kv[0] == name) {
                    return URLDecoder.decode(kv[1], "UTF-8")
                }
            }
            return null
        }
    }
}
