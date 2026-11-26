package com.maestrovpn.client.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

// Подмена tun-inbound для проверки конфига (xray run -test).
//
// Дефект 26.11.2026: на Android -test не может собрать inbound protocol tun —
// он читает системную информацию об интерфейсах и падает с
// "fail to get system interface information", даже когда конфиг верный.
// Настоящий tun создаётся только в VpnService (fd передаётся через XRAY_TUN_FD),
// а проверять надо сборку outbound'ов и правил — поэтому на время проверки
// tun заменяется обычным socks-inbound с тем же тегом (правила маршрутизации
// ссылаются на туннель по тегу и должны остаться валидными).
object XrayValidationConfig {

    /** Порт подменного inbound: только для -test, наружу не открывается. */
    private const val PROBE_PORT = 11809

    private val json = Json { prettyPrint = false }

    /** Поля tun, которых у socks быть не должно. */
    private val STUB_DROPS = setOf("settings", "protocol", "listen", "port")

    /**
     * Вернуть конфиг, пригодный для xray run -test на Android: все inbound'ы
     * с protocol=tun заменены на socks с тем же тегом. Невалидный JSON
     * возвращается как есть — причину покажет само ядро.
     */
    fun forValidation(configJson: String): String {
        val root = runCatching { json.parseToJsonElement(configJson).jsonObject }.getOrNull()
            ?: return configJson
        val inbounds = root["inbounds"] as? JsonArray ?: return configJson
        val hasTun = inbounds.any { element ->
            val protocol = (element as? JsonObject)?.get("protocol") as? JsonPrimitive
            protocol?.content == "tun"
        }
        if (!hasTun) return configJson
        val replaced = JsonArray(
            inbounds.map { element ->
                val obj = element as? JsonObject ?: return@map element
                val protocol = (obj["protocol"] as? JsonPrimitive)?.content
                if (protocol != "tun") obj else socksStub(obj)
            },
        )
        val patched = buildJsonObject {
            root.forEach { (key, value) ->
                if (key == "inbounds") put(key, replaced) else put(key, value)
            }
        }
        return patched.toString()
    }

    private fun socksStub(tun: JsonObject): JsonObject = buildJsonObject {
        tun.forEach { (key, value) -> if (key !in STUB_DROPS) put(key, value) }
        put("protocol", "socks")
        put("listen", "127.0.0.1")
        put("port", PROBE_PORT)
        put("settings", buildJsonObject {
            put("auth", "noauth")
            put("udp", false)
        })
    }
}
