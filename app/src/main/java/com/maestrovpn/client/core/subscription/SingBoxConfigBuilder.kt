package com.maestrovpn.client.core.subscription

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// Строитель полного sing-box конфига из распарсенных серверов (срез 3).
// Чистый Kotlin, без Android API — проверяется `sing-box check` 1.14.4.
//
// Шаблон — как у панели Maestro (backend/internal/subgen/subgen.go), но
// минимальный и офлайн-безопасный: без remote rule-set'ов (они тянутся из сети
// при старте и роняют старт без интернета) и без fakeip. DNS — новый формат
// sing-box 1.12+ (legacy-формат в 1.14 удалён, проверено `sing-box check`).
// Патчи BoxService (TunStack gvisor, TunIpv6, Sniffing, DIRECT-правила для
// xhttp) применяются поверх уже при запуске — билдер их не дублирует.
//
// Xray-узлы (xhttp): в конфиг идёт SOCKS-стаб 127.0.0.1:20801+i, а параметры
// раннера — в манифест subxhttp-nodes.json (формат SubXhttpRuntime.parse).
// Порты 20801+ — диапазон SubXhttpPlan.PORT_MIN..PORT_MAX, та же нумерация.
object SingBoxConfigBuilder {
    const val RUNNER_PORT_MIN = 20801

    data class BuiltConfig(
        val configJson: String,
        /** Манифест раннера (subxhttp-nodes.json) или null, если xhttp-узлов нет. */
        val manifestJson: String?,
        val runnerTags: List<String>,
    )

    fun build(servers: List<ParsedServer>, selectedTag: String?): BuiltConfig {
        require(servers.isNotEmpty()) { "нет серверов для конфига" }
        val tags = servers.map { it.tag }
        val selected = selectedTag?.takeIf { it in tags } ?: "auto"
        val runnerPorts = LinkedHashMap<String, Int>()
        var nextPort = RUNNER_PORT_MIN
        for (server in servers) {
            if (server.kind == ServerKind.XHTTP_RUNNER) {
                runnerPorts[server.tag] = nextPort++
            }
        }
        val config = buildJsonObject {
            put("log", buildJsonObject { put("level", "warn") })
            put("dns", buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "tls")
                        put("tag", "google")
                        put("server", "8.8.8.8")
                        put("detour", "select")
                    })
                    add(buildJsonObject {
                        put("type", "local")
                        put("tag", "local")
                        put("detour", "direct")
                    })
                })
                put("final", "google")
            })
            put("inbounds", buildJsonArray {
                add(buildJsonObject {
                    put("type", "tun")
                    put("tag", "tun-in")
                    put("address", buildJsonArray { add("172.19.0.1/30") })
                    put("auto_route", true)
                    put("strict_route", true)
                    put("stack", "system")
                    put("endpoint_independent_nat", true)
                })
            })
            put("outbounds", buildJsonArray {
                for (server in servers) {
                    when (server.kind) {
                        ServerKind.NATIVE -> {
                            val outbound = server.singBoxOutbound
                                ?: continue
                            add(JsonObject(outbound + ("tag" to JsonPrimitive(server.tag))))
                        }
                        ServerKind.XHTTP_RUNNER -> {
                            val port = runnerPorts.getValue(server.tag)
                            add(buildJsonObject {
                                put("type", "socks")
                                put("tag", server.tag)
                                put("server", "127.0.0.1")
                                put("server_port", port)
                                put("version", "5")
                            })
                        }
                    }
                }
                add(buildJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                })
                add(buildJsonObject {
                    put("type", "block")
                    put("tag", "block")
                })
                add(buildJsonObject {
                    put("type", "urltest")
                    put("tag", "auto")
                    put("outbounds", buildJsonArray {
                        tags.forEach { add(it) }
                    })
                    put("url", "https://www.gstatic.com/generate_204")
                    put("interval", "3m")
                    put("tolerance", 50)
                })
                add(buildJsonObject {
                    put("type", "selector")
                    put("tag", "select")
                    put("outbounds", buildJsonArray {
                        add("auto")
                        tags.forEach { add(it) }
                    })
                    put("default", selected)
                })
            })
            // WireGuard в sing-box 1.14 — endpoint, не outbound (срез 5):
            // endpoint несёт свой tag и выбирается селектором по тому же тегу.
            // Подписки endpoint'ов не дают (парсер отклоняет WireGuard), так что
            // для пути подписок этот блок всегда пуст.
            val endpoints = servers.mapNotNull { it.singBoxEndpoint }
            if (endpoints.isNotEmpty()) {
                put("endpoints", buildJsonArray {
                    endpoints.forEach { add(it) }
                })
            }
            put("route", buildJsonObject {
                put("final", "select")
                put("auto_detect_interface", true)
                put("default_domain_resolver", "local")
            })
        }
        val manifest = if (runnerPorts.isEmpty()) {
            null
        } else {
            buildJsonObject {
                put("nodes", buildJsonArray {
                    for (server in servers) {
                        if (server.kind != ServerKind.XHTTP_RUNNER) continue
                        val outbound = server.xrayOutbound ?: continue
                        add(buildJsonObject {
                            put("tag", server.tag)
                            put("label", server.label)
                            put("socksPort", runnerPorts.getValue(server.tag))
                            put("address", server.address)
                            put("outbound", outbound)
                        })
                    }
                })
            }.toString()
        }
        return BuiltConfig(
            configJson = config.toString(),
            manifestJson = manifest,
            runnerTags = runnerPorts.keys.toList(),
        )
    }
}
