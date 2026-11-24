package com.maestrovpn.client.core.subscription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder

// Парсер подписок (срез 3, переведён на Xray-only ядро в срезе 7).
// Чистый Kotlin, без Android API — проверяется на обычной JVM
// (см. отчёт среза 7) и валидируется настоящим `xray run -test`.
//
// Единственное ядро клиента — Xray-core. Парсер отдаёт готовые Xray
// outbound'ы как есть, без конвертации в другой формат:
//  - полный Xray JSON: массив конфигов (так отдаёт панель в ?format=xray —
//    WhiteListCombinedXrayJSONSubscription) и одиночный конфиг;
//  - share-ссылки: vless / vmess / trojan / ss / socks / hysteria2;
//  - base64-списки ссылок (как отдаёт панель в ?format=links);
//  - смешанный формат: ссылки + строки маршрутизации/метаданные (не-ссылки
//    пропускаются, считаются в ignoredLines);
//  - готовый JSON конфиг другого ядра — только ОПРЕДЕЛЯЕТСЯ как формат
//    SING_BOX_CONFIG (поле singBoxConfig); исполнять его нечем — движок
//    отклоняет такие подписки позже (не часть парсера).
// Что НЕ понимается (честно, с причиной в issues):
//  - WireGuard-ссылки и TUIC-ссылки: Xray-core не реализует TUIC ни в одной
//    версии, а WireGuard-ссылки не несут полных параметров Xray wireguard
//    outbound'а — обе отклоняются с явной причиной;
//  - kcp-транспорт в ссылках, TCP HTTP-заголовок, плагины Shadowsocks,
//    шифрованные подписки crypt/crypt5.
// В сообщениях об ошибках НИКОГДА не печатаются полные ссылки/токены/пароли —
// только схема + подпись + причина.
data class ParsedServer(
    val tag: String,
    val label: String,
    val protocol: String,
    /** Хост точки входа (без учётных данных) — для подписи и диагностики. */
    val address: String,
    /** Готовый Xray outbound (tag добавит XrayConfigBuilder). */
    val xrayOutbound: JsonObject,
    /** Транспорт: "tcp" | "ws" | "grpc" | "httpupgrade" | "xhttp" | "kcp" |
     *  "hysteria" | "wireguard" | ... — для подписи "· xhttp" в UI. */
    val network: String,
)

data class ParseIssue(
    /** Подсказка без секретов: схема + подпись, усечённая до 80 символов. */
    val hint: String,
    val reason: String,
)

enum class SubscriptionFormat {
    SING_BOX_CONFIG,
    XRAY,
    LINKS,
    BASE64_LINKS,
    EMPTY,
    UNKNOWN,
}

data class ParsedSubscription(
    val format: SubscriptionFormat,
    val servers: List<ParsedServer>,
    val issues: List<ParseIssue>,
    val ignoredLines: Int,
    /** Готовый конфиг другого ядра, только при format == SING_BOX_CONFIG. */
    val singBoxConfig: String? = null,
)

object SubscriptionParser {
    private const val MAX_SERVERS = 512
    private const val MAX_INPUT_BYTES = 8 * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(content: String): ParsedSubscription {
        val trimmed = content.trim()
        if (trimmed.isEmpty()) {
            return ParsedSubscription(SubscriptionFormat.EMPTY, emptyList(), emptyList(), 0)
        }
        if (trimmed.length > MAX_INPUT_BYTES) {
            return ParsedSubscription(
                SubscriptionFormat.UNKNOWN, emptyList(),
                listOf(ParseIssue("подписка", "тело подписки больше 8 МБ")), 0,
            )
        }
        if (trimmed.startsWith("{")) {
            return parseJsonObject(trimmed)
        }
        if (trimmed.startsWith("[")) {
            return parseJsonArray(trimmed)
        }
        // Base64-список ссылок (панель ?format=links): одна строка без пробелов.
        val decoded = decodeBase64Loose(trimmed)
        if (decoded != null) {
            val text = runCatching { String(decoded, Charsets.UTF_8) }.getOrNull()
            if (text != null && text.contains("://") && isValidUtf8(decoded)) {
                val parsed = parseLines(text)
                if (parsed.servers.isNotEmpty() || parsed.issues.isNotEmpty()) {
                    return parsed.copy(format = SubscriptionFormat.BASE64_LINKS)
                }
            }
        }
        return parseLines(trimmed)
    }

    // ---------- JSON ----------

