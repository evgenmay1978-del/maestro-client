package com.maestrovpn.client.core.subscription

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.util.UUID

// Ручной сервер (срез 5, issue #1; переведён на Xray-only ядро в срезе 7).
// Модель — поля редактора Happ (карта happ-4.11.2-map.md §2) в том объёме,
// который исполняет ядро Xray-core (таблица соответствия §2).
//
// Что отображается в Xray outbound (toParsedServer):
//   VLESS/VMess/Trojan — settings + streamSettings (транспорт tcp/ws/grpc/
//   httpupgrade/xhttp; security none/tls/reality; mux) — один общий билдер
//   для всех сетей, включая xhttp (раньше xhttp шёл отдельным путём);
//   Shadowsocks — settings.servers (method/password), mux;
//   SOCKS — settings.servers (user/pass), mux;
//   WireGuard — Xray wireguard outbound: settings.secretKey/address/peers
//   (endpoint "host:port", allowedIPs), mtu, reserved, noKernelTun;
//   Hysteria2 — протокол "hysteria", version 2: settings (address/port),
//   streamSettings (network hysteria, tlsSettings, hysteriaSettings с
//   auth/udpIdleTimeout, udpmasks salamander; скорости — в
//   finalmask.quicParams.brutalUp/brutalDown, Port Hopping — в
//   finalmask.udp как udphop).
// Что НЕ исполняется и потому блокирует сохранение с понятной ошибкой:
//   kcp-транспорт и его поля mtu/tti/cwnd — редактор их не предлагает
//   (в Xray mKCP есть, но в модели редактора полей под него нет);
//   gRPC mode multi — только с транспортом gRPC (grpcSettings.multiMode).
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
    /** gun | multi — grpcSettings.multiMode (Xray поддерживает multi). */
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
    /** Port hopping (finalmask.udp udphop): диапазон "20000-50000" или
     *  список портов через запятую; интервал — секунды (Xray: минимум 5). */
    val hopPorts: String = "",
    val hopInterval: String = "",
    /** UDP idle timeout, секунды (Xray: 2–600, по умолчанию 60). */
    val udpIdleTimeout: String = "",
    // XHTTP (только network == xhttp).
    /** auto | packet-up | stream-up | stream-one */
    val xhttpMode: String = "auto",
    /** Сырой JSON-объект, сливается в xhttpSettings (например {"xPaddingBytes":"100-1000"}). */
    val xhttpExtra: String = "",
    /** Сырой JSON-объект finalmask для streamSettings. */
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

        /** "20000-50000" или "20000,20001,30000" — порты 1..65535, диапазон lo<=hi. */
        fun validHopPorts(text: String): Boolean {
            val parts = text.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.isEmpty()) return false
            for (part in parts) {
                if (part.contains("-")) {
                    val bounds = part.split("-", limit = 2)
                    if (bounds.size != 2) return false
                    val lo = bounds[0].trim().toIntOrNull() ?: return false
                    val hi = bounds[1].trim().toIntOrNull() ?: return false
                    if (lo !in 1..65535 || hi !in 1..65535 || lo > hi) return false
                } else {
                    val p = part.toIntOrNull() ?: return false
                    if (p !in 1..65535) return false
                }
            }
            return true
        }
    }

    /** Тег сервера в конфиге — имя (уникальность проверяет хранилище). */
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
            if (fingerprint.isBlank()) errors += "Reality: укажите отпечаток (uTLS)"
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
                    errors += "Shadowsocks — только TCP (транспорт $network не поддерживается Xray)"
                }
                if (security != "none") errors += "Shadowsocks: TLS/Reality не применяется"
                if (flow.isNotBlank() && flow != "none") errors += "Flow — только для VLESS"
            }
            "socks" -> {
                if (network != "tcp") {
                    errors += "SOCKS — только TCP (транспорт $network не поддерживается Xray)"
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
                if (hopPorts.isNotBlank() && !validHopPorts(hopPorts)) {
                    errors += "Hysteria2: Port Hopping — порты вида «20000-50000» или список через запятую"
                }
                if (hopInterval.isNotBlank()) {
                    val iv = hopInterval.trim().toIntOrNull()
                    if (iv == null || iv !in 5..600) {
                        errors += "Hysteria2: интервал Port Hopping — секунды от 5 до 600"
                    }
                }
                if (udpIdleTimeout.isNotBlank()) {
                    val t = udpIdleTimeout.trim().toIntOrNull()
                    if (t == null || t !in 2..600) {
                        errors += "Hysteria2: UDP idle timeout — секунды от 2 до 600"
                    }
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
            if (grpcMode == "multi" && network != "grpc") {
                errors += "gRPC mode multi применяется только с транспортом gRPC"
            }
        }
        return errors
    }

    /**
     * Сервер -> ParsedServer для XrayConfigBuilder (та же модель, что у подписок).
     * null + причина — когда комбинация не исполняется ядром.
     */
    fun toParsedServer(): Pair<ParsedServer?, String?> {
        val errors = validate()
        if (errors.isNotEmpty()) return null to errors.first()
        val label = tag
        val addressTrimmed = address.trim()
        return when (protocol) {
            "wireguard" -> {
                val outbound = buildJsonObject {
                    put("protocol", "wireguard")
                    put("settings", buildJsonObject {
                        put("secretKey", privateKey.trim())
                        put("address", buildJsonArray {
                            localAddress.split(",").map { it.trim() }
                                .filter { it.isNotEmpty() }.forEach { add(it) }
                        })
                        put("peers", buildJsonArray {
                            add(buildJsonObject {
                                put("endpoint", "$addressTrimmed:$port")
                                put("publicKey", publicKey.trim())
                                put("allowedIPs", buildJsonArray {
                                    allowedIps.split(",").map { it.trim() }
                                        .filter { it.isNotEmpty() }.forEach { add(it) }
                                })
                                if (preSharedKey.isNotBlank()) {
                                    put("preSharedKey", preSharedKey.trim())
                                }
                            })
                        })
                        // На Android у Xray нет CAP_NET_ADMIN — только
                        // встроенный стек, без попытки поднять kernel TUN.
                        put("noKernelTun", true)
                        put("mtu", mtu)
                        val reservedParts = reserved.trim()
                        if (reservedParts.isNotEmpty()) {
                            put("reserved", buildJsonArray {
                                reservedParts.split(",").forEach { add(it.trim().toInt()) }
                            })
                        }
                    })
                }
                ParsedServer(
                    tag = label, label = label, protocol = "wireguard",
                    address = addressTrimmed, xrayOutbound = outbound,
                    network = "wireguard",
                ) to null
            }
            "hysteria2" -> {
                val outbound = buildHysteria2Outbound(addressTrimmed)
                ParsedServer(
                    tag = label, label = label, protocol = "hysteria2",
                    address = addressTrimmed, xrayOutbound = outbound,
                    network = "hysteria",
                ) to null
            }
            "shadowsocks" -> {
                val outbound = buildJsonObject {
                    put("protocol", "shadowsocks")
                    put("settings", buildJsonObject {
                        put("servers", buildJsonArray {
                            add(buildJsonObject {
                                put("address", addressTrimmed)
                                put("port", port)
                                put("method", method)
                                put("password", password)
                            })
                        })
                    })
                    if (muxEnabled) put("mux", buildMux())
                }
                ParsedServer(
                    tag = label, label = label, protocol = "shadowsocks",
                    address = addressTrimmed, xrayOutbound = outbound,
                    network = "tcp",
                ) to null
            }
            "socks" -> {
                val outbound = buildJsonObject {
                    put("protocol", "socks")
                    put("settings", buildJsonObject {
                        put("servers", buildJsonArray {
                            add(buildJsonObject {
                                put("address", addressTrimmed)
                                put("port", port)
                                if (username.isNotBlank() || password.isNotBlank()) {
                                    put("users", buildJsonArray {
                                        add(buildJsonObject {
                                            if (username.isNotBlank()) put("user", username)
                                            if (password.isNotBlank()) put("pass", password)
                                        })
                                    })
                                }
                            })
                        })
                    })
                    if (muxEnabled) put("mux", buildMux())
                }
                ParsedServer(
                    tag = label, label = label, protocol = "socks",
                    address = addressTrimmed, xrayOutbound = outbound,
                    network = "tcp",
                ) to null
            }
            "vless", "vmess", "trojan" -> {
                val settings: JsonObject = when (protocol) {
                    "vless" -> buildJsonObject {
                        put("vnext", buildJsonArray {
                            add(buildJsonObject {
                                put("address", addressTrimmed)
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
                                put("address", addressTrimmed)
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
                                put("address", addressTrimmed)
                                put("port", port)
                                put("password", password)
                            })
                        })
                    }
                }
                val outbound = buildJsonObject {
                    put("protocol", protocol)
                    put("settings", settings)
                    put("streamSettings", buildStreamSettings(addressTrimmed))
                    if (muxEnabled) put("mux", buildMux())
                }
                ParsedServer(
                    tag = label, label = label, protocol = protocol,
                    address = addressTrimmed, xrayOutbound = outbound,
                    network = network.lowercase(),
                ) to null
            }
            else -> null to "неизвестный протокол: $protocol"
        }
    }

    // ---------- Xray streamSettings (VLESS/VMess/Trojan, все сети) ----------

    private fun buildStreamSettings(serverAddress: String): JsonObject {
        val net = network.lowercase()
        val sec = security.lowercase()
        return buildJsonObject {
            put("network", net)
            put("security", sec)
            when (sec) {
                "tls" -> put("tlsSettings", buildJsonObject {
                    put("serverName", sni.ifBlank { serverAddress })
                    val alpnList = alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (alpnList.isNotEmpty()) {
                        put("alpn", buildJsonArray { alpnList.forEach { add(it) } })
                    }
                    val fp = fingerprint.trim()
                    if (fp.isNotEmpty()) put("fingerprint", fp)
                    if (allowInsecure) put("allowInsecure", true)
                })
                "reality" -> put("realitySettings", buildJsonObject {
                    put("serverName", sni.trim())
                    val fp = fingerprint.trim()
                    if (fp.isNotEmpty()) put("fingerprint", fp)
                    put("publicKey", realityPublicKey.trim())
                    if (realityShortId.isNotBlank()) put("shortId", realityShortId.trim())
                })
            }
            when (net) {
                "ws" -> put("wsSettings", buildJsonObject {
                    put("path", path.ifBlank { "/" })
                    if (host.isNotBlank()) {
                        put("headers", buildJsonObject { put("Host", host.trim()) })
                    }
                })
                "grpc" -> put("grpcSettings", buildJsonObject {
                    if (serviceName.isNotBlank()) put("serviceName", serviceName.trim())
                    // Xray: grpcSettings.multiMode (проверено по документации).
                    if (grpcMode == "multi") put("multiMode", true)
                })
                "httpupgrade" -> put("httpupgradeSettings", buildJsonObject {
                    if (host.isNotBlank()) put("host", host.trim())
                    put("path", path.ifBlank { "/" })
                })
                "xhttp" -> {
                    put("xhttpSettings", buildJsonObject {
                        if (host.isNotBlank()) put("host", host.trim())
                        if (path.isNotBlank()) put("path", path.trim())
                        put("mode", xhttpMode)
                        parseJsonObject(xhttpExtra)?.let { extra ->
                            for ((key, value) in extra) put(key, value)
                        }
                    })
                    parseJsonObject(finalMask)?.let { mask -> put("finalmask", mask) }
                }
            }
        }
    }

    /** Xray mux: {"enabled": true, "concurrency": N}. */
    private fun buildMux(): JsonObject = buildJsonObject {
        put("enabled", true)
        put("concurrency", muxConcurrency)
    }

    // ---------- Hysteria2: Xray outbound (протокол "hysteria", version 2) ----------

    private fun buildHysteria2Outbound(serverAddress: String): JsonObject {
        // Поля — по документации Xray для 26.11.9 (xtls.github.io, transport
        // hysteria + core-tutorial, pinned v26.11.9): hysteriaSettings несёт
        // только version/auth/udpIdleTimeout; up/down — finalmask.quicParams
        // brutalUp/brutalDown, Port Hopping — finalmask.udp udphop.
        val up = parseMbps(upMbps)?.let { "${it}mbps" }
        val down = parseMbps(downMbps)?.let { "${it}mbps" }
        return buildJsonObject {
            put("protocol", "hysteria")
            put("settings", buildJsonObject {
                put("version", 2)
                put("address", serverAddress)
                put("port", port)
            })
            put("streamSettings", buildJsonObject {
                put("network", "hysteria")
                put("security", "tls")
                put("tlsSettings", buildJsonObject {
                    put("serverName", sni.ifBlank { serverAddress })
                    val alpnList = alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    put("alpn", buildJsonArray {
                        if (alpnList.isNotEmpty()) alpnList.forEach { add(it) } else add("h3")
                    })
                    val fp = fingerprint.trim()
                    if (fp.isNotEmpty()) put("fingerprint", fp)
                    if (allowInsecure) put("allowInsecure", true)
                })
                put("hysteriaSettings", buildJsonObject {
                    put("version", 2)
                    put("auth", password)
                    udpIdleTimeout.trim().toIntOrNull()?.let { put("udpIdleTimeout", it) }
                })
                // Скорости и Port Hopping — НЕ поля hysteriaSettings (Xray
                // 26.11.9 молча игнорирует неизвестные ключи там): они живут
                // в streamSettings.finalmask — brutalUp/brutalDown в
                // quicParams, udphop — первой записью в finalmask.udp
                // (mode intervalRemote: меняется порт назначения из
                // remotePorts каждые interval секунд, минимум 5).
                val finalMask = buildJsonObject {
                    if (up != null || down != null) {
                        put("quicParams", buildJsonObject {
                            if (up != null) put("brutalUp", up)
                            if (down != null) put("brutalDown", down)
                        })
                    }
                    if (hopPorts.isNotBlank()) {
                        put("udp", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "udphop")
                                put("settings", buildJsonObject {
                                    put("mode", "intervalRemote")
                                    put("remotePorts", hopPorts.trim())
                                    hopInterval.trim().toIntOrNull()?.let { put("interval", it) }
                                })
                            })
                        })
                    }
                }
                if (finalMask.isNotEmpty()) put("finalmask", finalMask)
                if (obfsPassword.isNotBlank()) {
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
