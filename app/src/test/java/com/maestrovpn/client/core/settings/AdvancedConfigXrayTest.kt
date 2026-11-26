package com.maestrovpn.client.core.settings

import com.maestrovpn.client.core.subscription.ParsedServer
import com.maestrovpn.client.core.subscription.XrayConfigBuilder
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Раздел issue #3: продвинутые настройки реально попадают в Xray-конфиг
 * (DNS, стратегия запросов, порты inbound, MTU туннеля, Mux), а дефолты
 * остаются прежними — регресс на срез 7.
 *
 * Последний тест прогоняет собранный конфиг настоящим `xray run -test`
 * (бинарь из jniLibs); без бинаря он скипается.
 */
class AdvancedConfigXrayTest {

    private fun socksServer(tag: String = "srv1") = ParsedServer(
        tag = tag,
        label = tag,
        protocol = "socks",
        address = "127.0.0.1",
        xrayOutbound = Json.parseToJsonElement(
            """{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":1080}]}}""",
        ).jsonObject,
        network = "tcp",
    )

    private val visionServer = ParsedServer(
        tag = "vision",
        label = "vision",
        protocol = "vless",
        address = "v.example",
        // Реальная форма outbound у парсера: settings.vnext[].users[].flow
        xrayOutbound = Json.parseToJsonElement(
            """{"protocol":"vless","settings":{"vnext":[{"address":"v.example","port":443,""" +
                """"users":[{"id":"11111111-2222-3333-4444-555555555555",""" +
                """"flow":"xtls-rprx-vision","encryption":"none"}]}]}}""",
        ).jsonObject,
        network = "tcp",
    )

    private val wireguardServer = ParsedServer(
        tag = "wg",
        label = "wg",
        protocol = "wireguard",
        address = "wg.example",
        xrayOutbound = Json.parseToJsonElement(
            """{"protocol":"wireguard","settings":{"secretKey":"x","address":["10.0.0.2/32"]}}""",
        ).jsonObject,
        network = "wireguard",
    )

    private fun root(configJson: String): JsonObject = Json.parseToJsonElement(configJson).jsonObject

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.int

    private fun inbound(root: JsonObject, tag: String): JsonObject =
        root.arr("inbounds")!!.map { it.jsonObject }.first { it.text("tag") == tag }

    private fun outbound(root: JsonObject, tag: String): JsonObject =
        root.arr("outbounds")!!.map { it.jsonObject }.first { it.text("tag") == tag }

    private fun dnsServersOf(root: JsonObject): List<String> =
        root.obj("dns")!!.arr("servers")!!.map { (it as JsonPrimitive).content }

    // ---------- Дефолты (регресс среза 7) ----------

    @Test
    fun `defaults keep previous dns mtu and ports and no mux`() {
        val root = root(XrayConfigBuilder.build(listOf(socksServer()), "srv1").configJson)

        assertEquals(listOf("1.1.1.1", "8.8.8.8"), dnsServersOf(root))
        assertEquals("UseIPv4", root.obj("dns")!!.text("queryStrategy"))
        assertEquals(1280, inbound(root, "tun-in").obj("settings")!!.number("mtu"))
        assertNull(outbound(root, "srv1").obj("mux"))
    }

    // ---------- DNS ----------

    @Test
    fun `advanced dns and query strategy are applied`() {
        val advanced = AdvancedConfig(
            remoteDnsPrimary = "9.9.9.9",
            remoteDnsSecondary = "149.112.112.112",
            queryStrategy = AdvancedConfig.QUERY_USE_IP,
        )
        val root = root(XrayConfigBuilder.build(listOf(socksServer()), "srv1", advanced = advanced).configJson)

        assertEquals(listOf("9.9.9.9", "149.112.112.112"), dnsServersOf(root))
        assertEquals("UseIP", root.obj("dns")!!.text("queryStrategy"))
    }

    @Test
    fun `blank dns falls back to defaults instead of empty list`() {
        val advanced = AdvancedConfig(remoteDnsPrimary = "  ", remoteDnsSecondary = "")
        val root = root(XrayConfigBuilder.build(listOf(socksServer()), "srv1", advanced = advanced).configJson)

        assertEquals(listOf("1.1.1.1", "8.8.8.8"), dnsServersOf(root))
    }

    // ---------- Порты и MTU ----------

    @Test
    fun `proxy mode uses advanced socks and http ports`() {
        val advanced = AdvancedConfig(socksPort = 11080, httpPort = 11081)
        val root = root(
            XrayConfigBuilder.build(listOf(socksServer()), "srv1", vpnMode = false, advanced = advanced).configJson,
        )

        assertEquals(11080, inbound(root, "socks-in").number("port"))
        assertEquals(11081, inbound(root, "http-in").number("port"))
    }

    @Test
    fun `tun mtu comes from advanced settings`() {
        val advanced = AdvancedConfig(tunMtu = 1400)
        val root = root(XrayConfigBuilder.build(listOf(socksServer()), "srv1", advanced = advanced).configJson)

        assertEquals(1400, inbound(root, "tun-in").obj("settings")!!.number("mtu"))
    }

