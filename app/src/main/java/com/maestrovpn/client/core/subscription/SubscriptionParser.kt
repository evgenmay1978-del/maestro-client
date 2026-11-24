package com.maestrovpn.client.core.subscription

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.URLDecoder

// Парсер подписок (срез 3). Чистый Kotlin, без Android API — проверяется на обычной JVM
// (см. отчёт среза 3) и валидируется настоящим sing-box 1.14.4 через `sing-box check`.
//
// Что понимает (таблица соответствия §3.10):
//  - полный Xray JSON: массив конфигов (так отдаёт панель в ?format=xray —
//    WhiteListCombinedXrayJSONSubscription) и одиночный конфиг;
//  - share-ссылки: vless / vmess / trojan / ss / socks / hysteria2 / tuic;
//  - base64-списки ссылок (как отдаёт панель в ?format=links);
//  - смешанный формат: ссылки + строки маршрутизации/метаданные (не-ссылки
//    пропускаются, считаются в ignoredLines);
//  - готовый sing-box JSON — проходит как есть (формат SING_BOX_CONFIG).
// Что НЕ понимает (честно, с причиной в issues):
//  - xhttp-транспорт в sing-box: sing-box 1.14.4 его не знает
//    (`sing-box check`: "unknown transport type: xhttp") — такие узлы идут
//    через Xray-раннер (kind = XHTTP_RUNNER), а не в sing-box напрямую;
//  - WireGuard (в sing-box 1.13+ это endpoints, не outbound), TCP HTTP-заголовок,
//    KCP, плагины Shadowsocks, шифрованные подписки crypt/crypt5.
// В сообщениях об ошибках НИКОГДА не печатаются полные ссылки/токены/пароли —
// только схема + подпись + причина.
enum class ServerKind {
    /** Обычный sing-box outbound (конвертирован из Xray/ссылки). */
    NATIVE,

    /** Xray-узел (xhttp): идёт через Xray-раннер, в конфиге — SOCKS-стаб 127.0.0.1:20801+. */
    XHTTP_RUNNER,
}

