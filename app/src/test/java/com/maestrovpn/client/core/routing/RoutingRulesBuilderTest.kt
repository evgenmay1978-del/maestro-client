package com.maestrovpn.client.core.routing

import com.maestrovpn.client.core.settings.AdvancedConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Раздел 6/7: профиль маршрутизации → Xray routing.rules + dns.
 */
class RoutingRulesBuilderTest {

    private val profile = RoutingProfile(
        id = "p1",
        name = "Test",
        directSites = listOf("domain:example.com"),
        directIp = listOf("10.0.0.0/8"),
        blockSites = listOf("geosite:ads"),
        proxySites = listOf("domain:proxy.me"),
        dnsHosts = mapOf("dns.google" to "8.8.8.8"),
    )

    /** Замыкающее правило «всё остальное — в конкретный сервер». */
    private val catchAll = RoutingRulesBuilder.CatchAll(outboundTag = "server-1")

    @Test
    fun `rules are ordered block proxy direct`() {
        val rules = RoutingRulesBuilder.rules(profile, catchAll)
        val tags = rules.map { it.jsonObject["outboundTag"] }
        // globalProxy=true по умолчанию → proxy-правила пропущены.
        assertEquals(2, rules.size)
        assertEquals(JsonPrimitive("block"), tags[0])
        assertEquals(JsonPrimitive("direct"), tags[1])
    }

    @Test
    fun `proxy rules appear when globalProxy off and point to selected server`() {
        val rules = RoutingRulesBuilder.rules(profile.copy(globalProxy = false), catchAll)
        val tags = rules.map { it.jsonObject["outboundTag"] }
        assertEquals(3, rules.size)
        assertEquals(JsonPrimitive("block"), tags[0])
        // Раньше здесь стоял несуществующий outbound "proxy" — ядро такое
        // правило отбрасывает; теперь тег берётся из замыкающего правила.
        assertEquals(JsonPrimitive("server-1"), tags[1])
        assertEquals(JsonPrimitive("direct"), tags[2])
    }

    @Test
    fun `proxy rules use balancer when auto mode`() {
        val rules = RoutingRulesBuilder.rules(
            profile.copy(globalProxy = false),
            RoutingRulesBuilder.CatchAll(balancerTag = "auto"),
        )
        val proxy = rules[1].jsonObject
        assertNull(proxy["outboundTag"])
        assertEquals(JsonPrimitive("auto"), proxy["balancerTag"])
    }

    @Test
    fun `empty profile has no rules`() {
        val rules = RoutingRulesBuilder.rules(RoutingProfile.empty("x"), catchAll)
        assertTrue(rules.isEmpty())
    }

    @Test
    fun `rule carries domains and ips`() {
        val rules = RoutingRulesBuilder.rules(profile, catchAll)
        val direct = rules[1].jsonObject
        assertEquals(
            JsonArray(listOf(JsonPrimitive("domain:example.com"))),
            direct["domain"],
        )
        assertEquals(
            JsonArray(listOf(JsonPrimitive("10.0.0.0/8"))),
            direct["ip"],
        )
    }

    @Test
    fun `catch all rule covers tcp and udp and points to target`() {
        val rule = RoutingRulesBuilder.catchAllRule(catchAll).jsonObject
        assertEquals(JsonPrimitive("field"), rule["type"])
        assertEquals(JsonPrimitive("tcp,udp"), rule["network"])
        assertEquals(JsonPrimitive("server-1"), rule["outboundTag"])
        assertNull(rule["balancerTag"])
    }

    @Test
    fun `catch all rule may point to balancer`() {
        val rule = RoutingRulesBuilder.catchAllRule(
            RoutingRulesBuilder.CatchAll(balancerTag = "auto"),
        ).jsonObject
        assertEquals(JsonPrimitive("auto"), rule["balancerTag"])
        assertNull(rule["outboundTag"])
    }

    @Test
    fun `dns intercept rule matches port 53 from tun`() {
        val rule = RoutingRulesBuilder.dnsInterceptRule().jsonObject
        assertEquals(JsonPrimitive("field"), rule["type"])
        assertEquals(JsonPrimitive("tun-in"), rule["inboundTag"]!!.jsonArray[0])
        // Порт числом: ядро принимает и "53", и 53.
        assertEquals(JsonPrimitive(53), rule["port"])
        assertEquals(JsonPrimitive("tcp,udp"), rule["network"])
        assertEquals(JsonPrimitive("dns"), rule["outboundTag"])
    }

    @Test
    fun `dns is built with hosts and ip literal doh servers`() {
        val dns = RoutingRulesBuilder.dns(profile.copy(fakeDns = true))!!
        assertEquals("8.8.8.8", dns["hosts"]!!.jsonObject["dns.google"]!!.jsonPrimitive.content)
        val servers = dns["servers"]!!.jsonArray.map { it.jsonPrimitive.content }
        // DoH — по IP-литералу, иначе разрешение имени DNS само требует DNS.
        assertEquals("fakedns", servers[0])
        assertEquals("https://1.1.1.1/dns-query", servers[1])
        assertEquals("https://8.8.8.8/dns-query", servers[2])
        // fakedns в dns-секции ядро не читает — только верхним уровнем.
        assertFalse(dns.containsKey("fakedns"))
    }

    @Test
    fun `dns query strategy comes from advanced settings`() {
        val bare = RoutingProfile(id = "x", name = "x")
        val dns = RoutingRulesBuilder.dns(
            bare,
            AdvancedConfig.DEFAULT.copy(queryStrategy = AdvancedConfig.QUERY_USE_IP),
        )!!
        assertEquals(JsonPrimitive("UseIP"), dns["queryStrategy"])
    }

    @Test
    fun `dns keeps domain when no ip literal`() {
        val bare = RoutingProfile(id = "x", name = "x", remoteDnsIp = "")
        val dns = RoutingRulesBuilder.dns(bare)!!
        assertEquals(
            "https://cloudflare-dns.com/dns-query",
            dns["servers"]!!.jsonArray[0].jsonPrimitive.content,
        )
    }

    @Test
    fun `dns is null when nothing configured`() {
        val bare = RoutingProfile(id = "x", name = "x", remoteDnsDomain = "", domesticDnsDomain = "")
        assertNull(RoutingRulesBuilder.dns(bare))
    }

    @Test
    fun `fakedns alone is enough to build dns section`() {
        val bare = RoutingProfile(
            id = "x",
            name = "x",
            remoteDnsDomain = "",
            domesticDnsDomain = "",
            fakeDns = true,
        )
        val dns = RoutingRulesBuilder.dns(bare)!!
        assertEquals("fakedns", dns["servers"]!!.jsonArray[0].jsonPrimitive.content)
    }

    @Test
    fun `built json is parseable`() {
        val text = RoutingRulesBuilder.rules(profile, catchAll).toString()
        val parsed = Json.parseToJsonElement(text).jsonArray
        assertFalse(parsed.isEmpty())
    }
}
