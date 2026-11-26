package com.maestrovpn.client.core.subscription

import com.maestrovpn.client.core.settings.AdvancedConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регресс 26.11.2026: inbound protocol=tun без имени заставлял ядро искать
 * интерфейс через netlink, а приложению netlink запрещён — старт падал с
 * «fail to get system interface information». Здесь зафиксировано:
 *  - у tun inbound есть name (ядро не перечисляет интерфейсы), нет address,
 *    strictRoute и interfaceName — полей, которых в схеме TunConfig нет;
 *  - api inbound и правило routing на него остаются на месте.
 */
class XrayConfigTunInboundTest {

    private fun server() = ParsedServer(
        tag = "srv1",
        label = "srv1",
        protocol = "socks",
        address = "127.0.0.1",
        xrayOutbound = Json.parseToJsonElement(
            """{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":1080}]}}""",
        ).jsonObject,
        network = "tcp",
    )

    private fun root(configJson: String): JsonObject = Json.parseToJsonElement(configJson).jsonObject

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.int

    private fun inbound(root: JsonObject, tag: String): JsonObject =
        root.arr("inbounds")!!.map { it.jsonObject }.first { it.text("tag") == tag }

    private fun tunRoot(advanced: AdvancedConfig = AdvancedConfig.DEFAULT): JsonObject =
        root(XrayConfigBuilder.build(listOf(server()), "srv1", advanced = advanced).configJson)

    @Test
    fun tunInboundHasNameAndNoInterfaceLookupFields() {
        val tun = inbound(tunRoot(), "tun-in")
        val settings = tun.obj("settings")!!

        val name = settings.text("name")
        assertNotNull("у tun inbound нет name — ядро пойдёт в netlink", name)
        assertTrue(name!!.isNotBlank())

        assertNull("address не входит в схему TunConfig", settings["address"])
        assertNull("strictRoute не входит в схему TunConfig", settings["strictRoute"])
        assertNull("interfaceName тянет поиск интерфейса", settings["interfaceName"])
        assertNull(tun["interfaceName"])
    }

    @Test
    fun tunMtuStaysFromAdvancedSettings() {
        val root = tunRoot(AdvancedConfig(tunMtu = 1400))
        assertEquals(1400, inbound(root, "tun-in").obj("settings")!!.number("mtu"))
    }

    @Test
    fun apiInboundAndItsRoutingRuleSurvive() {
        val root = tunRoot()
        val api = inbound(root, "api")
        assertEquals(10085, api.number("port"))
        assertEquals("127.0.0.1", api.text("listen"))

        val rules = root.obj("routing")!!.arr("rules")!!
        val apiRule = rules.map { it.jsonObject }.firstOrNull { r ->
            r.arr("inboundTag")?.any { (it as JsonPrimitive).content == "api" } == true
        }
        assertNotNull("пропало правило routing для api", apiRule)
        assertEquals("api", apiRule!!.text("outboundTag"))
    }

    @Test
    fun tunInboundAbsentInProxyMode() {
        val root = root(XrayConfigBuilder.build(listOf(server()), "srv1", vpnMode = false).configJson)
        val tags = root.arr("inbounds")!!.map { it.jsonObject.text("tag") }
        assertFalse(tags.contains("tun-in"))
        assertTrue(tags.contains("socks-in"))
    }
}
