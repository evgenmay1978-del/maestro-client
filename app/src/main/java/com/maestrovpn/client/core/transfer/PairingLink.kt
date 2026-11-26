package com.maestrovpn.client.core.transfer

/**
 * Адрес приёма подписки на втором устройстве (ТВ): приёмник показывает QR-код со
 * ссылкой этого вида, телефон сканирует её и отправляет подписку по локальной сети.
 *
 * Формат: maestro-pair://receive?host=192.168.1.10&port=53412&token=<16 байт hex>
 * Свой scheme, а не http, чтобы сканер однозначно отличал адрес приёма от ссылки
 * на подписку.
 */
data class PairingLink(
    val host: String,
    val port: Int,
    val token: String,
) {
    /** Строка для QR-кода. */
    fun toUri(): String = "$SCHEME://$HOST_PATH?host=$host&port=$port&token=$token"

    /** Что показать человеку рядом с QR. */
    fun display(): String = "$host:$port"

    companion object {
        const val SCHEME = "maestro-pair"
        const val HOST_PATH = "receive"
        private const val PREFIX = "$SCHEME://$HOST_PATH?"
        private val TOKEN_PATTERN = Regex("[A-Za-z0-9_-]{8,64}")
        private val HOST_PATTERN = Regex("[A-Za-z0-9._-]{1,253}")

        fun isValidHost(value: String): Boolean =
            value.isNotBlank() && value.length <= 253 && HOST_PATTERN.matches(value) &&
                !value.startsWith(".") && !value.endsWith(".") && !value.contains("..")

        fun isValidPort(value: Int): Boolean = value in 1..65535

        fun isValidToken(value: String): Boolean = TOKEN_PATTERN.matches(value)

        /** Разбор отсканированной ссылки; null — если это не адрес приёма или он битый. */
        fun parse(raw: String?): PairingLink? {
            val text = raw?.trim().orEmpty()
            if (!text.startsWith(PREFIX, ignoreCase = true)) return null
            val params = LinkedHashMap<String, String>()
            text.substring(PREFIX.length).split('&').forEach { pair ->
                if (pair.isEmpty()) return@forEach
                val index = pair.indexOf('=')
                if (index <= 0) return@forEach
                params[pair.substring(0, index).lowercase()] = pair.substring(index + 1)
            }
            val host = params["host"]?.trim().orEmpty()
            val port = params["port"]?.trim()?.toIntOrNull() ?: return null
            val token = params["token"]?.trim().orEmpty()
            if (!isValidHost(host) || !isValidPort(port) || !isValidToken(token)) return null
            return PairingLink(host, port, token)
        }

        /** Подходит ли отсканированный текст под формат адреса приёма. */
        fun looksLike(raw: String?): Boolean =
            raw?.trim()?.startsWith("$SCHEME://", ignoreCase = true) == true
    }
}