    private fun parseJsonObject(text: String): ParsedSubscription {
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return ParsedSubscription(
                SubscriptionFormat.UNKNOWN, emptyList(),
                listOf(ParseIssue("JSON", "не удалось разобрать JSON-объект")), 0,
            )
        val outbounds = root["outbounds"]?.jsonArray
        if (outbounds == null) {
            return ParsedSubscription(
                SubscriptionFormat.UNKNOWN, emptyList(),
                listOf(ParseIssue("JSON", "в объекте нет outbounds — это не конфиг подписки")), 0,
            )
        }
        val first = outbounds.firstOrNull() as? JsonObject
        // Xray outbound узнаётся по "protocol"/"streamSettings",
        // конфиг другого ядра — по "type".
        val isXray = first != null &&
            (first.containsKey("streamSettings") || first.containsKey("protocol"))
        return if (isXray) {
            val (server, issue) = parseXrayConfig(root, TagScope())
            if (server != null) {
                ParsedSubscription(SubscriptionFormat.XRAY, listOf(server), emptyList(), 0)
            } else {
                ParsedSubscription(
                    SubscriptionFormat.XRAY, emptyList(),
                    listOf(issue ?: ParseIssue("Xray", "пустой конфиг")), 0,
                )
            }
        } else {
            ParsedSubscription(
                SubscriptionFormat.SING_BOX_CONFIG, emptyList(), emptyList(), 0,
                singBoxConfig = text,
            )
        }
    }

    private fun parseJsonArray(text: String): ParsedSubscription {
        val array = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull()
            ?: return ParsedSubscription(
                SubscriptionFormat.UNKNOWN, emptyList(),
                listOf(ParseIssue("JSON", "не удалось разобрать JSON-массив")), 0,
            )
        val servers = ArrayList<ParsedServer>()
        val issues = ArrayList<ParseIssue>()
        val tags = TagScope()
        for ((index, element) in array.withIndex()) {
            if (servers.size >= MAX_SERVERS) break
            val obj = element as? JsonObject
            if (obj == null) {
                issues += ParseIssue("элемент #${index + 1}", "не объект")
                continue
            }
            val (server, issue) = parseXrayConfig(obj, tags)
            if (server != null) servers += server
            else issues += issue ?: ParseIssue("элемент #${index + 1}", "не Xray-конфиг")
        }
        return ParsedSubscription(
            format = SubscriptionFormat.XRAY,
            servers = servers, issues = issues, ignoredLines = 0,
        )
    }

    // ---------- Xray-конфиг (passthrough, без конвертации) ----------

    /** Протоколы, которые считаются прокси-узлами (остальные — freedom/block/dns — не узлы). */
    private val PROXY_PROTOCOLS = setOf(
        "vless", "vmess", "trojan", "shadowsocks", "socks", "http", "hysteria", "wireguard",
    )

