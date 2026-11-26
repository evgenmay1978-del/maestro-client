package com.maestrovpn.client.core.subscription

import com.maestrovpn.client.core.routing.RoutingProfile
import com.maestrovpn.client.core.settings.AdvancedConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

/**
 * Причина 2 дефекта 26.11.2026 «трафик не идёт»: DNS уходил из туннеля как
 * обычный UDP через прокси, замыкания маршрута не было вовсе (поле
 * routing.final ядро игнорирует), DoH-серверы задавались доменными именами,
 * FakeDNS лежал в dns-секции, где ядро его не читает.
 *
 * Здесь зафиксирован конфиг, который проверен бинарём Xray 26.11.9.
 */
class XrayConfigDnsRoutingTest {

    private fun server(tag: String = "srv1") = ParsedServer(
        tag = tag,
        label = tag,
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

    private fun outbound(root: JsonObject, tag: String): JsonObject =
        root.arr("outbounds")!!.map { it.jsonObject }.first { it.text("tag") == tag }

    private fun outboundTags(root: JsonObject): List<String> =
        root.arr("outbounds")!!.map { it.jsonObject.text("tag")!! }

    private fun rules(root: JsonObject): List<JsonObject> =
        root.obj("routing")!!.arr("rules")!!.map { it.jsonObject }

    private fun build(
        profile: RoutingProfile? = null,
        advanced: AdvancedConfig = AdvancedConfig.DEFAULT,
        selected: String? = "srv1",
        vpnMode: Boolean = true,
    ): JsonObject = root(
        XrayConfigBuilder.build(
            listOf(server("srv1"), server("srv2")),
            selected,
            vpnMode = vpnMode,
            routingProfile = profile,
            advanced = advanced,
        ).configJson,
    )

    @Test
    fun `dns interception rule goes first`() {
        val first = rules(build()).first()
        assertEquals("tun-in", first.arr("inboundTag")!![0].jsonPrimitive.content)
        assertEquals(53, first.number("port"))
        assertEquals("tcp,udp", first.text("network"))
        assertEquals("dns", first.text("outboundTag"))
    }

    @Test
    fun `dns outbound exists and forwards to configured resolver`() {
        val dns = outbound(build(), "dns")
        assertEquals("dns", dns.text("protocol"))
        assertEquals("1.1.1.1", dns.obj("settings")!!.text("address"))
        assertEquals(53, dns.obj("settings")!!.number("port"))
    }

    @Test
    fun `dns outbound takes ip from routing profile`() {
        val profile = RoutingProfile(id = "p", name = "p", remoteDnsIp = "9.9.9.9")
        assertEquals("9.9.9.9", outbound(build(profile), "dns").obj("settings")!!.text("address"))
    }

    @Test
    fun `no routing final field`() {
        val routing = build().obj("routing")!!
        assertNull("routing.final ядру неизвестен — трафик уходил в первый outbound", routing["final"])
    }

    @Test
    fun `catch all rule is last and points to selected outbound`() {
        val all = rules(build())
        val last = all.last()
        assertEquals("field", last.text("type"))
        assertEquals("tcp,udp", last.text("network"))
        assertEquals("srv1", last.text("outboundTag"))
        assertTrue(outboundTags(build()).contains(last.text("outboundTag")))
    }

    @Test
    fun `auto mode uses balancer in catch all`() {
        val config = build(selected = null)
        val last = rules(config).last()
        assertNull(last.text("outboundTag"))
        assertEquals("auto", last.text("balancerTag"))
        val balancer = config.obj("routing")!!.arr("balancers")!!
            .map { it.jsonObject }.first { it.text("tag") == "auto" }
        assertEquals("leastPing", balancer.obj("strategy")!!.text("type"))
    }

    @Test
    fun `profile rules keep block proxy direct order before catch all`() {
        val profile = RoutingProfile(
            id = "p",
            name = "p",
            globalProxy = false,
            blockSites = listOf("geosite:ads"),
            proxySites = listOf("domain:proxy.me"),
            directSites = listOf("domain:example.com"),
        )
        val all = rules(build(profile))
        val tags = all.map { it.text("outboundTag") }
        // [0] DNS, [1] api, [2] приватные сети, [3] block, [4] proxy, [5] direct, [6] замыкающее
        assertEquals("block", tags[3])
        assertEquals("srv1", tags[4])
        assertEquals("direct", tags[5])
        assertEquals("srv1", tags.last())
        assertEquals(7, all.size)
    }

    @Test
    fun `doh servers are ip literals`() {
        val profile = RoutingProfile(id = "p", name = "p")
        val servers = build(profile).obj("dns")!!.arr("servers")!!
            .map { it.jsonPrimitive.content }
        assertEquals("https://1.1.1.1/dns-query", servers[0])
        assertEquals("https://8.8.8.8/dns-query", servers[1])
    }

    @Test
    fun `query strategy comes from advanced settings`() {
        val advanced = AdvancedConfig.DEFAULT.copy(queryStrategy = AdvancedConfig.QUERY_USE_IP)
        val profile = RoutingProfile(id = "p", name = "p")
        assertEquals("UseIP", build(profile, advanced).obj("dns")!!.text("queryStrategy"))
    }

    @Test
    fun `fake dns is top level and sniffing overrides it`() {
        val profile = RoutingProfile(id = "p", name = "p", fakeDns = true)
        val config = build(profile)
        val fake = config.obj("fakeDns")
        assertNotNull("fakeDns обязан быть на верхнем уровне", fake)
        assertEquals("198.18.0.0/16", fake!!.text("ipPool"))
        assertNull("в dns-секции ядро fakedns не читает", config.obj("dns")!!["fakedns"])

        val dnsServers = config.obj("dns")!!.arr("servers")!!.map { it.jsonPrimitive.content }
        assertTrue(dnsServers.contains("fakedns"))

        // Без fakedns в destOverride ядро пишет Warning и домены не подставляет.
        val tunSniffing = config.arr("inbounds")!!.map { it.jsonObject }
            .first { it.text("tag") == "tun-in" }.obj("sniffing")!!
        val overrides = tunSniffing.arr("destOverride")!!.map { it.jsonPrimitive.content }
        assertTrue(overrides.contains("fakedns"))
    }

    @Test
    fun `no fake dns section when disabled`() {
        val config = build(RoutingProfile(id = "p", name = "p"))
        assertNull(config["fakeDns"])
        val overrides = config.arr("inbounds")!!.map { it.jsonObject }
            .first { it.text("tag") == "tun-in" }.obj("sniffing")!!
            .arr("destOverride")!!.map { it.jsonPrimitive.content }
        assertFalse(overrides.contains("fakedns"))
    }

    @Test
    fun `proxy mode has no dns interception rule`() {
        val config = build(vpnMode = false)
        // tun-in в proxy-режиме отсутствует: правило на него бессмысленно.
        assertFalse(
            rules(config).any { rule ->
                rule.arr("inboundTag")?.any { it.jsonPrimitive.content == "tun-in" } == true
            },
        )
        // Сам DNS-форвардер остаётся в outbounds — он не про tun.
        assertEquals("dns", outbound(config, "dns").text("protocol"))
    }
// ---------- Настоящее ядро ----------

    private val binary: File?
        get() = listOf(
            "src/main/jniLibs/arm64-v8a/libmaestro_subxhttp.so",
            "src/main/jniLibs/x86_64/libmaestro_subxhttp.so",
        ).map { File(it) }.firstOrNull { it.canExecute() || it.setExecutable(true) }

    /** Прогон собранного конфига настоящим ядром: -test ловит ошибки схемы. */
    private fun assertCoreAccepts(configJson: String, name: String) {
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
        val tmp = Files.createTempFile(name, ".json").toFile()
        tmp.writeText(configJson)
        // копия для ручной проверки: build/xray-<name>.json
        runCatching { tmp.copyTo(File("build", "xray-" + name + ".json"), overwrite = true) }
        try {
            val proc = ProcessBuilder(bin.absolutePath, "run", "-test", "-c", tmp.absolutePath)
                .redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor(60, TimeUnit.SECONDS)
            assertEquals(output, 0, proc.exitValue())
            assertTrue(output, output.contains("Configuration OK"))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `vpn config with dns interception and fake dns passes xray run -test`() {
        val profile = RoutingProfile(
            id = "p",
            name = "p",
            globalProxy = false,
            blockSites = listOf("domain:ads.example"),
            directSites = listOf("domain:example.com"),
            fakeDns = true,
        )
        assertCoreAccepts(
            XrayConfigBuilder.build(
                listOf(server("srv1"), server("srv2")),
                "srv1",
                routingProfile = profile,
            ).configJson,
            "xray-stop-dns-vpn-",
        )
    }

    @Test
    fun `proxy config with fake dns passes xray run -test`() {
        val profile = RoutingProfile(id = "p", name = "p", fakeDns = true)
        assertCoreAccepts(
            XrayConfigBuilder.build(
                listOf(server("srv1")),
                "srv1",
                vpnMode = false,
                routingProfile = profile,
            ).configJson,
            "xray-stop-dns-proxy-",
        )
    }
}
