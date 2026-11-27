package com.maestrovpn.client.core.settings

// Продвинутые настройки (issue #3): значения, которые попадают в Xray-конфиг.
// Чистый Kotlin без Android API — проверяется на обычной JVM;
// хранение и UI — в AdvancedSettings / AdvancedSettingsScreen.
//
// Смысл выделения: XrayConfigBuilder должен оставаться без Android-зависимостей
// (его зовут тесты на JVM и он валидируется настоящим `xray run -test`),
// поэтому настройки приходят в него как data class, а не читаются из Room.
data class AdvancedConfig(
    val remoteDnsPrimary: String = DEFAULT_DNS_PRIMARY,
    val remoteDnsSecondary: String = DEFAULT_DNS_SECONDARY,
    val domesticDnsPrimary: String = DEFAULT_DOMESTIC_DNS_PRIMARY,
    val domesticDnsSecondary: String = DEFAULT_DOMESTIC_DNS_SECONDARY,
    val queryStrategy: String = QUERY_USE_IPV4,
    val tunMtu: Int = DEFAULT_TUN_MTU,
    val socksPort: Int = DEFAULT_SOCKS_PORT,
    val httpPort: Int = DEFAULT_HTTP_PORT,
    val localDnsPort: Int = DEFAULT_LOCAL_DNS_PORT,
    val muxEnabled: Boolean = false,
    val muxConcurrency: Int = DEFAULT_MUX_CONCURRENCY,
    val muxXudpConcurrency: Int = DEFAULT_MUX_XUDP_CONCURRENCY,
    val muxXudpBehavior: String = MUX_REJECT,
    val fragmentEnabled: Boolean = false,
    val fragmentLengthMin: Int = DEFAULT_FRAGMENT_LENGTH_MIN,
    val fragmentLengthMax: Int = DEFAULT_FRAGMENT_LENGTH_MAX,
    val fragmentIntervalMin: Int = DEFAULT_FRAGMENT_INTERVAL_MIN,
    val fragmentIntervalMax: Int = DEFAULT_FRAGMENT_INTERVAL_MAX,
    val fragmentMaxSplit: Int = DEFAULT_FRAGMENT_MAX_SPLIT,
    val noisesEnabled: Boolean = false,
    val noisesPacket: Boolean = false,
    val noisesDelayMin: Int = DEFAULT_NOISES_DELAY_MIN,
    val noisesDelayMax: Int = DEFAULT_NOISES_DELAY_MAX,
    val noisesRand: Boolean = false,
    val noisesRange: Int = DEFAULT_NOISES_RANGE,
    val noisesType: String = DEFAULT_NOISES_TYPE,
    val sniffingEnabled: Boolean = true,
) {
    /** DNS-серверы как их ждёт Xray: пустые строки отброшены. */
    fun dnsServers(): List<String> = listOf(remoteDnsPrimary, remoteDnsSecondary)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .ifEmpty { listOf(DEFAULT_DNS_PRIMARY, DEFAULT_DNS_SECONDARY) }

    companion object {
        const val DEFAULT_DNS_PRIMARY = "1.1.1.1"
        const val DEFAULT_DNS_SECONDARY = "8.8.8.8"
        const val DEFAULT_DOMESTIC_DNS_PRIMARY = "8.8.8.8"
        const val DEFAULT_DOMESTIC_DNS_SECONDARY = "1.1.1.1"
        const val DEFAULT_TUN_MTU = 1280
        const val DEFAULT_SOCKS_PORT = 10808
        const val DEFAULT_HTTP_PORT = 10809
        const val DEFAULT_LOCAL_DNS_PORT = 10853
        const val DEFAULT_MUX_CONCURRENCY = 8
        const val DEFAULT_MUX_XUDP_CONCURRENCY = 8

        const val MTU_MIN = 1280
        const val MTU_MAX = 9000
        const val PORT_MIN = 1
        const val PORT_MAX = 65535
        const val MUX_MIN = 0
        const val MUX_MAX = 1024

        const val QUERY_USE_IPV4 = "UseIPv4"
        const val QUERY_USE_IP = "UseIP"
        const val QUERY_USE_IPV6 = "UseIPv6"

        val QUERY_STRATEGIES = listOf(QUERY_USE_IPV4, QUERY_USE_IP, QUERY_USE_IPV6)

        const val MUX_REJECT = "reject"
        const val MUX_ALLOW = "allow"
        const val MUX_SKIP = "skip"

        val MUX_BEHAVIORS = listOf(MUX_REJECT, MUX_ALLOW, MUX_SKIP)

        // Fragmentation defaults (Happ: length 50-100, interval 10-20, max split 100-200)
        const val DEFAULT_FRAGMENT_LENGTH_MIN = 50
        const val DEFAULT_FRAGMENT_LENGTH_MAX = 100
        const val DEFAULT_FRAGMENT_INTERVAL_MIN = 10
        const val DEFAULT_FRAGMENT_INTERVAL_MAX = 20
        const val DEFAULT_FRAGMENT_MAX_SPLIT = 200

        // Noises defaults (Happ: delay 10-16, packet, rand, range, type)
        const val DEFAULT_NOISES_DELAY_MIN = 10
        const val DEFAULT_NOISES_DELAY_MAX = 16
        const val DEFAULT_NOISES_RANGE = 100
        const val DEFAULT_NOISES_TYPE = "xudp"

        val DEFAULT = AdvancedConfig()

        fun queryStrategyLabel(value: String): String = when (value) {
            QUERY_USE_IP -> "UseIP (IPv4+IPv6)"
            QUERY_USE_IPV6 -> "UseIPv6"
            else -> "UseIPv4"
        }

        fun normalizeQueryStrategy(value: String): String =
            value.takeIf { it in QUERY_STRATEGIES } ?: QUERY_USE_IPV4

        fun normalizeMuxBehavior(value: String): String =
            value.takeIf { it in MUX_BEHAVIORS } ?: MUX_REJECT

        /**
         * Адрес DNS-сервера: IP, домен или DoH-URL. Xray принимает все три,
         * поэтому проверяем только очевидный мусор — пустоту, пробелы внутри
         * и недопустимые для адреса/URL символы.
         */
        fun isValidDnsServer(value: String): Boolean {
            val v = value.trim()
            if (v.isEmpty() || v.length > 255) return false
            if (v.any { it.isWhitespace() }) return false
            return v.all { it.isLetterOrDigit() || it in ".:-_/[]@%" }
        }

        fun isValidPort(value: String): Boolean =
            value.trim().toIntOrNull()?.let { it in PORT_MIN..PORT_MAX } == true

        fun isValidMtu(value: String): Boolean =
            value.trim().toIntOrNull()?.let { it in MTU_MIN..MTU_MAX } == true

        fun isValidMuxConcurrency(value: String): Boolean =
            value.trim().toIntOrNull()?.let { it in MUX_MIN..MUX_MAX } == true
    }
}