    // ---------- Mux ----------

    @Test
    fun `mux is added to multiplexable outbound when enabled`() {
        val advanced = AdvancedConfig(
            muxEnabled = true,
            muxConcurrency = 4,
            muxXudpConcurrency = 6,
            muxXudpBehavior = AdvancedConfig.MUX_ALLOW,
        )
        val mux = outbound(
            root(XrayConfigBuilder.build(listOf(socksServer()), "srv1", advanced = advanced).configJson),
            "srv1",
        ).obj("mux")!!

        assertEquals(true, (mux["enabled"] as JsonPrimitive).content.toBoolean())
        assertEquals(4, mux.number("concurrency"))
        assertEquals(6, mux.number("xudpConcurrency"))
        assertEquals("allow", mux.text("xudpProxyUDP443"))
    }

    @Test
    fun `mux is skipped for vless vision and wireguard outbounds`() {
        val advanced = AdvancedConfig(muxEnabled = true)
        val root = root(
            XrayConfigBuilder.build(
                listOf(socksServer(), visionServer, wireguardServer),
                null,
                advanced = advanced,
            ).configJson,
        )

        // Контроль: у обычного outbound mux есть — значит тест вообще про mux.
        assertNotNull(outbound(root, "srv1").obj("mux"))
        // Vision несовместим с mux, wireguard его не поддерживает вовсе.
        assertNull(outbound(root, "vision").obj("mux"))
        assertNull(outbound(root, "wg").obj("mux"))
    }

    // ---------- Валидаторы (то, чем экран не пускает мусор в БД) ----------

    @Test
    fun `validators reject out of range values`() {
        assertTrue(AdvancedConfig.isValidPort("10808"))
        assertTrue(AdvancedConfig.isValidPort("1"))
        assertTrue(AdvancedConfig.isValidPort("65535"))
        assertFalse(AdvancedConfig.isValidPort("0"))
        assertFalse(AdvancedConfig.isValidPort("65536"))
        assertFalse(AdvancedConfig.isValidPort(""))
        assertFalse(AdvancedConfig.isValidPort("10 80"))

        assertTrue(AdvancedConfig.isValidMtu("1280"))
        assertTrue(AdvancedConfig.isValidMtu("9000"))
        assertFalse(AdvancedConfig.isValidMtu("1279"))
        assertFalse(AdvancedConfig.isValidMtu("9001"))

        assertTrue(AdvancedConfig.isValidMuxConcurrency("0"))
        assertTrue(AdvancedConfig.isValidMuxConcurrency("1024"))
        assertFalse(AdvancedConfig.isValidMuxConcurrency("1025"))

        assertTrue(AdvancedConfig.isValidDnsServer("1.1.1.1"))
        assertTrue(AdvancedConfig.isValidDnsServer("https://dns.google/dns-query"))
        assertFalse(AdvancedConfig.isValidDnsServer(""))
        assertFalse(AdvancedConfig.isValidDnsServer("1.1.1.1 8.8.8.8"))
    }

    @Test
    fun `dns servers list drops blanks and keeps default when empty`() {
        assertEquals(listOf("1.1.1.1", "8.8.8.8"), AdvancedConfig.DEFAULT.dnsServers())
        assertEquals(
            listOf("9.9.9.9"),
            AdvancedConfig(remoteDnsPrimary = "9.9.9.9", remoteDnsSecondary = "").dnsServers(),
        )
    }

    // ---------- Настоящее ядро ----------

    private val binary: File?
        get() = listOf(
            "src/main/jniLibs/arm64-v8a/libmaestro_subxhttp.so",
            "src/main/jniLibs/x86_64/libmaestro_subxhttp.so",
        ).map { File(it) }.firstOrNull { it.canExecute() || it.setExecutable(true) }

    @Test
    fun `config with advanced settings and mux passes xray run -test`() {
        val bin = binary
        if (bin == null) {
            assumeTrue("xray binary missing", false)
            return
        }
        val geoip = File("src/main/assets/geoip.dat")
        if (geoip.exists()) {
            val target = File(bin.parentFile, "geoip.dat")
            if (!target.exists() || target.length() != geoip.length()) {
                geoip.copyTo(target, overwrite = true)
            }
        }
        val advanced = AdvancedConfig(
            remoteDnsPrimary = "9.9.9.9",
            remoteDnsSecondary = "149.112.112.112",
            tunMtu = 1400,
            muxEnabled = true,
            muxConcurrency = 4,
            muxXudpConcurrency = 6,
            muxXudpBehavior = AdvancedConfig.MUX_SKIP,
        )
        val config = XrayConfigBuilder.build(
            servers = listOf(socksServer(), visionServer),
            selectedTag = "srv1",
            advanced = advanced,
        )
        val tmp = Files.createTempFile("xray-advanced-", ".json").toFile()
        tmp.writeText(config.configJson)
        try {
            val proc = ProcessBuilder(bin.absolutePath, "run", "-test", "-c", tmp.absolutePath)
                .redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(output, 0, proc.exitValue())
        } finally {
            tmp.delete()
        }
    }
}