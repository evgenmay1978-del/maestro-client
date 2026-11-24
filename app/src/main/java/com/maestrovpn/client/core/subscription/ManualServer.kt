package com.maestrovpn.client.core.subscription

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

// Ручной сервер (срез 5, issue #1): один сервер, введённый вручную в редакторе.
// Модель — поля редактора Happ (карта happ-4.11.2-map.md §2) в том объёме,
// который исполняет ядро sing-box 1.14.4 + Xray-раннер (таблица соответствия §2).
//
// Что отображается в sing-box outbound (нативный путь):
//   VLESS/VMess/Trojan — uuid/flow/security, транспорт tcp/ws/grpc/httpupgrade,
//   TLS/Reality (+ALPN, uTLS-отпечаток), mux;
//   Shadowsocks — method/password (только TCP: sing-box не знает транспорта для SS);
//   SOCKS — user/password (необязательно);
//   WireGuard — НЕ outbound: в sing-box 1.14 это endpoint (outbound wireguard
//   отклоняется проверкой конфига), поэтому toParsedServer кладёт его в
//   ParsedServer.singBoxEndpoint, а SingBoxConfigBuilder пишет секцию endpoints;
//   Hysteria2 — password, up/down Мбит/с, obfs salamander, TLS.
// Что идёт через Xray-раннер (network == xhttp, только VLESS/VMess/Trojan):
//   Xray outbound как есть + xhttpSettings (host/path/mode + сырой xhttpExtra)
//   + finalmask в streamSettings; в sing-box конфиг — SOCKS-стаб 127.0.0.1:20801+,
//   как у узлов подписок (SingBoxConfigBuilder, тот же диапазон портов).
// Что НЕ исполняется и потому блокирует сохранение с понятной ошибкой:
//   kcp-транспорт и его поля mtu/tti/cwnd (только mKCP в Xray, sing-box его
//   не знает, а раннер приложения обслуживает только xhttp-узлы);
//   Port Hopping и UDP idle timeout у Hysteria2 (в sing-box 1.14.4 таких
//   опций нет); grpc mode multi для нативных узлов (есть только в Xray,
//   sing-box grpc — всегда gun).
// В сообщениях об ошибках НИКОГДА не печатаются секреты (uuid/пароли/ключи) —
// только имя поля и причина, как в SubscriptionParser.
@Serializable
data class ManualServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    /** vless | vmess | trojan | shadowsocks | socks | wireguard | hysteria2 */
    val protocol: String = "vless",
    val address: String = "",
    val port: Int = 443,
    // Учётные данные / идентичность.
    val uuid: String = "",
    val password: String = "",
    val username: String = "",
    /** Shadowsocks method или VMess security (auto/aes-128-gcm/chacha20-poly1305/none/zero). */
    val method: String = "",
    val alterId: Int = 0,
    /** VLESS flow: "" | none | xtls-rprx-vision | xtls-rprx-vision-udp443. */
    val flow: String = "",
    // Транспорт (VLESS/VMess/Trojan).
    /** tcp | ws | grpc | httpupgrade | xhttp */
    val network: String = "tcp",
    val path: String = "",
    val host: String = "",
    val serviceName: String = "",
    /** gun | multi — только для Xray (xhttp-узлы); нативный grpc всегда gun. */
    val grpcMode: String = "gun",
    // Безопасность потока.
    /** none | tls | reality */
    val security: String = "none",
    val sni: String = "",
    /** ALPN через запятую, например "h2,http/1.1". */
    val alpn: String = "",
    /** uTLS-отпечаток: "" (нет) | chrome | firefox | safari | ios | android | edge | 360 | qq | random | randomized */
    val fingerprint: String = "",
    val allowInsecure: Boolean = false,
    /** Reality: публичный ключ сервера (pbk) и short id (sid). */
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    // Mux.
    val muxEnabled: Boolean = false,
    val muxConcurrency: Int = 8,
    // WireGuard.
    val publicKey: String = "",
    val privateKey: String = "",
    val preSharedKey: String = "",
    /** Локальные адреса интерфейса через запятую, например "10.0.0.2/32". */
    val localAddress: String = "",
    /** Allowed IPs пира через запятую (по умолчанию — весь трафик). */
    val allowedIps: String = "0.0.0.0/0, ::/0",
    val mtu: Int = 1420,
    /** Reserved: "" или три числа через запятую, например "0,0,0". */
    val reserved: String = "",
    // Hysteria2.
    /** "10", "10 mbps", "1 gbps" — число Мбит/с. */
    val upMbps: String = "",
    val downMbps: String = "",
    val obfsPassword: String = "",
    /** Port hopping / UDP idle timeout: хранятся, но сохранение блокируется
     *  (см. шапку файла) — sing-box 1.14.4 их не поддерживает. */
    val hopPorts: String = "",
    val hopInterval: String = "",
    val udpIdleTimeout: String = "",
    // XHTTP (только network == xhttp).
    /** auto | packet-up | stream-up | stream-one */
    val xhttpMode: String = "auto",
    /** Сырой JSON-объект, сливается в xhttpSettings (например {"xPaddingBytes":"100-1000"}). */
    val xhttpExtra: String = "",
    /** Сырой JSON-объект finalmask для streamSettings Xray-раннера. */
    val finalMask: String = "",
) {
    companion object {
        val PROTOCOLS = listOf(
            "vless", "vmess", "trojan", "shadowsocks", "socks", "wireguard", "hysteria2",
        )
        val NETWORKS = listOf("tcp", "ws", "grpc", "httpupgrade", "xhttp")
        val SECURITIES = listOf("none", "tls", "reality")
        val FLOWS = listOf("", "none", "xtls-rprx-vision", "xtls-rprx-vision-udp443")
        val FINGERPRINTS = listOf(
            "", "chrome", "firefox", "safari", "ios", "android",
            "edge", "360", "qq", "random", "randomized",
        )
        val XHTTP_MODES = listOf("auto", "packet-up", "stream-up", "stream-one")
        val GRPC_MODES = listOf("gun", "multi")
        val SS_METHODS = listOf(
            "aes-256-gcm", "aes-128-gcm", "chacha20-poly1305",
            "chacha20-ietf-poly1305", "xchacha20-poly1305", "xchacha20-ietf-poly1305",
            "none", "plain", "2022-blake3-aes-128-gcm", "2022-blake3-aes-256-gcm",
            "2022-blake3-chacha20-poly1305",
        )
        val VMESS_SECURITIES = listOf("auto", "aes-128-gcm", "chacha20-poly1305", "none", "zero")

        /** "10" / "10 mbps" / "1 gbps" -> Мбит/с, null — не число. */
        fun parseMbps(text: String): Int? {
            val t = text.trim().lowercase()
            if (t.isEmpty()) return null
            val number = t.removeSuffix("mbps").removeSuffix("gbps").removeSuffix("mb")
                .removeSuffix("gb").trim()
            val value = number.toDoubleOrNull() ?: return null
            if (value <= 0) return null
            val mbps = if (t.endsWith("gbps") || t.endsWith("gb")) value * 1000 else value
            return mbps.toInt().takeIf { it in 1..100_000 }
        }
    }

    /** Тег сервера в sing-box конфиге — имя (уникальность проверяет хранилище). */
    val tag: String get() = name.trim()

    /** Все ошибки формы списком; пусто — сохранять можно. */
    fun validate(): List<String> {
        val errors = ArrayList<String>()
        if (name.isBlank()) errors += "Укажите имя сервера"
        if (protocol !in PROTOCOLS) errors += "Неизвестный протокол: $protocol"
        if (address.isBlank()) errors += "Укажите адрес сервера"
        if (address.contains("://") || address.contains(" ") || address.contains("/")) {
            errors += "Адрес — только хост или IP, без схемы и пути"
        }
        if (port !in 1..65535) errors += "Порт должен быть от 1 до 65535"
        if (network !in NETWORKS) errors += "Неизвестный транспорт: $network"
        if (security !in SECURITIES) errors += "Неизвестная защита потока: $security"
        if (fingerprint !in FINGERPRINTS) errors += "Неизвестный отпечаток: $fingerprint"
        if (flow !in FLOWS) errors += "Неизвестный flow: $flow"
        if (xhttpMode !in XHTTP_MODES) errors += "Неизвестный XHTTP mode: $xhttpMode"
        if (grpcMode !in GRPC_MODES) errors += "Неизвестный gRPC mode: $grpcMode"
        if (security == "reality") {
            if (sni.isBlank()) errors += "Reality: укажите SNI (имя сервера для TLS)"
            if (realityPublicKey.isBlank()) errors += "Reality: укажите публичный ключ (pbk)"
            // sing-box check: "uTLS is required by reality client".
            if (fingerprint.isBlank()) errors += "Reality: укажите отпечаток (uTLS) — sing-box требует uTLS для Reality"
        }
        if (muxEnabled && muxConcurrency !in 1..64) {
            errors += "Mux: конкурентность — от 1 до 64"
        }
        when (protocol) {
            "vless" -> {
                if (uuid.isBlank()) errors += "VLESS: укажите UUID"
                if (flow == "xtls-rprx-vision-udp443" && network != "tcp") {
                    errors += "Flow xtls-rprx-vision-udp443 работает только с TCP"
                }
            }
            "vmess" -> {
                if (uuid.isBlank()) errors += "VMess: укажите UUID"
                if (flow.isNotBlank() && flow != "none") errors += "Flow — только для VLESS"
                if (method.isNotBlank() && method.lowercase() !in VMESS_SECURITIES) {
                    errors += "VMess: неизвестный security: ${method.lowercase()}"
                }
                if (alterId !in 0..65535) errors += "VMess: alterId — от 0 до 65535"
            }
            "trojan" -> {
                if (password.isBlank()) errors += "Trojan: укажите пароль"
                if (security == "none") errors += "Trojan: без TLS нельзя — выберите TLS или Reality"
                if (flow.isNotBlank() && flow != "none") errors += "Flow — только для VLESS"
            }
            "shadowsocks" -> {
                if (password.isBlank()) errors += "Shadowsocks: укажите пароль"
                if (method.isBlank()) errors += "Shadowsocks: выберите метод шифрования"
                else if (method !in SS_METHODS) errors += "Shadowsocks: неизвестный метод: $method"
                if (network != "tcp") {
                    errors += "Shadowsocks в sing-box — только TCP (транспорт $network не поддерживается)"
                }
                if (security != "none") errors += "Shadowsocks: TLS/Reality не применяется"
                if (flow.isNotBlank() && flow != "none") errors += "Flow — только для VLESS"
            }
            "socks" -> {
                if (network != "tcp") {
                    errors += "SOCKS в sing-box — только TCP (транспорт $network не поддерживается)"
                }
                if (security != "none") errors += "SOCKS: TLS/Reality не применяется"
                if (flow.isNotBlank() && flow != "none") errors += "Flow — только для VLESS"
            }
            "wireguard" -> {
                if (publicKey.isBlank()) errors += "WireGuard: укажите публичный ключ"
                if (privateKey.isBlank()) errors += "WireGuard: укажите приватный ключ"
                if (localAddress.isBlank()) errors += "WireGuard: укажите локальный адрес"
                if (allowedIps.isBlank()) errors += "WireGuard: укажите Allowed IPs"
                if (mtu !in 576..9000) errors += "WireGuard: MTU — от 576 до 9000"
                if (network != "tcp") errors += "WireGuard: транспорт не применяется"
                if (security != "none") errors += "WireGuard: TLS/Reality не применяется"
                val reservedParts = reserved.trim()
                if (reservedParts.isNotEmpty()) {
                    val nums = reservedParts.split(",").map { it.trim().toIntOrNull() }
                    if (nums.size != 3 || nums.any { it == null || it !in 0..255 }) {
                        errors += "WireGuard: Reserved — три числа 0–255 через запятую"
                    }
                }
                val badLocal = localAddress.split(",").map { it.trim() }
                    .filter { it.isNotEmpty() && !it.contains(".") && !it.contains(":") }
                if (badLocal.isNotEmpty()) errors += "WireGuard: локальный адрес — IP или CIDR"
            }
            "hysteria2" -> {
                if (password.isBlank()) errors += "Hysteria2: укажите пароль"
                if (upMbps.isNotBlank() && parseMbps(upMbps) == null) {
                    errors += "Hysteria2: скорость отдачи — число Мбит/с (например «10» или «1 gbps»)"
                }
                if (downMbps.isNotBlank() && parseMbps(downMbps) == null) {
                    errors += "Hysteria2: скорость загрузки — число Мбит/с"
                }
                if (hopPorts.isNotBlank()) {
                    errors += "Hysteria2: Port Hopping не поддерживается sing-box 1.14.4 — уберите значение"
                }
                if (hopInterval.isNotBlank()) {
                    errors += "Hysteria2: интервал Port Hopping не поддерживается sing-box 1.14.4"
                }
                if (udpIdleTimeout.isNotBlank()) {
                    errors += "Hysteria2: UDP idle timeout не поддерживается sing-box 1.14.4 — уберите значение"
                }
                if (network != "tcp") errors += "Hysteria2: транспорт не применяется"
                if (flow.isNotBlank() && flow != "none") errors += "Flow — только для VLESS"
            }
        }
        if (network == "xhttp") {
            if (protocol !in setOf("vless", "vmess", "trojan")) {
                errors += "XHTTP доступен только для VLESS/VMess/Trojan"
            }
            if (security == "none") {
                errors += "XHTTP: без TLS нельзя — выберите TLS или Reality"
            }
            if (xhttpExtra.isNotBlank() && parseJsonObject(xhttpExtra) == null) {
                errors += "XHTTP Extra — не JSON-объект"
            }
            if (finalMask.isNotBlank() && parseJsonObject(finalMask) == null) {
                errors += "FinalMask — не JSON-объект"
            }
        } else {
            if (xhttpExtra.isNotBlank()) errors += "XHTTP Extra применяется только с транспортом XHTTP"
            if (finalMask.isNotBlank()) errors += "FinalMask применяется только с транспортом XHTTP"
            if (grpcMode == "multi") {
                errors += "gRPC mode multi — только для Xray (нативный grpc всегда gun)"
            }
        }
        return errors
    }

    /**
     * Сервер -> ParsedServer для SingBoxConfigBuilder (та же модель, что у подписок).
     * null + причина — когда комбинация не исполняется ядром.
     */
    fun toParsedServer(): Pair<ParsedServer?, String?> {
        val errors = validate()
        if (errors.isNotEmpty()) return null to errors.first()
        val label = tag
        if (network == "xhttp") return toXhttpParsedServer(label)
        return when (protocol) {
            "wireguard" -> {
                val endpoint = buildJsonObject {
                    put("type", "wireguard")
                    put("tag", label)
                    put("address", buildJsonArray {
                        localAddress.split(",").map { it.trim() }
                            .filter { it.isNotEmpty() }.forEach { add(it) }
                    })
                    put("private_key", privateKey.trim())
                    put("peers", buildJsonArray {
                        add(buildJsonObject {
                            put("address", address.trim())
                            put("port", port)
                            put("public_key", publicKey.trim())
                            put("allowed_ips", buildJsonArray {
                                allowedIps.split(",").map { it.trim() }
                                    .filter { it.isNotEmpty() }.forEach { add(it) }
                            })
                            if (preSharedKey.isNotBlank()) put("pre_shared_key", preSharedKey.trim())
                            val reservedParts = reserved.trim()
                            if (reservedParts.isNotEmpty()) {
                                put("reserved", buildJsonArray {
                                    reservedParts.split(",").forEach { add(it.trim().toInt()) }
                                })
                            }
                        })
                    })
                    put("mtu", mtu)
                }
                ParsedServer(
                    tag = label, label = label, protocol = "wireguard",
                    kind = ServerKind.NATIVE, address = address.trim(),
                    singBoxOutbound = null, xrayOutbound = null,
                    singBoxEndpoint = endpoint,
                ) to null
            }
            "hysteria2" -> {
                val outbound = buildJsonObject {
                    put("type", "hysteria2")
                    put("server", address.trim())
                    put("server_port", port)
                    put("password", password)
                    put("tls", buildJsonObject {
                        put("enabled", true)
                        put("server_name", sni.ifBlank { address.trim() })
                        if (allowInsecure) put("insecure", true)
                    })
                    if (obfsPassword.isNotBlank()) {
                        put("obfs", buildJsonObject {
                            put("type", "salamander")
                            put("password", obfsPassword)
                        })
                    }
                    parseMbps(upMbps)?.let { put("up_mbps", it) }
                    parseMbps(downMbps)?.let { put("down_mbps", it) }
                    if (muxEnabled) put("multiplex", buildMux())
                }
                ParsedServer(
                    tag = label, label = label, protocol = "hysteria2",
                    kind = ServerKind.NATIVE, address = address.trim(),
                    singBoxOutbound = outbound, xrayOutbound = null,
                ) to null
            }
            "shadowsocks" -> {
                val outbound = buildJsonObject {
                    put("type", "shadowsocks")
                    put("server", address.trim())
                    put("server_port", port)
                    put("method", method)
                    put("password", password)
                    if (muxEnabled) put("multiplex", buildMux())
                }
                ParsedServer(
                    tag = label, label = label, protocol = "shadowsocks",
                    kind = ServerKind.NATIVE, address = address.trim(),
                    singBoxOutbound = outbound, xrayOutbound = null,
                ) to null
            }
            "socks" -> {
                val outbound = buildJsonObject {
                    put("type", "socks")
                    put("server", address.trim())
                    put("server_port", port)
                    put("version", "5")
                    if (username.isNotBlank()) put("username", username)
                    if (password.isNotBlank()) put("password", password)
                    if (muxEnabled) put("multiplex", buildMux())
                }
                ParsedServer(
                    tag = label, label = label, protocol = "socks",
                    kind = ServerKind.NATIVE, address = address.trim(),
                    singBoxOutbound = outbound, xrayOutbound = null,
                ) to null
            }
            "vless", "vmess", "trojan" -> {
                if (security.lowercase() != "none" && buildTls() == null) {
                    return null to "не удалось собрать TLS/Reality"
                }
                val tls = buildTls()
                val transport = buildTransport()
                val outbound = when (protocol) {
                    "vless" -> buildJsonObject {
                        put("type", "vless")
                        put("server", address.trim())
                        put("server_port", port)
                        put("uuid", uuid.trim())
                        val f = flow.trim()
                        if (f.isNotEmpty() && f != "none") put("flow", f)
                    }
                    "vmess" -> buildJsonObject {
                        put("type", "vmess")
                        put("server", address.trim())
                        put("server_port", port)
                        put("uuid", uuid.trim())
                        put("security", method.lowercase().ifBlank { "auto" })
                        if (alterId != 0) put("alter_id", alterId)
                    }
                    else -> buildJsonObject {
                        put("type", "trojan")
                        put("server", address.trim())
                        put("server_port", port)
                        put("password", password)
                    }
                }
                var result = outbound
                if (tls != null) result = JsonObject(result + ("tls" to tls))
                if (transport != null) result = JsonObject(result + ("transport" to transport))
                if (muxEnabled) result = JsonObject(result + ("multiplex" to buildMux()))
                ParsedServer(
                    tag = label, label = label, protocol = protocol,
                    kind = ServerKind.NATIVE, address = address.trim(),
                    singBoxOutbound = result, xrayOutbound = null,
                ) to null
            }
            else -> null to "неизвестный протокол: $protocol"
        }
    }

    // ---------- Нативный путь: TLS / транспорт / mux (те же формы, что в SubscriptionParser) ----------

    private fun buildTls(): JsonObject? {
        return when (security.lowercase()) {
            "none" -> null
            "tls" -> buildJsonObject {
                put("enabled", true)
                put("server_name", sni.ifBlank { address.trim() })
                val alpnList = alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (alpnList.isNotEmpty()) {
                    put("alpn", buildJsonArray { alpnList.forEach { add(it) } })
                }
                val fp = fingerprint.trim()
                if (fp.isNotEmpty() && fp in FINGERPRINTS) {
                    put("utls", buildJsonObject {
                        put("enabled", true)
                        put("fingerprint", fp)
                    })
                }
                if (allowInsecure) put("insecure", true)
            }
            "reality" -> {
                if (sni.isBlank() || realityPublicKey.isBlank()) return null
                buildJsonObject {
                    put("enabled", true)
                    put("server_name", sni.trim())
                    val fp = fingerprint.trim()
                    if (fp.isNotEmpty() && fp in FINGERPRINTS) {
                        put("utls", buildJsonObject {
                            put("enabled", true)
                            put("fingerprint", fp)
                        })
                    }
                    put("reality", buildJsonObject {
                        put("enabled", true)
                        put("public_key", realityPublicKey.trim())
                        if (realityShortId.isNotBlank()) put("short_id", realityShortId.trim())
                    })
                }
            }
            else -> null
        }
    }

    private fun buildTransport(): JsonObject? = when (network.lowercase()) {
        "tcp" -> null
        "ws" -> buildJsonObject {
            put("type", "ws")
            put("path", path.ifBlank { "/" })
            if (host.isNotBlank()) {
                put("headers", buildJsonObject { put("Host", host.trim()) })
            }
        }
        "grpc" -> buildJsonObject {
            put("type", "grpc")
            if (serviceName.isNotBlank()) put("service_name", serviceName.trim())
        }
        "httpupgrade" -> buildJsonObject {
            put("type", "httpupgrade")
            if (host.isNotBlank()) put("host", host.trim())
            put("path", path.ifBlank { "/" })
        }
        else -> null
    }

    private fun buildMux(): JsonObject = buildJsonObject {
        put("enabled", true)
        put("protocol", "smux")
        put("max_connections", muxConcurrency)
    }

    // ---------- XHTTP-путь: Xray outbound для раннера ----------

    private fun toXhttpParsedServer(label: String): Pair<ParsedServer?, String?> {
        val xrayProtocol = when (protocol) {
            "vless" -> "vless"
            "vmess" -> "vmess"
            "trojan" -> "trojan"
            else -> return null to "XHTTP доступен только для VLESS/VMess/Trojan"
        }
        val settings: JsonObject = when (protocol) {
            "vless" -> buildJsonObject {
                put("vnext", buildJsonArray {
                    add(buildJsonObject {
                        put("address", address.trim())
                        put("port", port)
                        put("users", buildJsonArray {
                            add(buildJsonObject {
                                put("id", uuid.trim())
                                put("encryption", "none")
                                val f = flow.trim()
                                if (f.isNotEmpty() && f != "none") put("flow", f)
                            })
                        })
                    })
                })
            }
            "vmess" -> buildJsonObject {
                put("vnext", buildJsonArray {
                    add(buildJsonObject {
                        put("address", address.trim())
                        put("port", port)
                        put("users", buildJsonArray {
                            add(buildJsonObject {
                                put("id", uuid.trim())
                                put("alterId", alterId)
                                put("security", method.lowercase().ifBlank { "auto" })
                            })
                        })
                    })
                })
            }
            else -> buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject {
                        put("address", address.trim())
                        put("port", port)
                        put("password", password)
                    })
                })
            }
        }
        val streamSettings = buildJsonObject {
            put("network", "xhttp")
            put("security", security.lowercase())
            when (security.lowercase()) {
                "tls" -> put("tlsSettings", buildJsonObject {
                    put("serverName", sni.ifBlank { address.trim() })
                    val alpnList = alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (alpnList.isNotEmpty()) {
                        put("alpn", buildJsonArray { alpnList.forEach { add(it) } })
                    }
                    if (fingerprint.isNotBlank()) put("fingerprint", fingerprint.trim())
                })
                "reality" -> put("realitySettings", buildJsonObject {
                    put("serverName", sni.trim())
                    if (fingerprint.isNotBlank()) put("fingerprint", fingerprint.trim())
                    put("publicKey", realityPublicKey.trim())
                    if (realityShortId.isNotBlank()) put("shortId", realityShortId.trim())
                })
            }
            val xhttp = buildJsonObject {
                if (host.isNotBlank()) put("host", host.trim())
                if (path.isNotBlank()) put("path", path.trim())
                put("mode", xhttpMode)
                parseJsonObject(xhttpExtra)?.let { extra ->
                    for ((key, value) in extra) put(key, value)
                }
            }
            put("xhttpSettings", xhttp)
            parseJsonObject(finalMask)?.let { mask -> put("finalmask", mask) }
        }
        val outbound = buildJsonObject {
            put("tag", label)
            put("protocol", xrayProtocol)
            put("settings", settings)
            put("streamSettings", streamSettings)
            if (muxEnabled) {
                put("mux", buildJsonObject {
                    put("enabled", true)
                    put("concurrency", muxConcurrency)
                })
            }
        }
        return ParsedServer(
            tag = label, label = label, protocol = protocol,
            kind = ServerKind.XHTTP_RUNNER, address = address.trim(),
            singBoxOutbound = null, xrayOutbound = outbound,
        ) to null
    }

    private fun parseJsonObject(text: String): JsonObject? {
        if (text.isBlank()) return null
        return runCatching {
            Json.parseToJsonElement(text).jsonObject
        }.getOrNull()
    }
}

// Безопасная подпись без секретов (для логов/ошибок): протокол + имя.
fun ManualServer.safeHint(): String = "$protocol ${name.trim().take(40)}"
