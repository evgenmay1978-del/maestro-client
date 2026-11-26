package com.maestrovpn.client.core.engine

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Проверка подмены tun-inbound перед xray run -test (дефект 26.11.2026):
// на Android ядро не может собрать tun inbound в режиме проверки.
class XrayValidationConfigTest {

    private val json = Json { prettyPrint = false }

    private val vpnConfig = """
        {
          "inbounds": [
            {"tag": "api", "listen": "127.0.0.1", "port": 10085, "protocol": "dokodemo-door",
             "settings": {"address": "127.0.0.1"}},
            {"tag": "tun-in", "protocol": "tun",
             "settings": {"address": "172.19.0.1/30", "mtu": 1280, "strictRoute": true},
             "sniffing": {"enabled": true, "destOverride": ["http", "tls"]}}
          ],
          "outbounds": [{"tag": "proxy", "protocol": "freedom"}],
          "routing": {"rules": [{"type": "field", "inboundTag": ["tun-in"], "outboundTag": "proxy"}]}
        }
    """.trimIndent()

    @Test
    fun tunInboundReplacedWithSocksKeepingTag() {
        val patched = json.parseToJsonElement(XrayValidationConfig.forValidation(vpnConfig)).jsonObject
        val inbounds = patched["inbounds"]!!.jsonArray
        val tun = inbounds.first { it.jsonObject["tag"]?.let { t -> (t as JsonPrimitive).content } == "tun-in" }
            .jsonObject
        assertEquals("socks", (tun["protocol"] as JsonPrimitive).content)
        assertEquals("127.0.0.1", (tun["listen"] as JsonPrimitive).content)
        assertNotNull(tun["port"])
        val settings = tun["settings"] as JsonObject
        assertEquals("noauth", (settings["auth"] as JsonPrimitive).content)
        assertTrue("mtu не должен переезжать в socks", settings["mtu"] == null)
        assertNotNull("sniffing остаётся", tun["sniffing"])
    }

    @Test
    fun outboundsAndRoutingUntouched() {
        val patched = json.parseToJsonElement(XrayValidationConfig.forValidation(vpnConfig)).jsonObject
        val outbounds = patched["outbounds"]!!.jsonArray
        assertEquals(1, outbounds.size)
        assertEquals("freedom", (outbounds[0].jsonObject["protocol"] as JsonPrimitive).content)
        val rule = patched["routing"]!!.jsonObject["rules"]!!.jsonArray[0].jsonObject
        assertEquals("tun-in", (rule["inboundTag"]!!.jsonArray[0] as JsonPrimitive).content)
    }

    @Test
    fun proxyOnlyConfigUnchanged() {
        val proxyOnly = """{"inbounds":[{"tag":"socks-in","protocol":"socks","listen":"127.0.0.1","port":10808}],"outbounds":[]}"""
        assertEquals(proxyOnly, XrayValidationConfig.forValidation(proxyOnly))
    }

    @Test
    fun brokenJsonReturnedAsIs() {
        val broken = "{ not json"
        assertEquals(broken, XrayValidationConfig.forValidation(broken))
    }
}
