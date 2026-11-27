package com.maestrovpn.client.core.routing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.maestrovpn.client.core.settings.AdvancedConfig

/**
 * Профиль маршрутизации → секции routing.rules + dns для конфига Xray
 * (раздел 6: структура конфига; домены/IP — из профиля).
 *
 * Чистый Kotlin — проверяется JVM-тестом.
 *
 * Порядок правил — как в Happ: block → proxy → direct (первое совпадение
 * выигрывает, поэтому блокировки идут первыми).
 *
 * ВАЖНО (27.11.2026): секция routing.final ядру неизвестна — оно её
 * игнорирует (проверено на собранном бинаре Xray 26.11.9: config с
 * "final":123 проходит -test). Поэтому «куда идёт остальное» задаётся
 * явным замыкающим правилом, а не полем final.
 */
object RoutingRulesBuilder {

    /**
     * Куда уходит трафик, не попавший ни в одно правило профиля.
     * Ровно одно из полей: конкретный сервер (outboundTag) или
     * балансировщик auto (balancerTag). Пустые оба — ошибка вызывающего.
     */
    data class CatchAll(
        val outboundTag: String? = null,
        val balancerTag: String? = null,
    ) {
        init {
            require((outboundTag == null) != (balancerTag == null)) {
                "нужен ровно один тег: outboundTag или balancerTag"
            }
        }
    }

    /** Собрать routing.rules из профиля. Пустой список — правил нет. */
    fun rules(profile: RoutingProfile, catchAll: CatchAll): JsonArray = buildJsonArray {
        // 1. Блокировки: geosite/домены/IP.
        addRule(this, profile.blockSites, profile.blockIp, "block")
        // 2. Прокси: только когда globalProxy выключен (иначе остальное
        //    и так уходит в замыкающее правило).
        if (!profile.globalProxy) {
            addRule(this, profile.proxySites, profile.proxyIp, null, catchAll)
        }
        // 3. Прямые: сайты/IP мимо прокси.
        addRule(this, profile.directSites, profile.directIp, "direct")
    }

    /**
     * Замыкающее правило: весь оставшийся трафик — в выбранный сервер
     * (или в балансировщик auto). Ставится последним в routing.rules.
     */
    fun catchAllRule(catchAll: CatchAll): JsonObject = buildJsonObject {
        put("type", "field")
        put("network", "tcp,udp")
        putTarget(this, catchAll)
    }

    /**
     * Перехват DNS: запросы из tun (порт 53) уходят в outbound ["dns"]
     * (protocol "dns"), а не в прокси как обычный UDP. Ставится первым.
     */
    fun dnsInterceptRule(inboundTag: String = "tun-in", outboundTag: String = "dns"): JsonObject =
        buildJsonObject {
            put("type", "field")
            put("inboundTag", buildJsonArray { add(inboundTag) })
            put("network", "tcp,udp")
            put("port", 53)
            put("outboundTag", outboundTag)
        }

    /**
     * Сборка dns-секции из профиля. null — нечего настраивать
     * (тогда конфиг оставляет DNS по умолчанию).
     *
     * DoH задаётся IP-литералом из remoteDnsIp/domesticDnsIp: доменное имя
     * в dns.servers само требует разрешения и без bootstrap-записи в hosts
     * даёт цикл «DNS для DNS». Путь (/dns-query) берётся из домена профиля.
     */
    fun dns(profile: RoutingProfile, advanced: AdvancedConfig = AdvancedConfig.DEFAULT): JsonObject? {
        val hosts = profile.dnsHosts.filterKeys { it.isNotBlank() }
        val fakeDns = profile.fakeDns
        if (profile.remoteDnsDomain.isBlank() &&
            profile.domesticDnsDomain.isBlank() &&
            hosts.isEmpty() &&
            !fakeDns
        ) {
            return null
        }
        return buildJsonObject {
            if (hosts.isNotEmpty()) {
                put("hosts", JsonObject(hosts.mapValues { JsonPrimitive(it.value) }))
            }
            val servers = buildJsonArray {
                // fakedns первым: с ним ядро отдаёт синтетические адреса
                // из ipPool, а настоящие имена подставляет sniffing.
                if (fakeDns) add("fakedns")
                if (profile.remoteDnsDomain.isNotBlank()) {
                    add(dnsServer(profile.remoteDnsType, profile.remoteDnsDomain, profile.remoteDnsIp))
                }
                if (profile.domesticDnsDomain.isNotBlank()) {
                    add(dnsServer(profile.domesticDnsType, profile.domesticDnsDomain, profile.domesticDnsIp))
                }
            }
            if (servers.isNotEmpty()) put("servers", servers)
            // Стратегия — из настроек, не хардкод: на IPv6-only сети
            // принудительный UseIPv4 рвёт разрешение.
            put("queryStrategy", advanced.queryStrategy)
        }
    }

    private fun addRule(
        target: JsonArrayBuilder,
        sites: List<String>,
        ips: List<String>,
        outboundTag: String?,
        catchAll: CatchAll? = null,
    ) {
        val domains = sites.filter { it.isNotBlank() }
        val addresses = ips.filter { it.isNotBlank() }
        if (domains.isEmpty() && addresses.isEmpty()) return
        val rule = buildJsonObject {
            put("type", "field")
            if (domains.isNotEmpty()) {
                put("domain", JsonArray(domains.map { JsonPrimitive(it) }))
            }
            if (addresses.isNotEmpty()) {
                put("ip", JsonArray(addresses.map { JsonPrimitive(it) }))
            }
            // null — «как обычный проксируемый трафик»: тег берём из
            // замыкающего правила (раньше тут был несуществующий "proxy").
            if (outboundTag != null) put("outboundTag", JsonPrimitive(outboundTag))
            else putTarget(this, requireNotNull(catchAll) { "нужен catchAll для правила proxy" })
        }
        target.add(rule)
    }

    /** Тег назначения: конкретный сервер или балансировщик auto. */
    private fun putTarget(rule: JsonObjectBuilder, catchAll: CatchAll) {
        if (catchAll.outboundTag != null) {
            rule.put("outboundTag", JsonPrimitive(catchAll.outboundTag))
        } else {
            rule.put("balancerTag", JsonPrimitive(catchAll.balancerTag))
        }
    }

    /**
     * Тип DNS → адрес сервера для dns.servers[].
     * DoH: при наличии IP-литерала — https://<ip><путь>, иначе домен как есть.
     */
    private fun dnsServer(type: String, domain: String, ip: String): JsonPrimitive =
        if (type.equals("DoH", ignoreCase = true)) JsonPrimitive(doHAddress(domain, ip))
        else JsonPrimitive(domain.removePrefix("https://").removePrefix("http://"))

    private fun doHAddress(domain: String, ip: String): String {
        val path = domainPath(domain)
        val address = ip.trim()
        return if (AdvancedConfig.isValidDnsServer(address) && !address.contains('/')) {
            "https://" + address + path
        } else {
            domain
        }
    }

    /** Путь DoH-эндпоинта: "/dns-query" по умолчанию. */
    private fun domainPath(domain: String): String {
        val withoutScheme = domain.removePrefix("https://").removePrefix("http://")
        val slash = withoutScheme.indexOf('/')
        return if (slash >= 0) withoutScheme.substring(slash) else "/dns-query"
    }
}
