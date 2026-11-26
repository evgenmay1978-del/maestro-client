package com.maestrovpn.client.core.subscription

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import com.maestrovpn.client.core.routing.RoutingProfile
import com.maestrovpn.client.core.routing.RoutingRulesBuilder
import com.maestrovpn.client.core.settings.AdvancedConfig

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

    /** Имя tun для схемы ядра: исключает перечисление интерфейсов на Android. */
    private const val TUN_NAME = "maestro0"
    private const val TUN_INBOUND_TAG = "tun-in"
    private const val DNS_OUTBOUND_TAG = "dns"
    private const val API_PORT = 10085
    private const val PROBE_URL = "https://www.gstatic.com/generate_204"
    private const val PROBE_INTERVAL = "3m"

    // MTU tun-inbound, порты socks/http — из AdvancedConfig (issue #3);
    // дефолты совпадают с прежними константами (1280 / 10808 / 10809).

    /**
     * Протоколы, у которых Xray поддерживает mux. Для остальных (wireguard,
     * freedom и пр.) mux-блок — ошибка конфига, а не «лишнее поле».
     */
    private val MULTIPLEXABLE_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "http")

    private const val AUTO_BALANCER_TAG = "auto"
    private const val FAKE_DNS_IP_POOL = "198.18.0.0/16"
    private const val FAKE_DNS_POOL_SIZE = 65535

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
        advanced: AdvancedConfig = AdvancedConfig.DEFAULT,
    ): BuiltConfig {
        require(servers.isNotEmpty()) { "нет серверов для конфига" }
        val tags = servers.map { it.tag }
        val selected = selectedTag?.takeIf { it in tags }
        val auto = selected == null
        // Куда идёт всё, что не попало в правила профиля. Без этого ядро
        // кидало трафик в первый outbound, а поле routing.final, которое
        // стояло тут раньше, ядро игнорирует (проверено на 26.11.9).
        val catchAll = if (auto) {
            RoutingRulesBuilder.CatchAll(balancerTag = AUTO_BALANCER_TAG)
        } else {
            RoutingRulesBuilder.CatchAll(outboundTag = selected)
        }

        val config = buildJsonObject {
            put("log", buildJsonObject {
                put("loglevel", "warning")
                put("access", "none")
            })
            // DNS профиля приоритетнее: он задаёт split-DNS для маршрутизации.
            // Без профиля — адреса и стратегия из продвинутых настроек.
            put("dns", routingProfile?.let { RoutingRulesBuilder.dns(it, advanced) }
                ?: buildJsonObject {
                    put("servers", buildJsonArray {
                        advanced.dnsServers().forEach { add(it) }
                    })
                    put("queryStrategy", advanced.queryStrategy)
                })
            // FakeDNS — ВЕРХНЕУРОВНЕВАЯ секция (в dns её ядро не читает:
            // проверено на бинаре 26.11.9 — "dns":{...,"fakeDns":"..."}
            // проходит -test как неизвестное поле). Без "fakedns" в
            // dns.servers и в sniffing.destOverride ядро пишет
            // Warning «Defined FakeDNS but haven't enabled FakeDNS destOverride».
            if (fakeDnsEnabled(routingProfile)) {
                put("fakeDns", buildJsonObject {
                    put("ipPool", FAKE_DNS_IP_POOL)
                    put("poolSize", FAKE_DNS_POOL_SIZE)
                })
            }
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
                        // Поля — строго по схеме собранного бинаря
                        // (Xray 26.11.9, infra/conf/tun.go: name, desc, mtu,
                        // gateway, dns, userLevel, autoSystemRoutingTable, ...).
                        // Без "name" ядро перечисляет системные интерфейсы
                        // (GetAvailableTunName -> net.Interfaces) и на Android
                        // падает: netlink приложению запрещён, отсюда
                        // "fail to get system interface information".
                        // Настоящее имя туннеля ядро узнаёт у fd через
                        // TUNGETIFF, поэтому "name" здесь — только чтобы
                        // обойти перечисление. Адрес и MTU туннеля ставит
                        // VpnService.Builder; полю "address" ядро не знает
                        // (игнорирует) — поэтому его тут нет.
                        put("settings", buildJsonObject {
                            put("name", TUN_NAME)
                            put("mtu", advanced.tunMtu)
                        })
                        put("sniffing", sniffing(fakeDnsEnabled(routingProfile)))
                    })
                } else {
                    add(buildJsonObject {
                        put("tag", "socks-in")
                        put("listen", "127.0.0.1")
                        put("port", advanced.socksPort)
                        put("protocol", "socks")
                        put("settings", buildJsonObject {
                            put("auth", "noauth")
                            put("udp", true)
                        })
                        put("sniffing", sniffing(fakeDnsEnabled(routingProfile)))
                    })
                    add(buildJsonObject {
                        put("tag", "http-in")
                        put("listen", "127.0.0.1")
                        put("port", advanced.httpPort)
                        put("protocol", "http")
                        put("settings", buildJsonObject {
                            put("auth", "noauth")
                        })
                        put("sniffing", sniffing(fakeDnsEnabled(routingProfile)))
                    })
                }
            })
            put("outbounds", buildJsonArray {
                for (server in servers) {
                    // Xray outbound как есть, tag — наш (уникальный в подписке).
                    val outbound = JsonObject(server.xrayOutbound + ("tag" to JsonPrimitive(server.tag)))
                    add(withMux(outbound, advanced))
                }
                // DNS-форвардер: сюда уходят запросы порта 53 из tun
                // (правило dnsInterceptRule). Иначе они ехали как обычный
                // UDP через прокси и не доходили — сайты не открывались.
                add(buildJsonObject {
                    put("tag", DNS_OUTBOUND_TAG)
                    put("protocol", "dns")
                    put("settings", buildJsonObject {
                        put("network", "udp")
                        put("address", dnsForwardAddress(routingProfile, advanced))
                        put("port", 53)
                    })
                })
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
                    // 0. DNS из туннеля — в DNS-форвардер, а не в прокси.
                    if (vpnMode) add(RoutingRulesBuilder.dnsInterceptRule(TUN_INBOUND_TAG, DNS_OUTBOUND_TAG))
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
                        RoutingRulesBuilder.rules(profile, catchAll).forEach { add(it) }
                    }
                    // Замыкающее правило — строго последним: всё остальное
                    // в выбранный сервер (или в балансировщик auto).
                    add(RoutingRulesBuilder.catchAllRule(catchAll))
                })
                // Balancer — не outbound, а секция routing (документация Xray):
                // только когда выбран "auto".
                if (auto) {
                    put("balancers", buildJsonArray {
                        add(buildJsonObject {
                            put("tag", AUTO_BALANCER_TAG)
                            put("selector", buildJsonArray {
                                tags.forEach { add(it) }
                            })
                            put("strategy", buildJsonObject {
                                put("type", "leastPing")
                            })
                        })
                    })
                }
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

    /**
     * sniffing с destOverride. При FakeDNS "fakedns" обязателен в
     * destOverride: без него ядро не подставляет домен вместо
     * синтетического адреса и маршрутизация по доменам ломается
     * (ядро ругается Warning'ом, проверено на 26.11.9).
     */
    private fun sniffing(fakeDns: Boolean) = buildJsonObject {
        put("enabled", true)
        put("destOverride", buildJsonArray {
            add("http")
            add("tls")
            add("quic")
            if (fakeDns) add("fakedns")
        })
        // routeOnly намеренно не ставим: destOverride нужен и для выбора
        // маршрута по домену (domainStrategy IPIfNonMatch), и для FakeDNS.
    }

    private fun fakeDnsEnabled(profile: RoutingProfile?): Boolean = profile?.fakeDns == true

    /**
     * Адрес, куда dns-форвардер отправляет запросы: IP из профиля, иначе
     * первичный DNS из продвинутых настроек.
     */
    private fun dnsForwardAddress(profile: RoutingProfile?, advanced: AdvancedConfig): String {
        val fromProfile = profile?.remoteDnsIp?.trim().orEmpty()
        return if (fromProfile.isNotEmpty()) fromProfile else advanced.remoteDnsPrimary
    }

    /**
     * Добавляет mux-блок к outbound, если он включён и протокол это умеет.
     * VLESS + XTLS Vision с mux не работает (Xray отвергает такой конфиг),
     * поэтому Vision-outbound'ы mux не получают — иначе включённый в настройках
     * mux ломал бы всю подписку целиком.
     */
    private fun withMux(outbound: JsonObject, advanced: AdvancedConfig): JsonObject {
        if (!advanced.muxEnabled) return outbound
        val protocol = (outbound["protocol"] as? JsonPrimitive)?.contentOrNull?.lowercase()
        if (protocol == null || protocol !in MULTIPLEXABLE_PROTOCOLS) return outbound
        if (hasVisionFlow(outbound)) return outbound
        return JsonObject(
            outbound + (
                "mux" to buildJsonObject {
                    put("enabled", true)
                    put("concurrency", advanced.muxConcurrency)
                    put("xudpConcurrency", advanced.muxXudpConcurrency)
                    put("xudpProxyUDP443", advanced.muxXudpBehavior)
                }
                ),
        )
    }

    /**
     * VLESS-настройка flow = xtls-rprx-vision: mux с ней несовместим.
     * Реальная форма outbound у парсера — settings.vnext[].users[].flow
     * (плоская settings.vless проверяется на случай ручного конфига).
     */
    private fun hasVisionFlow(outbound: JsonObject): Boolean {
        val settings = outbound["settings"] as? JsonObject ?: return false
        (settings["vnext"] as? JsonArray)?.forEach { endpoint ->
            val users = (endpoint as? JsonObject)?.get("users") as? JsonArray ?: return@forEach
            users.forEach { user ->
                val flow = ((user as? JsonObject)?.get("flow") as? JsonPrimitive)?.contentOrNull
                if (flow != null && flow.contains("vision", ignoreCase = true)) return true
            }
        }
        val flat = (settings["vless"] as? JsonObject)?.get("flow") as? JsonPrimitive
        return flat?.contentOrNull?.contains("vision", ignoreCase = true) == true
    }
}