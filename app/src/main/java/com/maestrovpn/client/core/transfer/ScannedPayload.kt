package com.maestrovpn.client.core.transfer

/**
 * Что именно закодировано в QR-коде, который отсканировал телефон.
 *
 * Правила разбора намеренно разделены по схеме, чтобы поведение было предсказуемым:
 * - maestro-pair:// — адрес приёма на другом устройстве (ТВ), подписку отправляем туда;
 * - http(s):// — ссылка на подписку, её можно сразу добавить;
 * - vless/vmess/ss/trojan/hy2/wireguard/socks — ссылка на конкретный сервер;
 * - всё остальное — «не наше» и показываем причину, а не молчим.
 */
sealed class ScannedPayload {
    data class Pairing(val link: PairingLink) : ScannedPayload()

    data class Subscription(val url: String) : ScannedPayload()

    data class ServerLink(val raw: String, val scheme: String) : ScannedPayload()

    data class Unknown(val reason: String) : ScannedPayload()

    companion object {
        private val SERVER_SCHEMES = setOf(
            "vless", "vmess", "ss", "ssr", "trojan", "hy2", "hysteria2", "wireguard", "wg", "socks",
        )
        private val SUBSCRIPTION_SCHEMES = setOf("http", "https")

        fun classify(raw: String?): ScannedPayload {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return Unknown("Пустой QR-код")

            if (PairingLink.looksLike(text)) {
                val link = PairingLink.parse(text)
                return if (link == null) Unknown("Адрес приёма повреждён") else Pairing(link)
            }

            val scheme = schemeOf(text)
            if (scheme == null) return Unknown("В QR-коде не ссылка: " + shorten(text))

            if (scheme == "maestro") return Unknown("Это команда maestro://, а не подписка")
            if (scheme in SUBSCRIPTION_SCHEMES) {
                return if (looksLikeUrl(text)) Subscription(text) else Unknown("Ссылка на подписку повреждена")
            }
            if (scheme in SERVER_SCHEMES) return ServerLink(text, scheme)
            return Unknown("Схема «$scheme» не поддержана")
        }

        private fun schemeOf(text: String): String? {
            val index = text.indexOf("://")
            if (index <= 0) return null
            val scheme = text.substring(0, index).lowercase()
            if (!scheme.all { it.isLetterOrDigit() || it == '+' || it == '-' || it == '.' }) return null
            return scheme
        }

        private fun looksLikeUrl(text: String): Boolean {
            val rest = text.substringAfter("://")
            if (rest.isBlank()) return false
            if (rest.any { it.isWhitespace() }) return false
            val host = rest.substringBefore('/').substringBefore('?').substringAfter('@')
                .substringBefore(':')
            return host.contains('.') || host == "localhost"
        }

        private fun shorten(text: String): String =
            if (text.length <= 24) text else text.take(24) + "…"
    }
}
