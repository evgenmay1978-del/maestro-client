package com.maestrovpn.client.core.routing

import kotlinx.serialization.Serializable

/**
 * Профиль маршрутизации (раздел 7, issue #2).
 *
 * Поля — по живому образцу профиля панели (без выдумывания):
 * name, direct/proxy/block по сайтам и IP, DNS (remote/domestic), dnsHosts,
 * гео-URL'ы, domainStrategy, fakeDNS, globalProxy.
 */
@Serializable
data class RoutingProfile(
    val id: String,
    val name: String,
    /** null — профиль общий; иначе свой набор правил этой подписки. */
    val subscriptionId: String? = null,
    /** true — весь трафик через прокси, кроме прямых правил. */
    val globalProxy: Boolean = true,
    val directSites: List<String> = emptyList(),
    val directIp: List<String> = emptyList(),
    val proxySites: List<String> = emptyList(),
    val proxyIp: List<String> = emptyList(),
    val blockSites: List<String> = emptyList(),
    val blockIp: List<String> = emptyList(),
    /** "IPIfNonMatch" | "AsIs" | "IPOnDemand". */
    val domainStrategy: String = DOMAIN_STRATEGY_IP_IF_NON_MATCH,
    /** "DoH" | "DoU" | "UDP" — тип удалённого DNS. */
    val remoteDnsType: String = "DoH",
    val remoteDnsDomain: String = "https://cloudflare-dns.com/dns-query",
    val remoteDnsIp: String = "1.1.1.1",
    val domesticDnsType: String = "DoH",
    val domesticDnsDomain: String = "https://dns.google/dns-query",
    val domesticDnsIp: String = "8.8.8.8",
    val dnsHosts: Map<String, String> = emptyMap(),
    /** URL geo-баз; пусто — правила geosite:/geoip: не работать. */
    val geoipUrl: String = "",
    val geositeUrl: String = "",
    val fakeDns: Boolean = false,
    /** Время последнего обновления (мс). */
    val lastUpdated: Long = 0L,
) {
    /** Пустой профиль — модель по умолчанию для UI-редактора. */
    companion object {
        const val DOMAIN_STRATEGY_IP_IF_NON_MATCH = "IPIfNonMatch"
        const val DOMAIN_STRATEGY_AS_IS = "AsIs"
        const val DOMAIN_STRATEGY_IP_ON_DEMAND = "IPOnDemand"

        fun empty(id: String): RoutingProfile =
            RoutingProfile(id = id, name = "Новый профиль")
    }
}