data class ParsedServer(
    val tag: String,
    val label: String,
    val protocol: String,
    val kind: ServerKind,
    /** Хост точки входа (без учётных данных) — для резолва/DIRECT-правил раннера. */
    val address: String,
    /** sing-box outbound, только для NATIVE. */
    val singBoxOutbound: JsonObject?,
    /** Xray outbound как есть, только для XHTTP_RUNNER (параметры раннера). */
    val xrayOutbound: JsonObject?,
    /** sing-box endpoint (только WireGuard: в 1.14 это endpoint, не outbound). */
    val singBoxEndpoint: JsonObject? = null,
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
    /** Готовый sing-box конфиг, только при format == SING_BOX_CONFIG. */
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
        // Xray outbound узнаётся по "protocol"/"streamSettings", sing-box — по "type".
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

    // ---------- Xray-конфиг ----------

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
            protocol in setOf("vless", "vmess", "trojan", "shadowsocks", "socks")
        } ?: return null to ParseIssue(
            hint = remarks.ifBlank { "Xray" }.take(80),
            reason = "в конфиге нет прокси-outbound (vless/vmess/trojan/ss/socks)",
        )
        return parseXrayOutbound(proxy, remarks, tags)
    }

    /** Конвертирует один Xray outbound в ParsedServer (NATIVE или XHTTP_RUNNER). */
    private fun parseXrayOutbound(
        outbound: JsonObject,
        remarks: String,
        tags: TagScope,
    ): Pair<ParsedServer?, ParseIssue?> {
        val protocol = (outbound["protocol"] as? JsonPrimitive)?.contentOrNull.orEmpty().lowercase()
        val stream = outbound["streamSettings"] as? JsonObject
        val network = (stream?.get("network") as? JsonPrimitive)?.contentOrNull
            ?.lowercase().orEmpty().ifBlank { "tcp" }
        val security = (stream?.get("security") as? JsonPrimitive)?.contentOrNull
            ?.lowercase().orEmpty().ifBlank { "none" }
        val label = remarks.ifBlank { "server" }
        val hint = label.take(80)
        if (network == "xhttp") {
            val settings = outbound["settings"] as? JsonObject
            val address = xrayAddress(protocol, settings).orEmpty()
            if (address.isEmpty()) {
                return null to ParseIssue(hint, "xhttp-узел без адреса")
            }
            val tag = tags.unique(label)
            return ParsedServer(
                tag = tag, label = label, protocol = protocol,
                kind = ServerKind.XHTTP_RUNNER, address = address,
                singBoxOutbound = null, xrayOutbound = outbound,
            ) to null
        }
        val converted = convertXrayOutbound(outbound, protocol, network, security, label)
            ?: return null to ParseIssue(
                hint,
                "не поддерживается: $protocol/$network/$security",
            )
        val (singBox, address) = converted
        val tag = tags.unique(label)
        return ParsedServer(
            tag = tag, label = label, protocol = protocol,
            kind = ServerKind.NATIVE, address = address,
            singBoxOutbound = singBox,
            xrayOutbound = null,
        ) to null
    }

    private fun xrayAddress(protocol: String, settings: JsonObject?): String? {
        if (settings == null) return null
        val vnext = (settings["vnext"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (vnext != null) return (vnext["address"] as? JsonPrimitive)?.contentOrNull
        val servers = (settings["servers"] as? JsonArray)?.firstOrNull() as? JsonObject
        return (servers?.get("address") as? JsonPrimitive)?.contentOrNull
    }

    /**
     * Xray outbound -> sing-box outbound. Возвращает null, когда конвертация
     * невозможна (тогда caller делает issue с протоколом/сетью/защитой).
     */
    private fun convertXrayOutbound(
        outbound: JsonObject,
        protocol: String,
        network: String,
        security: String,
        label: String,
    ): Pair<JsonObject, String>? {
        val settings = outbound["settings"] as? JsonObject ?: return null
        val stream = outbound["streamSettings"] as? JsonObject
        // buildTransport/buildTls возвращают null и для «не нужен», и для
        // «не поддерживается» — поэтому сначала проверяем допустимость значений.
        if (network !in setOf("tcp", "ws", "grpc", "http")) return null
        if (security !in setOf("none", "tls", "reality")) return null
        val transport = buildTransport(network, stream)
        val tls = buildTls(security, stream)
        // tlsSettings/realitySettings отсутствуют при security != none —
        // молча снимать шифрование нельзя, отклоняем узел.
        if (security != "none" && tls == null) return null
        val mux = buildMux(outbound["mux"] as? JsonObject)
        val address: String
        val base: JsonObject = when (protocol) {
            "vless" -> {
                val vnext = (settings["vnext"] as? JsonArray)?.firstOrNull() as? JsonObject
                    ?: return null
                val user = (vnext["users"] as? JsonArray)?.firstOrNull() as? JsonObject
                    ?: return null
                val id = (user["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (id.isEmpty()) return null
                address = (vnext["address"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val port = (vnext["port"] as? JsonPrimitive)?.intOrNull ?: return null
                val flow = (user["flow"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                buildJsonObject {
                    put("type", "vless")
                    put("server", address)
                    put("server_port", port)
                    put("uuid", id)
                    if (flow.isNotEmpty()) put("flow", flow)
                }
            }
            "vmess" -> {
                val vnext = (settings["vnext"] as? JsonArray)?.firstOrNull() as? JsonObject
                    ?: return null
                val user = (vnext["users"] as? JsonArray)?.firstOrNull() as? JsonObject
                    ?: return null
                val id = (user["id"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (id.isEmpty()) return null
                address = (vnext["address"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val port = (vnext["port"] as? JsonPrimitive)?.intOrNull ?: return null
                val alterId = (user["alterId"] as? JsonPrimitive)?.intOrNull ?: 0
                val sec = (user["security"] as? JsonPrimitive)?.contentOrNull
                    ?.lowercase().orEmpty().ifBlank { "auto" }
                if (sec !in setOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")) {
                    return null
                }
                buildJsonObject {
                    put("type", "vmess")
                    put("server", address)
                    put("server_port", port)
                    put("uuid", id)
                    put("security", sec)
                    if (alterId != 0) put("alter_id", alterId)
                }
            }
            "trojan" -> {
                val server = (settings["servers"] as? JsonArray)?.firstOrNull() as? JsonObject
                    ?: return null
                val password = (server["password"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (password.isEmpty()) return null
                address = (server["address"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val port = (server["port"] as? JsonPrimitive)?.intOrNull ?: return null
                buildJsonObject {
                    put("type", "trojan")
                    put("server", address)
                    put("server_port", port)
                    put("password", password)
                }
            }
            "shadowsocks" -> {
                val server = (settings["servers"] as? JsonArray)?.firstOrNull() as? JsonObject
                    ?: return null
                if (server.containsKey("plugin")) return null
                val method = (server["method"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val password = (server["password"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (method.isEmpty() || password.isEmpty()) return null
                address = (server["address"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val port = (server["port"] as? JsonPrimitive)?.intOrNull ?: return null
                buildJsonObject {
                    put("type", "shadowsocks")
                    put("server", address)
                    put("server_port", port)
                    put("method", method)
                    put("password", password)
                }
            }
            "socks" -> {
                val server = (settings["servers"] as? JsonArray)?.firstOrNull() as? JsonObject
                    ?: return null
                address = (server["address"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val port = (server["port"] as? JsonPrimitive)?.intOrNull ?: return null
                val user = (server["users"] as? JsonArray)?.firstOrNull() as? JsonObject
                buildJsonObject {
                    put("type", "socks")
                    put("server", address)
                    put("server_port", port)
                    put("version", "5")
                    if (user != null) {
                        (user["user"] as? JsonPrimitive)?.contentOrNull?.let { put("username", it) }
                        (user["pass"] as? JsonPrimitive)?.contentOrNull?.let { put("password", it) }
                    }
                }
            }
            else -> return null
        }
        if (address.isBlank()) return null
        var result = base
        if (tls != null) result = JsonObject(result + ("tls" to tls))
        if (transport != null) result = JsonObject(result + ("transport" to transport))
        if (mux != null) result = JsonObject(result + ("multiplex" to mux))
        return result to address
    }

    /** Xray streamSettings -> sing-box tls. null = неизвестная защита. */
    private fun buildTls(security: String, stream: JsonObject?): JsonObject? {
        if (security == "none") return null
        if (stream == null) return null
        return when (security) {
            "tls" -> {
                val tls = stream["tlsSettings"] as? JsonObject ?: return null
                val serverName = (tls["serverName"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val alpn = (tls["alpn"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
                val fp = (tls["fingerprint"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                buildJsonObject {
                    put("enabled", true)
                    if (serverName.isNotEmpty()) put("server_name", serverName)
                    if (alpn.isNotEmpty()) {
                        put("alpn", buildJsonArray { alpn.forEach { add(it) } })
                    }
                    if (fp.isNotEmpty() && fp in UTLS_FINGERPRINTS) {
                        put("utls", buildJsonObject {
                            put("enabled", true)
                            put("fingerprint", fp)
                        })
                    }
                }
            }
            "reality" -> {
                val reality = stream["realitySettings"] as? JsonObject ?: return null
                val serverName = (reality["serverName"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val fp = (reality["fingerprint"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val publicKey = (reality["publicKey"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val shortId = (reality["shortId"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (serverName.isEmpty() || publicKey.isEmpty()) return null
                buildJsonObject {
                    put("enabled", true)
                    put("server_name", serverName)
                    if (fp.isNotEmpty() && fp in UTLS_FINGERPRINTS) {
                        put("utls", buildJsonObject {
                            put("enabled", true)
                            put("fingerprint", fp)
                        })
                    }
                    put("reality", buildJsonObject {
                        put("enabled", true)
                        put("public_key", publicKey)
                        if (shortId.isNotEmpty()) put("short_id", shortId)
                    })
                }
            }
            else -> null
        }
    }

    private val UTLS_FINGERPRINTS = setOf(
        "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq",
        "random", "randomized",
    )

    /**
     * Xray network -> sing-box transport. null = транспорт не поддерживается
     * sing-box (kcp и т.п.); xhttp здесь не встречается — он отсекается раньше
     * в парсер Xray outbound и идёт в раннер.
     */
    private fun buildTransport(network: String, stream: JsonObject?): JsonObject? {
        return when (network) {
            "tcp" -> {
                val tcp = stream?.get("tcpSettings") as? JsonObject
                val header = tcp?.get("header") as? JsonObject
                val headerType = (header?.get("type") as? JsonPrimitive)?.contentOrNull
                    ?.lowercase().orEmpty()
                if (headerType.isNotEmpty() && headerType != "none") return null
                null // обычный TCP — транспорт не нужен
            }
            "ws" -> {
                val ws = stream?.get("wsSettings") as? JsonObject
                val path = (ws?.get("path") as? JsonPrimitive)?.contentOrNull.orEmpty()
                    .ifBlank { "/" }
                val headers = ws?.get("headers") as? JsonObject
                val host = (headers?.get("Host") as? JsonPrimitive)?.contentOrNull.orEmpty()
                buildJsonObject {
                    put("type", "ws")
                    put("path", path)
                    if (host.isNotEmpty()) {
                        put("headers", buildJsonObject { put("Host", host) })
                    }
                }
            }
            "grpc" -> {
                val grpc = stream?.get("grpcSettings") as? JsonObject
                val serviceName = (grpc?.get("serviceName") as? JsonPrimitive)?.contentOrNull
                    .orEmpty()
                buildJsonObject {
                    put("type", "grpc")
                    if (serviceName.isNotEmpty()) put("service_name", serviceName)
                }
            }
            "http" -> {
                val http = stream?.get("httpSettings") as? JsonObject
                val host = (http?.get("host") as? JsonPrimitive)?.contentOrNull.orEmpty()
                val path = (http?.get("path") as? JsonPrimitive)?.contentOrNull.orEmpty()
                    .ifBlank { "/" }
                buildJsonObject {
                    put("type", "http")
                    if (host.isNotEmpty()) put("host", host)
                    put("path", path)
                }
            }
            "xhttp" -> null // обрабатывается отдельно (раннер), сюда не доходит
            else -> null
        }
    }

    private fun buildMux(mux: JsonObject?): JsonObject? {
        if (mux == null) return null
        val enabled = (mux["enabled"] as? JsonPrimitive)?.contentOrNull == "true"
        if (!enabled) return null
        val concurrency = (mux["concurrency"] as? JsonPrimitive)?.intOrNull ?: 0
        return buildJsonObject {
            put("enabled", true)
            put("protocol", "smux")
            if (concurrency > 0) put("max_connections", concurrency)
        }
    }

    // ---------- Текстовые строки (share-ссылки) ----------

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
                hint, "WireGuard — не поддерживается в этой версии",
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
        if (type == "xhttp") {
            // Xray outbound для раннера: собираем из параметров ссылки.
            val xray = buildXrayVlessOutbound(uuid, query, host, port, label)
            return ParsedServer(
                tag = tag, label = label, protocol = "vless",
                kind = ServerKind.XHTTP_RUNNER, address = host,
                singBoxOutbound = null, xrayOutbound = xray,
            ) to null
        }
        val outbound = convertLinkToSingBox(
            protocol = "vless", host = host, port = port,
            uuid = uuid, flow = query["flow"].orEmpty(),
            security = security, type = type, query = query,
        ) ?: return null to ParseIssue(hint, "не поддерживается: vless/$type/$security")
        return ParsedServer(
            tag = tag, label = label, protocol = "vless",
            kind = ServerKind.NATIVE, address = host,
            singBoxOutbound = outbound, xrayOutbound = null,
        ) to null
    }

    /** Собирает Xray outbound для xhttp-узла из параметров vless-ссылки. */
    private fun buildXrayVlessOutbound(
        uuid: String,
        query: Map<String, String>,
        host: String,
        port: Int,
        label: String,
    ): JsonObject {
        val sni = query["sni"].orEmpty()
        val fp = query["fp"].orEmpty()
        val alpn = query["alpn"].orEmpty()
        return buildJsonObject {
            put("tag", label)
            put("protocol", "vless")
            put("settings", buildJsonObject {
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
            })
            put("streamSettings", buildJsonObject {
                put("network", "xhttp")
                put("security", "tls")
                put("tlsSettings", buildJsonObject {
                    if (sni.isNotEmpty()) put("serverName", sni)
                    if (alpn.isNotEmpty()) {
                        put("alpn", buildJsonArray {
                            alpn.split(",").forEach { add(it.trim()) }
                        })
                    }
                    if (fp.isNotEmpty()) put("fingerprint", fp)
                })
                val xPath = query["path"].orEmpty()
                val xHost = query["host"].orEmpty()
                val mode = query["mode"].orEmpty()
                put("xhttpSettings", buildJsonObject {
                    if (xHost.isNotEmpty()) put("host", xHost)
                    if (xPath.isNotEmpty()) put("path", xPath)
                    if (mode.isNotEmpty()) put("mode", mode)
                })
            })
        }
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
        val security = (query["security"] ?: "tls").lowercase()
        val type = (query["type"] ?: "tcp").lowercase()
        if (type == "xhttp") {
            return null to ParseIssue(hint, "trojan/xhttp — не поддерживается")
        }
        // linkTransport возвращает null и для «не нужен» (tcp), и для
        // «не поддерживается» — сначала проверяем допустимость типа.
        if (type !in setOf("tcp", "ws", "grpc", "http")) {
            return null to ParseIssue(hint, "не поддерживается: trojan/$type/$security")
        }
        val transport = linkTransport(type, query)
        val sni = query["sni"].orEmpty().ifBlank { host }
        val alpn = query["alpn"].orEmpty()
        val fp = query["fp"].orEmpty()
        val allowInsecure = query["allowInsecure"] == "1" || query["insecure"] == "1"
        val outbound = buildJsonObject {
            put("type", "trojan")
            put("server", host)
            put("server_port", port)
            put("password", password)
            put("tls", buildJsonObject {
                put("enabled", true)
                put("server_name", sni)
                if (alpn.isNotEmpty()) {
                    put("alpn", buildJsonArray { alpn.split(",").forEach { add(it.trim()) } })
                }
                if (fp.isNotEmpty() && fp in UTLS_FINGERPRINTS) {
                    put("utls", buildJsonObject {
                        put("enabled", true)
                        put("fingerprint", fp)
                    })
                }
                if (allowInsecure) put("insecure", true)
            })
            if (transport != null) put("transport", transport)
        }
        return ParsedServer(
            tag = tag, label = label, protocol = "trojan",
            kind = ServerKind.NATIVE, address = host,
            singBoxOutbound = outbound, xrayOutbound = null,
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
            put("type", "shadowsocks")
            put("server", realHost)
            put("server_port", realPort)
            put("method", method)
            put("password", password)
        }
        return ParsedServer(
            tag = tag, label = label, protocol = "ss",
            kind = ServerKind.NATIVE, address = realHost,
            singBoxOutbound = outbound, xrayOutbound = null,
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
            put("type", "socks")
            put("server", realHost)
            put("server_port", realPort)
            put("version", "5")
            if (username != null) put("username", username)
            if (password != null) put("password", password)
        }
        return ParsedServer(
            tag = tag, label = label, protocol = "socks",
            kind = ServerKind.NATIVE, address = realHost,
            singBoxOutbound = outbound, xrayOutbound = null,
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
        val insecure = query["insecure"] == "1"
        val obfs = query["obfs"].orEmpty()
        val obfsPassword = query["obfs-password"].orEmpty()
        if (obfs.isNotEmpty() && obfs != "salamander") {
            return null to ParseIssue(hint, "obfs=$obfs не поддерживается")
        }
        val outbound = buildJsonObject {
            put("type", "hysteria2")
            put("server", host)
            put("server_port", port)
            put("password", password)
            put("tls", buildJsonObject {
                put("enabled", true)
                put("server_name", sni)
                if (insecure) put("insecure", true)
            })
            if (obfs == "salamander" && obfsPassword.isNotEmpty()) {
                put("obfs", buildJsonObject {
                    put("type", "salamander")
                    put("password", obfsPassword)
                })
            }
        }
        return ParsedServer(
            tag = tag, label = label, protocol = "hysteria2",
            kind = ServerKind.NATIVE, address = host,
            singBoxOutbound = outbound, xrayOutbound = null,
        ) to null
    }

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
        val sni = query["sni"].orEmpty().ifBlank { host }
        val alpn = query["alpn"].orEmpty()
        val cc = query["congestion_control"].orEmpty().lowercase()
        if (cc.isNotEmpty() && cc !in setOf("cubic", "new_reno", "bbr")) {
            return null to ParseIssue(hint, "congestion_control=$cc не поддерживается")
        }
        val outbound = buildJsonObject {
            put("type", "tuic")
            put("server", host)
            put("server_port", port)
            put("uuid", uuid)
            put("password", password)
            if (cc.isNotEmpty()) put("congestion_control", cc)
            put("tls", buildJsonObject {
                put("enabled", true)
                put("server_name", sni)
                if (alpn.isNotEmpty()) {
                    put("alpn", buildJsonArray { alpn.split(",").forEach { add(it.trim()) } })
                }
            })
        }
        return ParsedServer(
            tag = tag, label = label, protocol = "tuic",
            kind = ServerKind.NATIVE, address = host,
            singBoxOutbound = outbound, xrayOutbound = null,
        ) to null
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
        if (net == "xhttp") {
            return null to ParseIssue(hint, "vmess/xhttp — не поддерживается")
        }
        val tls = (obj["tls"] as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty()
        val security = if (tls == "tls") "tls" else "none"
        val query = mapOf(
            "security" to security,
            "type" to net,
            "sni" to ((obj["sni"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
            "host" to ((obj["host"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
            "path" to ((obj["path"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
            "serviceName" to ((obj["path"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
            "fp" to ((obj["fp"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
            "alpn" to ((obj["alpn"] as? JsonPrimitive)?.contentOrNull.orEmpty()),
        )
        val tag = tags.unique(label)
        val outbound = convertLinkToSingBox(
            protocol = "vmess", host = address, port = port,
            uuid = uuid, flow = "",
            security = security, type = net, query = query,
            vmessSecurity = (obj["scy"] as? JsonPrimitive)?.contentOrNull?.lowercase()
                .orEmpty().ifBlank { "auto" },
            alterId = (obj["aid"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0,
        ) ?: return null to ParseIssue(hint, "не поддерживается: vmess/$net/$security")
        return ParsedServer(
            tag = tag, label = label, protocol = "vmess",
            kind = ServerKind.NATIVE, address = address,
            singBoxOutbound = outbound, xrayOutbound = null,
        ) to null
    }

    /**
     * Общая конвертация ссылки (vless/vmess) в sing-box outbound.
     * null — комбинация не поддерживается.
     */
    private fun convertLinkToSingBox(
        protocol: String,
        host: String,
        port: Int,
        uuid: String,
        flow: String,
        security: String,
        type: String,
        query: Map<String, String>,
        vmessSecurity: String = "auto",
        alterId: Int = 0,
    ): JsonObject? {
        if (type !in setOf("tcp", "ws", "grpc", "http")) return null
        if (security !in setOf("none", "tls", "reality")) return null
        val transport = linkTransport(type, query)
        val tls = linkTls(security, query, host)
        // reality без sni/pbk — отклоняем, а не снимаем шифрование молча.
        if (security != "none" && tls == null) return null
        val base: JsonObject = when (protocol) {
            "vless" -> buildJsonObject {
                put("type", "vless")
                put("server", host)
                put("server_port", port)
                put("uuid", uuid)
                if (flow.isNotEmpty()) put("flow", flow)
            }
            "vmess" -> {
                if (vmessSecurity !in setOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")) {
                    return null
                }
                buildJsonObject {
                    put("type", "vmess")
                    put("server", host)
                    put("server_port", port)
                    put("uuid", uuid)
                    put("security", vmessSecurity)
                    if (alterId != 0) put("alter_id", alterId)
                }
            }
            else -> return null
        }
        var result = base
        if (tls != null) result = JsonObject(result + ("tls" to tls))
        if (transport != null) result = JsonObject(result + ("transport" to transport))
        return result
    }

    /** Параметры ссылки -> sing-box transport. null = не поддерживается. */
    private fun linkTransport(type: String, query: Map<String, String>): JsonObject? {
        return when (type) {
            "tcp" -> {
                val headerType = (query["headerType"] ?: "none").lowercase()
                if (headerType != "none") return null
                null
            }
            "ws" -> {
                val path = query["path"].orEmpty().ifBlank { "/" }
                val host = query["host"].orEmpty()
                buildJsonObject {
                    put("type", "ws")
                    put("path", path)
                    if (host.isNotEmpty()) {
                        put("headers", buildJsonObject { put("Host", host) })
                    }
                }
            }
            "grpc" -> {
                val serviceName = query["serviceName"].orEmpty()
                buildJsonObject {
                    put("type", "grpc")
                    if (serviceName.isNotEmpty()) put("service_name", serviceName)
                }
            }
            "http" -> {
                val host = query["host"].orEmpty()
                val path = query["path"].orEmpty().ifBlank { "/" }
                buildJsonObject {
                    put("type", "http")
                    if (host.isNotEmpty()) put("host", host)
                    put("path", path)
                }
            }
            else -> null // kcp, xhttp (отдельно), quic — не поддерживаются
        }
    }

    /** Параметры ссылки -> sing-box tls. null = неизвестная защита. */
    private fun linkTls(security: String, query: Map<String, String>, host: String): JsonObject? {
        return when (security) {
            "none", "" -> null
            "tls" -> {
                val sni = query["sni"].orEmpty().ifBlank { host }
                val alpn = query["alpn"].orEmpty()
                val fp = query["fp"].orEmpty()
                val allowInsecure = query["allowInsecure"] == "1" || query["insecure"] == "1"
                buildJsonObject {
                    put("enabled", true)
                    put("server_name", sni)
                    if (alpn.isNotEmpty()) {
                        put("alpn", buildJsonArray { alpn.split(",").forEach { add(it.trim()) } })
                    }
                    if (fp.isNotEmpty() && fp in UTLS_FINGERPRINTS) {
                        put("utls", buildJsonObject {
                            put("enabled", true)
                            put("fingerprint", fp)
                        })
                    }
                    if (allowInsecure) put("insecure", true)
                }
            }
            "reality" -> {
                val sni = query["sni"].orEmpty()
                val fp = query["fp"].orEmpty()
                val pbk = query["pbk"].orEmpty()
                val sid = query["sid"].orEmpty()
                if (sni.isEmpty() || pbk.isEmpty()) return null
                buildJsonObject {
                    put("enabled", true)
                    put("server_name", sni)
                    if (fp.isNotEmpty() && fp in UTLS_FINGERPRINTS) {
                        put("utls", buildJsonObject {
                            put("enabled", true)
                            put("fingerprint", fp)
                        })
                    }
                    put("reality", buildJsonObject {
                        put("enabled", true)
                        put("public_key", pbk)
                        if (sid.isNotEmpty()) put("short_id", sid)
                    })
                }
            }
            else -> null
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
