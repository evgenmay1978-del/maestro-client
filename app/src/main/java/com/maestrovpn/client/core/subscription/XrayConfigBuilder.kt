package com.maestrovpn.client.core.subscription

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import com.maestrovpn.client.core.routing.RoutingProfile
import com.maestrovpn.client.core.routing.RoutingRulesBuilder

// Строитель полного Xray-конфига из распарсенных серверов (срез 7).
// Чистый Kotlin, без Android API — проверяется на обычной JVM
// (см. отчёт среза 7) и валидируется настоящим `xray run -test`.
//
// Единственное ядро клиента — Xray-core. Форматы — по документации Xray
// (xtls.github.io): tun inbound, socks/http inbounds, balancer +
// observatory под routing, stats/api/policy.
//
// Без geoip/geosite: приватные диапазоны — только явные CIDR.
// Без remote-правил: конфиг офлайн-безопасен, ничего не тянется из сети.
//
// MTU tun-inbound — 1280: измеренный урок из Maestro — на мобильных сетях
// путь имеет MTU около 1280, а 1500 без MSS-clamping молча роняет трафик;
// VpnService.Builder тоже ставит MTU 1280, значения должны совпадать.
object XrayConfigBuilder {
    data class BuiltConfig(
        val configJson: String,
        val vpnMode: Boolean,
    )

    private const val TUN_ADDRESS = "172.19.0.1/30"
    private const val TUN_MTU = 1280
    private const val API_PORT = 10085
    private const val SOCKS_PORT = 10808
    private const val HTTP_PORT = 10809
    private const val PROBE_URL = "https://www.gstatic.com/generate_204"
    private const val PROBE_INTERVAL = "3m"

    /** Приватные диапазоны — всегда напрямую, без прокси. */
    private val PRIVATE_CIDRS = listOf(
        "10.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "127.0.0.0/8",
        "::1/128",
        "fc00::/7",
        "fe80::/10",
    )

    fun build(
        servers: List<ParsedServer>,
        selectedTag: String?,
        vpnMode: Boolean = true,
        routingProfile: RoutingProfile? = null,
    ): BuiltConfig {
        require(servers.isNotEmpty()) { "нет серверов для конфига" }
        val tags = servers.map { it.tag }
        val selected = selectedTag?.takeIf { it in tags }
        val auto = selected == null
        val finalTag = selected ?: "auto"

        val config = buildJsonObject {
            put("log", buildJsonObject {
                put("loglevel", "warning")
                put("access", "none")
            })
            put("dns", routingProfile?.let { RoutingRulesBuilder.dns(it) } ?: buildJsonObject {
                put("servers", buildJsonArray {
                    add("1.1.1.1")
                    add("8.8.8.8")
                })
                put("queryStrategy", "UseIPv4")
            })
            put("stats", buildJsonObject {})
            put("policy", buildJsonObject {
                put("levels", buildJsonObject {
                    put("0", buildJsonObject {
                        put("statsUserUplink", true)
                        put("statsUserDownlink", true)
                    })
                })
                put("system", buildJsonObject {
                    put("statsInboundUplink", true)
                    put("statsInboundDownlink", true)
                    put("statsOutboundUplink", true)
                    put("statsOutboundDownlink", true)
                })
            })
            put("api", buildJsonObject {
                put("tag", "api")
                // HandlerService обязателен: без него Stats API не отвечает;
                // LoggerService — для полноты API (проверено на Xray 26.11.9).
                put("services", buildJsonArray {
                    add("HandlerService")
                    add("LoggerService")
                    add("StatsService")
                })
            })
            put("inbounds", buildJsonArray {
                // gRPC API для статистики (StatsService).
                add(buildJsonObject {
                    put("tag", "api")
                    put("listen", "127.0.0.1")
                    put("port", API_PORT)
                    put("protocol", "dokodemo-door")
                    put("settings", buildJsonObject {
                        put("address", "127.0.0.1")
                    })
                })
                if (vpnMode) {
                    add(buildJsonObject {
                        put("tag", "tun-in")
                        put("protocol", "tun")
                        put("settings", buildJsonObject {
                            put("address", TUN_ADDRESS)
                            put("mtu", TUN_MTU)
                            put("strictRoute", true)
                        })
                        put("sniffing", buildJsonObject {
                            put("enabled", true)
                            put("destOverride", buildJsonArray {
                                add("http")
                                add("tls")
                                add("quic")
                            })
                        })
                    })
                } else {
                    add(buildJsonObject {
                        put("tag", "socks-in")
                        put("listen", "127.0.0.1")
                        put("port", SOCKS_PORT)
                        put("protocol", "socks")
                        put("settings", buildJsonObject {
                            put("auth", "noauth")
                            put("udp", true)
                        })
                        put("sniffing", buildJsonObject {
                            put("enabled", true)
                            put("destOverride", buildJsonArray {
                                add("http")
                                add("tls")
                                add("quic")
                            })
                        })
                    })
                    add(buildJsonObject {
                        put("tag", "http-in")
                        put("listen", "127.0.0.1")
                        put("port", HTTP_PORT)
                        put("protocol", "http")
                        put("settings", buildJsonObject {
                            put("auth", "noauth")
                        })
                        put("sniffing", buildJsonObject {
                            put("enabled", true)
                            put("destOverride", buildJsonArray {
                                add("http")
                                add("tls")
                                add("quic")
                            })
                        })
                    })
                }
            })
            put("outbounds", buildJsonArray {
                for (server in servers) {
                    // Xray outbound как есть, tag — наш (уникальный в подписке).
                    add(JsonObject(server.xrayOutbound + ("tag" to JsonPrimitive(server.tag))))
                }
                add(buildJsonObject {
                    put("tag", "direct")
                    put("protocol", "freedom")
                })
                add(buildJsonObject {
                    put("tag", "block")
                    put("protocol", "blackhole")
                })
            })
            put("routing", buildJsonObject {
                put("domainStrategy", routingProfile?.domainStrategy ?: "IPIfNonMatch")
                put("rules", buildJsonArray {
                    // API-запросы — в API-обработчик, а не в прокси.
                    add(buildJsonObject {
                        put("type", "field")
                        put("inboundTag", buildJsonArray { add("api") })
                        put("outboundTag", "api")
                    })
                    // Приватные диапазоны — всегда напрямую (и без профиля).
                    add(buildJsonObject {
                        put("type", "field")
                        put("ip", buildJsonArray {
                            PRIVATE_CIDRS.forEach { add(it) }
                        })
                        put("outboundTag", "direct")
                    })
                    // Правила профиля: block -> proxy -> direct.
                    routingProfile?.let { profile ->
                        RoutingRulesBuilder.rules(profile).forEach { add(it) }
                    }
                })
                // Balancer — не outbound, а секция routing (документация Xray):
                // только когда выбран "auto".
                if (auto) {
                    put("balancers", buildJsonArray {
                        add(buildJsonObject {
                            put("tag", "auto")
                            put("selector", buildJsonArray {
                                tags.forEach { add(it) }
                            })
                            put("strategy", buildJsonObject {
                                put("type", "leastPing")
                            })
                        })
                    })
                }
                put("final", finalTag)
            })
            // Observatory — пробы для leastPing, только в режиме auto.
            if (auto) {
                put("observatory", buildJsonObject {
                    put("subjectSelector", buildJsonArray {
                        tags.forEach { add(it) }
                    })
                    put("probeUrl", PROBE_URL)
                    put("probeInterval", PROBE_INTERVAL)
                })
            }
        }
        return BuiltConfig(configJson = config.toString(), vpnMode = vpnMode)
    }
}