    /** Разбирает один полный Xray-конфиг (элемент массива ?format=xray). */
    private fun parseXrayConfig(
        root: JsonObject,
        tags: TagScope,
    ): Pair<ParsedServer?, ParseIssue?> {
        val remarks = (root["remarks"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val outbounds = (root["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }
            .orEmpty()
        val proxy = outbounds.firstOrNull { outbound ->
            val protocol = (outbound["protocol"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                .lowercase()
            protocol in PROXY_PROTOCOLS
        } ?: return null to ParseIssue(
            hint = remarks.ifBlank { "Xray" }.take(80),
            reason = "в конфиге нет прокси-outbound (vless/vmess/trojan/ss/socks/hysteria/wireguard)",
        )
        return parseXrayOutbound(proxy, remarks, tags)
    }

    /**
     * Xray outbound как есть: passthrough для ВСЕХ сетей, включая xhttp —
     * ни одно поле не переписывается (tag проставит XrayConfigBuilder).
     */
    private fun parseXrayOutbound(
        outbound: JsonObject,
        remarks: String,
        tags: TagScope,
    ): Pair<ParsedServer?, ParseIssue?> {
        val protocol = (outbound["protocol"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
        val stream = outbound["streamSettings"] as? JsonObject
        val network = (stream?.get("network") as? JsonPrimitive)?.contentOrNull
            ?.lowercase().orEmpty().ifBlank {
                // У протоколов без streamSettings сеть — сам протокол.
                when (protocol) {
                    "hysteria" -> "hysteria"
                    "wireguard" -> "wireguard"
                    else -> "tcp"
                }
            }
        val label = remarks.ifBlank {
            (outbound["tag"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        }.ifBlank { "server" }
        val hint = label.take(80)
        val address = xrayAddress(protocol, outbound["settings"] as? JsonObject).orEmpty()
        if (address.isEmpty()) {
            return null to ParseIssue(hint, "узел без адреса")
        }
        val tag = tags.unique(label)
        return ParsedServer(
            tag = tag, label = label, protocol = protocol,
            address = address, xrayOutbound = outbound, network = network,
        ) to null
    }

    private fun xrayAddress(protocol: String, settings: JsonObject?): String? {
        if (settings == null) return null
        val vnext = (settings["vnext"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (vnext != null) return (vnext["address"] as? JsonPrimitive)?.contentOrNull
        val servers = (settings["servers"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (servers != null) return (servers["address"] as? JsonPrimitive)?.contentOrNull
        if (protocol == "hysteria") {
            return (settings["address"] as? JsonPrimitive)?.contentOrNull
        }
        if (protocol == "wireguard") {
            val peer = (settings["peers"] as? JsonArray)?.firstOrNull() as? JsonObject
            val endpoint = (peer?.get("endpoint") as? JsonPrimitive)?.contentOrNull.orEmpty()
            // endpoint — "host:port" (или "[v6]:port").
            return endpoint.substringBeforeLast(":").trim('[', ']')
                .ifEmpty { null }
        }
        return null
    }

    private val UTLS_FINGERPRINTS = setOf(
        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq",
        "random", "randomized",
    )

    // ---------- Текстовые строки (share-ссылки -> Xray outbound напрямую) ----------

    private fun parseLines(text: String): ParsedSubscription {
        val servers = ArrayList<ParsedServer>()
        val issues = ArrayList<ParseIssue>()
        var ignored = 0
        val tags = TagScope()
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (servers.size >= MAX_SERVERS) break
            val scheme = line.substringBefore("://", "").lowercase()
            if (scheme.isEmpty() || !line.contains("://")) {
                ignored++
                continue
            }
            when (scheme) {
                "vless", "vmess", "trojan", "ss", "socks", "socks5",
                "hysteria2", "hy2", "tuic", "wireguard", "wg",
                -> {
                    val (server, issue) = parseShareLink(line, tags)
                    if (server != null) servers += server
                    else if (issue != null) issues += issue
                }
                else -> {
                    issues += ParseIssue(
                        hint = "$scheme://…",
                        reason = "неизвестный протокол",
                    )
                }
            }
        }
        val format = if (servers.isEmpty() && issues.isEmpty()) {
            SubscriptionFormat.EMPTY
        } else {
            SubscriptionFormat.LINKS
        }
        return ParsedSubscription(format, servers, issues, ignored)
    }

    private fun parseShareLink(line: String, tags: TagScope): Pair<ParsedServer?, ParseIssue?> {
        val scheme = line.substringBefore("://").lowercase()
        val hint = scheme + "://…"
        return when (scheme) {
            "wireguard", "wg" -> null to ParseIssue(
                hint, "WireGuard-ссылки не поддерживаются: в ссылке нет полных " +
                    "параметров Xray wireguard outbound'а (ключи, адреса, пиры)",
            )
            "vmess" -> parseVmessLink(line, tags)
            else -> {
                val uri = runCatching { URI(line) }.getOrNull()
                    ?: return null to ParseIssue(hint, "битая ссылка")
                parseUriLink(uri, scheme, line, tags)
            }
        }
    }

    private fun parseUriLink(
        uri: URI,
        scheme: String,
        line: String,
        tags: TagScope,
    ): Pair<ParsedServer?, ParseIssue?> {
        val hint = scheme + "://…"
        val query = parseQuery(uri.rawQuery)
        val label = uri.fragment?.let { urlDecode(it) }.orEmpty()
            .ifBlank { "${uri.host ?: ""}:${uri.port}" }
        val tag = tags.unique(label.ifBlank { scheme })
        val host = uri.host.orEmpty()
        if (host.isEmpty()) return null to ParseIssue(hint, "нет хоста")
        val port = if (uri.port > 0) uri.port else defaultPort(scheme)
        if (port <= 0 || port > 65535) return null to ParseIssue(hint, "неверный порт")
        return when (scheme) {
            "vless" -> parseVlessUri(uri, query, tag, label, host, port)
            "trojan" -> parseTrojanUri(uri, query, tag, label, host, port)
            "ss" -> parseSsUri(uri, line, tag, label, host, port)
            "socks", "socks5" -> parseSocksUri(uri, tag, label, host, port)
            "hysteria2", "hy2" -> parseHy2Uri(uri, query, tag, label, host, port)
            "tuic" -> parseTuicUri(uri, query, tag, label, host, port)
            else -> null to ParseIssue(hint, "неизвестный протокол")
        }
    }

    private fun defaultPort(scheme: String): Int = when (scheme) {
        "vless", "vmess", "trojan" -> 443
        "ss", "socks", "socks5" -> 8388
        "hysteria2", "hy2", "tuic" -> 443
        else -> -1
    }

    private fun parseVlessUri(
        uri: URI,
        query: Map<String, String>,
        tag: String,
        label: String,
        host: String,
        port: Int,
    ): Pair<ParsedServer?, ParseIssue?> {
        val hint = "vless://…"
        val uuid = uri.userInfo.orEmpty().substringBefore(":").let { urlDecode(it) }
        if (uuid.isEmpty()) return null to ParseIssue(hint, "нет UUID")
        val security = (query["security"] ?: "none").lowercase()
        val type = (query["type"] ?: "tcp").lowercase()
        val encryption = (query["encryption"] ?: "none").lowercase()
        if (encryption != "none") {
            return null to ParseIssue(hint, "encryption=$encryption не поддерживается")
        }
        val settings = buildJsonObject {
            put("vnext", buildJsonArray {
                add(buildJsonObject {
                    put("address", host)
                    put("port", port)
                    put("users", buildJsonArray {
                        add(buildJsonObject {
                            put("id", uuid)
                            put("encryption", "none")
                            val flow = query["flow"].orEmpty()
                            if (flow.isNotEmpty()) put("flow", flow)
                        })
                    })
                })
            })
        }
        val outbound = buildLinkOutbound(
            protocol = "vless", settings = settings,
            network = type, security = security, query = query, host = host,
        ) ?: return null to ParseIssue(hint, "не поддерживается: vless/$type/$security")
        return ParsedServer(
            tag = tag, label = label, protocol = "vless",
            address = host, xrayOutbound = outbound, network = type,
        ) to null
    }

    private fun parseTrojanUri(
        uri: URI,
        query: Map<String, String>,
        tag: String,
        label: String,
        host: String,
        port: Int,
    ): Pair<ParsedServer?, ParseIssue?> {
        val hint = "trojan://…"
        val password = uri.userInfo.orEmpty().substringBefore(":").let { urlDecode(it) }
        if (password.isEmpty()) return null to ParseIssue(hint, "нет пароля")
        // Trojan без TLS не работает — security ниже всегда даёт tlsSettings,
        // кроме явно заданного reality.
        val security = if ((query["security"] ?: "tls").lowercase() == "reality") {
            "reality"
        } else {
            "tls"
        }
        val type = (query["type"] ?: "tcp").lowercase()
        val settings = buildJsonObject {
            put("servers", buildJsonArray {
                add(buildJsonObject {
                    put("address", host)
                    put("port", port)
                    put("password", password)
                })
            })
        }
        val outbound = buildLinkOutbound(
            protocol = "trojan", settings = settings,
            network = type, security = security, query = query, host = host,
        ) ?: return null to ParseIssue(hint, "не поддерживается: trojan/$type/$security")
        return ParsedServer(
            tag = tag, label = label, protocol = "trojan",
            address = host, xrayOutbound = outbound, network = type,
        ) to null
    }

    private fun parseSsUri(
        uri: URI,
        line: String,
        tag: String,
        label: String,
        host: String,
        port: Int,
    ): Pair<ParsedServer?, ParseIssue?> {
        val hint = "ss://…"
        // Три формы: method:password@host:port | base64(method:password)@host:port |
        // base64(method:password@host:port) — у последней URI не парсит хост.
        var method = ""
        var password = ""
        var realHost = host
        var realPort = port
        val rawUserInfo = uri.rawUserInfo.orEmpty()
        val afterScheme = line.substringAfter("://").substringBefore("#")
        val query = parseQuery(uri.rawQuery)
        if (query.containsKey("plugin")) {
            return null to ParseIssue(hint, "плагин Shadowsocks не поддерживается")
        }
        if (rawUserInfo.isNotEmpty() && rawUserInfo.contains(":")) {
            val decoded = urlDecode(rawUserInfo.substringBefore(":"))
            val parts = decoded.split(":", limit = 2)
            if (parts.size == 2) {
                method = parts[0]
                password = urlDecode(rawUserInfo.substringAfter(":"))
            }
        }
        if (method.isEmpty()) {
            // Вся часть до '@' (или до '#') — base64.
            val encoded = afterScheme.substringBefore("@")
            val decoded = decodeBase64Loose(encoded)?.let { String(it, Charsets.UTF_8) }
            if (decoded != null && decoded.contains("@")) {
                val creds = decoded.substringBefore("@")
                val hostPort = decoded.substringAfter("@")
                val cp = creds.split(":", limit = 2)
                if (cp.size == 2) {
                    method = cp[0]
                    password = cp[1]
                }
                realHost = hostPort.substringBefore(":")
                realPort = hostPort.substringAfter(":", "").toIntOrNull() ?: realPort
            } else if (decoded != null) {
                val cp = decoded.split(":", limit = 2)
                if (cp.size == 2) {
                    method = cp[0]
                    password = cp[1]
                }
            }
        }
        if (method.isEmpty() || password.isEmpty() || realHost.isEmpty()) {
            return null to ParseIssue(hint, "не удалось разобрать method:password@host")
        }
        val outbound = buildJsonObject {
            put("protocol", "shadowsocks")
            put("settings", buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject {
                        put("address", realHost)
                        put("port", realPort)
                        put("method", method)
                        put("password", password)
                    })
                })
            })
        }
        return ParsedServer(
            tag = tag, label = label, protocol = "ss",
            address = realHost, xrayOutbound = outbound, network = "tcp",
        ) to null
    }

    private fun parseSocksUri(
        uri: URI,
        tag: String,
        label: String,
        host: String,
        port: Int,
    ): Pair<ParsedServer?, ParseIssue?> {
        val hint = "socks://…"
        val userInfo = uri.userInfo.orEmpty()
        var username: String? = null
        var password: String? = null
        var realHost = host
        var realPort = port
        if (userInfo.isNotEmpty() && userInfo.contains(":")) {
            username = urlDecode(userInfo.substringBefore(":"))
            password = urlDecode(userInfo.substringAfter(":"))
        } else if (userInfo.isNotEmpty()) {
            // userInfo целиком — base64(user:pass@host:port).
            val decoded = decodeBase64Loose(userInfo)?.let { String(it, Charsets.UTF_8) }
            if (decoded != null && decoded.contains("@")) {
                val creds = decoded.substringBefore("@")
                val hostPort = decoded.substringAfter("@")
                val cp = creds.split(":", limit = 2)
                if (cp.size == 2) {
                    username = cp[0]
                    password = cp[1]
                }
                realHost = hostPort.substringBefore(":")
                realPort = hostPort.substringAfter(":", "").toIntOrNull() ?: realPort
            }
        }
        if (realHost.isEmpty()) return null to ParseIssue(hint, "нет хоста")
        val outbound = buildJsonObject {
            put("protocol", "socks")
            put("settings", buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject {
                        put("address", realHost)
                        put("port", realPort)
                        if (username != null || password != null) {
                            put("users", buildJsonArray {
                                add(buildJsonObject {
                                    if (username != null) put("user", username)
                                    if (password != null) put("pass", password)
                                })
                            })
                        }
                    })
                })
            })
        }
        return ParsedServer(
            tag = tag, label = label, protocol = "socks",
            address = realHost, xrayOutbound = outbound, network = "tcp",
        ) to null
    }

    private fun parseHy2Uri(
        uri: URI,
        query: Map<String, String>,
        tag: String,
        label: String,
        host: String,
        port: Int,
    ): Pair<ParsedServer?, ParseIssue?> {
        val hint = "hysteria2://…"
        val password = uri.userInfo.orEmpty().substringBefore(":").let { urlDecode(it) }
        if (password.isEmpty()) return null to ParseIssue(hint, "нет пароля")
        val sni = query["sni"].orEmpty().ifBlank { host }
        val insecure = query["insecure"] == "1" || query["insecure"] == "true"
        val obfs = query["obfs"].orEmpty()
        val obfsPassword = query["obfs-password"].orEmpty()
        if (obfs.isNotEmpty() && obfs != "salamander") {
            return null to ParseIssue(hint, "obfs=$obfs не поддерживается")
        }
        // up/down: "100", "100mbps", "100 mbps" -> "100mbps" (форма Xray-core).
        val up = normalizeMbps(query["up"] ?: query["upmbps"].orEmpty())
        val down = normalizeMbps(query["down"] ?: query["downmbps"].orEmpty())
        val alpn = query["alpn"].orEmpty()
        val fp = query["fp"].orEmpty()
        val outbound = buildJsonObject {
            // Xray: протокол "hysteria", version 2 — это Hysteria2.
            put("protocol", "hysteria")
            put("settings", buildJsonObject {
                put("version", 2)
                put("address", host)
                put("port", port)
            })
            put("streamSettings", buildJsonObject {
                put("network", "hysteria")
                put("security", "tls")
                put("tlsSettings", buildJsonObject {
                    put("serverName", sni)
                    // Hysteria2 — поверх QUIC/HTTP3.
                    put("alpn", buildJsonArray {
                        if (alpn.isNotEmpty()) {
                            alpn.split(",").forEach { add(it.trim()) }
                        } else {
                            add("h3")
                        }
                    })
                    if (fp.isNotEmpty() && fp in UTLS_FINGERPRINTS) put("fingerprint", fp)
                    if (insecure) put("allowInsecure", true)
                })
                put("hysteriaSettings", buildJsonObject {
                    put("version", 2)
                    put("auth", password)
                })
                // Скорости и Port Hopping — НЕ поля hysteriaSettings (Xray
                // 26.11.9 молча игнорирует неизвестные ключи там): они живут
                // в streamSettings.finalmask — brutalUp/brutalDown в
                // quicParams, udphop — первой записью в finalmask.udp.
                val finalMask = buildJsonObject {
                    if (up != null || down != null) {
                        put("quicParams", buildJsonObject {
                            if (up != null) put("brutalUp", up)
                            if (down != null) put("brutalDown", down)
                        })
                    }
                }
                if (finalMask.isNotEmpty()) put("finalmask", finalMask)
                if (obfs == "salamander" && obfsPassword.isNotEmpty()) {
                    put("udpmasks", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "salamander")
                            put("settings", buildJsonObject {
                                put("password", obfsPassword)
                            })
                        })
                    })
                }
            })
        }
        return ParsedServer(
            tag = tag, label = label, protocol = "hysteria2",
            address = host, xrayOutbound = outbound, network = "hysteria",
        ) to null
    }

    /** "100" / "100mbps" / "100 mbps" -> "100mbps", null — не число. */
    private fun normalizeMbps(text: String): String? {
        val t = text.trim().lowercase().replace(" ", "")
            .removeSuffix("mbps").removeSuffix("gbps")
        val value = t.toDoubleOrNull() ?: return null
        if (value <= 0) return null
        val mbps = if (text.trim().lowercase().replace(" ", "").endsWith("gbps")) {
            (value * 1000).toInt()
        } else {
            value.toInt()
        }
        return if (mbps in 1..100_000) "${mbps}mbps" else null
    }

    /**
     * TUIC: Xray-core не реализует протокол TUIC ни в одной версии
     * (проверено по документации и дереву Xray-core: нет proxy/tuic).
     * Ссылка распознаётся и валидируется как раньше, но отклоняется с явной
     * причиной — иначе в конфиг попал бы outbound, который Xray не знает,
     * и `xray run -test` уронил бы весь конфиг из-за одного узла.
     */
    private fun parseTuicUri(
        uri: URI,
        query: Map<String, String>,
        tag: String,
        label: String,
        host: String,
        port: Int,
    ): Pair<ParsedServer?, ParseIssue?> {
        val hint = "tuic://…"
        val userInfo = uri.userInfo.orEmpty()
        val parts = userInfo.split(":", limit = 2)
        if (parts.size != 2) return null to ParseIssue(hint, "нет uuid:пароль")
        val uuid = urlDecode(parts[0])
        val password = urlDecode(parts[1])
        if (uuid.isEmpty() || password.isEmpty()) {
            return null to ParseIssue(hint, "нет uuid:пароль")
        }
        val cc = query["congestion_control"].orEmpty().lowercase()
        if (cc.isNotEmpty() && cc !in setOf("cubic", "new_reno", "bbr")) {
            return null to ParseIssue(hint, "congestion_control=$cc не поддерживается")
        }
        return null to ParseIssue(hint, "TUIC не поддерживается Xray")
    }

    private fun parseVmessLink(line: String, tags: TagScope): Pair<ParsedServer?, ParseIssue?> {
        val hint = "vmess://…"
        val encoded = line.substringAfter("://").substringBefore("#").trim()
        val decoded = decodeBase64Loose(encoded)?.let { String(it, Charsets.UTF_8) }
            ?: return null to ParseIssue(hint, "не base64")
        val obj = runCatching { json.parseToJsonElement(decoded).jsonObject }.getOrNull()
            ?: return null to ParseIssue(hint, "внутри не JSON")
        val uuid = (obj["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val address = (obj["add"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val port = (obj["port"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
        if (uuid.isEmpty() || address.isEmpty() || port == null) {
            return null to ParseIssue(hint, "нет id/add/port")
        }
        val label = (obj["ps"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            .ifBlank { "$address:$port" }
        val net = (obj["net"] as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty()
            .ifBlank { "tcp" }
        val type = (obj["type"] as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty()
            .ifBlank { "none" }
        if (type != "none") {
            return null to ParseIssue(hint, "TCP HTTP-заголовок не поддерживается")
        }
        val tls = (obj["tls"] as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty()
        val security = if (tls == "tls") "tls" else "none"
        val vmessSecurity = (obj["scy"] as? JsonPrimitive)?.contentOrNull?.lowercase()
            .orEmpty().ifBlank { "auto" }
        if (vmessSecurity !in setOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")) {
            return null to ParseIssue(hint, "неизвестный VMess security: $vmessSecurity")
        }
        val alterId = (obj["aid"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val query = mapOf(
            "security" to security,
            "sni" to ((obj["sni"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
            "host" to ((obj["host"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
            "path" to ((obj["path"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
            "serviceName" to ((obj["path"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
            "fp" to ((obj["fp"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
            "alpn" to ((obj["alpn"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
        )
        val settings = buildJsonObject {
            put("vnext", buildJsonArray {
                add(buildJsonObject {
                    put("address", address)
                    put("port", port)
                    put("users", buildJsonArray {
                        add(buildJsonObject {
                            put("id", uuid)
                            put("alterId", alterId)
                            put("security", vmessSecurity)
                        })
                    })
                })
            })
        }
        val tag = tags.unique(label)
        val outbound = buildLinkOutbound(
            protocol = "vmess", settings = settings,
            network = net, security = security, query = query, host = address,
        ) ?: return null to ParseIssue(hint, "не поддерживается: vmess/$net/$security")
        return ParsedServer(
            tag = tag, label = label, protocol = "vmess",
            address = address, xrayOutbound = outbound, network = net,
        ) to null
    }

    // ---------- Xray streamSettings из параметров ссылки ----------

    /**
     * Собирает Xray outbound для vless/vmess/trojan из параметров ссылки.
     * null — комбинация не поддерживается (тогда caller делает issue).
     * Форматы — по документации Xray (xtls.github.io): streamSettings с
     * network/security, tlsSettings/realitySettings, wsSettings/grpcSettings/
     * httpSettings/httpupgradeSettings/xhttpSettings.
     */
    private fun buildLinkOutbound(
        protocol: String,
        settings: JsonObject,
        network: String,
        security: String,
        query: Map<String, String>,
        host: String,
    ): JsonObject? {
        val stream = buildLinkStreamSettings(network, security, query, host)
            ?: return null
        return buildJsonObject {
            put("protocol", protocol)
            put("settings", settings)
            put("streamSettings", stream)
        }
    }

    private fun buildLinkStreamSettings(
        network: String,
        security: String,
        query: Map<String, String>,
        host: String,
    ): JsonObject? {
        if (network !in setOf("tcp", "ws", "grpc", "http", "httpupgrade", "xhttp")) return null
        if (security !in setOf("none", "tls", "reality")) return null
        // TCP HTTP-заголовок Xray-ссылками не описывается — отклоняем, а не
        // теряем параметр молча.
        if (network == "tcp") {
            val headerType = (query["headerType"] ?: "none").lowercase()
            if (headerType != "none") return null
        }
        // Reality без sni/pbk — отклоняем, а не снимаем шифрование молча.
        if (security == "reality") {
            if (query["sni"].isNullOrEmpty() || query["pbk"].isNullOrEmpty()) return null
        }
        return buildJsonObject {
            put("network", network)
            put("security", security)
            when (security) {
                "tls" -> put("tlsSettings", buildJsonObject {
                    put("serverName", query["sni"].orEmpty().ifBlank { host })
                    val alpn = query["alpn"].orEmpty()
                    if (alpn.isNotEmpty()) {
                        put("alpn", buildJsonArray {
                            alpn.split(",").forEach { add(it.trim()) }
                        })
                    }
                    val fp = query["fp"].orEmpty()
                    if (fp.isNotEmpty() && fp in UTLS_FINGERPRINTS) put("fingerprint", fp)
                    if (query["allowInsecure"] == "1" || query["insecure"] == "1") {
                        put("allowInsecure", true)
                    }
                })
                "reality" -> put("realitySettings", buildJsonObject {
                    put("serverName", query["sni"].orEmpty())
                    val fp = query["fp"].orEmpty()
                    if (fp.isNotEmpty() && fp in UTLS_FINGERPRINTS) put("fingerprint", fp)
                    put("publicKey", query["pbk"].orEmpty())
                    val sid = query["sid"].orEmpty()
                    if (sid.isNotEmpty()) put("shortId", sid)
                    val spx = query["spx"].orEmpty()
                    if (spx.isNotEmpty()) put("spiderX", spx)
                })
            }
            when (network) {
                "ws" -> put("wsSettings", buildJsonObject {
                    put("path", query["path"].orEmpty().ifBlank { "/" })
                    val wsHost = query["host"].orEmpty()
                    if (wsHost.isNotEmpty()) {
                        put("headers", buildJsonObject { put("Host", wsHost) })
                    }
                })
                "grpc" -> put("grpcSettings", buildJsonObject {
                    val serviceName = query["serviceName"].orEmpty()
                    if (serviceName.isNotEmpty()) put("serviceName", serviceName)
                })
                "http" -> put("httpSettings", buildJsonObject {
                    val httpHost = query["host"].orEmpty()
                    if (httpHost.isNotEmpty()) put("host", httpHost)
                    put("path", query["path"].orEmpty().ifBlank { "/" })
                })
                "httpupgrade" -> put("httpupgradeSettings", buildJsonObject {
                    val upHost = query["host"].orEmpty()
                    if (upHost.isNotEmpty()) put("host", upHost)
                    put("path", query["path"].orEmpty().ifBlank { "/" })
                })
                "xhttp" -> put("xhttpSettings", buildJsonObject {
                    val xHost = query["host"].orEmpty()
                    if (xHost.isNotEmpty()) put("host", xHost)
                    val xPath = query["path"].orEmpty()
                    if (xPath.isNotEmpty()) put("path", xPath)
                    val mode = query["mode"].orEmpty()
                    if (mode.isNotEmpty()) put("mode", mode)
                })
            }
        }
    }

    // ---------- Утилиты ----------

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (part in rawQuery.split("&")) {
            if (part.isEmpty()) continue
            val eq = part.indexOf("=")
            if (eq < 0) {
                result[urlDecode(part)] = ""
            } else {
                result[urlDecode(part.substring(0, eq))] = urlDecode(part.substring(eq + 1))
            }
        }
        return result
    }

    private fun urlDecode(value: String): String =
        runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

    /**
     * Base64 без strict: стандартный и urlsafe алфавиты, пробелы/переносы
     * игнорируются, недостающий padding достраивается.
     * Ручной декодер: java.util.Base64 требует API 26, а minSdk — 24.
     */
    fun decodeBase64Loose(input: String): ByteArray? {
        val cleaned = input.filter { it !in " \t\r\n" }
        if (cleaned.isEmpty() || cleaned.length < 8) return null
        if (!cleaned.all { it.isLetterOrDigit() || it in "+/=_-" }) return null
        val normalized = cleaned.trimEnd('=').replace('-', '+').replace('_', '/')
        val out = ArrayList<Byte>(normalized.length * 3 / 4)
        var acc = 0
        var bits = 0
        for (ch in normalized) {
            val value = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+' -> 62
                '/' -> 63
                else -> return null
            }
            acc = (acc shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out += ((acc shr bits) and 0xFF).toByte()
            }
        }
        return out.toByteArray()
    }

    private fun isValidUtf8(bytes: ByteArray): Boolean =
        runCatching { String(bytes, Charsets.UTF_8); true }.getOrDefault(false) &&
            !bytes.contains(0)

    /** Уникальные теги серверов внутри одной подписки. */
    private class TagScope {
        private val used = HashSet<String>()
        fun unique(label: String): String {
            val base = label.trim().ifBlank { "server" }
            var candidate = base
            var n = 2
            while (!used.add(candidate)) {
                candidate = "$base ($n)"
                n++
            }
            return candidate
        }
    }
}
