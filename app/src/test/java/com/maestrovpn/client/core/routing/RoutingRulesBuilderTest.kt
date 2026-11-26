package com.maestrovpn.client.core.routing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

    @Test
    fun `rules are ordered block proxy direct`() {
        val rules = RoutingRulesBuilder.rules(profile)
        val tags = rules.map { it.jsonObject["outboundTag"] }
        // globalProxy=true по умолчанию → proxy-правила пропущены.
        assertEquals(2, rules.size)
        assertEquals(JsonPrimitive("block"), tags[0])
        assertEquals(JsonPrimitive("direct"), tags[1])
    }

    @Test
    fun `proxy rules appear when globalProxy off`() {
        val rules = RoutingRulesBuilder.rules(profile.copy(globalProxy = false))
        val tags = rules.map { it.jsonObject["outboundTag"] }
        assertEquals(3, rules.size)
        assertEquals(JsonPrimitive("block"), tags[0])
        assertEquals(JsonPrimitive("proxy"), tags[1])
        assertEquals(JsonPrimitive("direct"), tags[2])
    }

    @Test
    fun `empty profile has no rules`() {
        val rules = RoutingRulesBuilder.rules(RoutingProfile.empty("x"))
        assertTrue(rules.isEmpty())
    }

    @Test
    fun `rule carries domains and ips`() {
        val rules = RoutingRulesBuilder.rules(profile)
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
    fun `dns is built with hosts servers and fakedns`() {
        val dns = RoutingRulesBuilder.dns(profile.copy(fakeDns = true))!!
        assertEquals("8.8.8.8", dns["hosts"]!!.jsonObject["dns.google"]!!.jsonPrimitive.content)
        val servers = dns["servers"]!!.jsonArray
        assertTrue(servers.any { it.jsonPrimitive.content.contains("cloudflare-dns") })
        assertTrue(servers.any { it.jsonPrimitive.content.contains("dns.google") })
        assertTrue(dns.containsKey("fakedns"))
    }

    @Test
    fun `dns is null when nothing configured`() {
        val bare = RoutingProfile(id = "x", name = "x", remoteDnsDomain = "", domesticDnsDomain = "")
        assertNull(RoutingRulesBuilder.dns(bare))
    }

    @Test
    fun `built json is parseable`() {
        val text = RoutingRulesBuilder.rules(profile).toString()
        val parsed = Json.parseToJsonElement(text).jsonArray
        assertFalse(parsed.isEmpty())
    }
}
