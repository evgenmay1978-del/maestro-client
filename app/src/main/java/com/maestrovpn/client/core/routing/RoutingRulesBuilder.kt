package com.maestrovpn.client.core.routing

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArrayBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Профиль маршрутизации → секции routing.rules + dns для конфига Xray
 * (раздел 6: структура конфига; домены/IP — из профиля).
 *
 * Чистый Kotlin — проверяется JVM-тестом.
 *
 * Порядок правил — как в Happ: block → proxy → direct (первое совпадение
 * выигрывает, поэтому блокировки идут первыми).
 */
object RoutingRulesBuilder {

    /** Собрать routing.rules из профиля. Пустой список — правил нет. */
    fun rules(profile: RoutingProfile): JsonArray {
        val rules = buildJsonArray {
            // 1. Блокировки: geosite/домены/IP.
            addRule(this, profile.blockSites, profile.blockIp, "block")
            // 2. Прокси: только когда globalProxy выключен (иначе весь
            //    трафик и так в proxy через routing.final).
            if (!profile.globalProxy) {
                addRule(this, profile.proxySites, profile.proxyIp, null)
            }
            // 3. Прямые: сайты/IP мимо прокси.
            addRule(this, profile.directSites, profile.directIp, "direct")
        }
        return rules
    }

    /**
     * Сборка dns-секции из профиля. null — нечего настраивать
     * (тогда конфиг оставляет DNS по умолчанию).
     */
    fun dns(profile: RoutingProfile): JsonObject? {
        val hosts = profile.dnsHosts.filterKeys { it.isNotBlank() }
        if (profile.remoteDnsDomain.isBlank() &&
            profile.domesticDnsDomain.isBlank() &&
            hosts.isEmpty()
        ) {
            return null
        }
        return buildJsonObject {            // hosts
            if (hosts.isNotEmpty()) {
                put("hosts", JsonObject(hosts.mapValues { JsonPrimitive(it.value) }))
            }
            // servers — оба DNS (remote + domestic)
            val servers = buildJsonArray {
                if (profile.remoteDnsDomain.isNotBlank()) {
                    add(dnsServer(profile.remoteDnsType, profile.remoteDnsDomain))
                }
                if (profile.domesticDnsDomain.isNotBlank()) {
                    add(dnsServer(profile.domesticDnsType, profile.domesticDnsDomain))
                }
            }
            if (servers.isNotEmpty()) put("servers", servers)
            // queryStrategy
            put("queryStrategy", "UseIPv4")
            if (profile.fakeDns) {
                put("fakedns", buildJsonObject { put("ipPool", "198.18.0.0/16") })
            }
        }
    }

    private fun addRule(
        target: JsonArrayBuilder,
        sites: List<String>,
        ips: List<String>,
        outboundTag: String?,
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
            // null — «через прокси по final» (только при выключенном globalProxy).
            if (outboundTag != null) put("outboundTag", JsonPrimitive(outboundTag))
            else put("outboundTag", JsonPrimitive("proxy"))
        }
        target.add(rule)
    }

    /** Тип DNS → адрес сервера для dns.servers[]. "DoH" — домен, иначе IP. */
    private fun dnsServer(type: String, domain: String): JsonPrimitive =
        if (type.equals("DoH", ignoreCase = true)) JsonPrimitive(domain)
        else JsonPrimitive(domain.removePrefix("https://").removePrefix("http://"))
}
