package com.maestrovpn.client.core.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Specii 7: chuzhoy konfig JSON otvergayetsya s prichinoy, ne opredelyaetsya kak podderzhivaemyy.
 * JSON-объект без распознаваемых Xray outbounds — отказ с причиной (UNKNOWN),
 * а не определение чужого формата.
 */
class SubscriptionParserForeignJsonTest {

    @Test
    fun `json object with outbounds but not xray is unknown with reason`() {
        // Объект с outbounds, у которых НЕТ protocol/streamSettings — чужой формат.
        val foreign = """
            {"outbounds":[{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":1080}]}
        """.trimIndent()
        val parsed = SubscriptionParser.parse(foreign)

        assertEquals(SubscriptionFormat.UNKNOWN, parsed.format)
        assertTrue(parsed.servers.isEmpty())
        assertTrue(
            "reason must mention Xray outbounds, got: ${parsed.issues}",
            parsed.issues.any { it.reason.contains("Xray outbounds") },
        )
    }

    @Test
    fun `json object without outbounds key is unknown with reason`() {
        val foreign = """{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":1080}"""
        val parsed = SubscriptionParser.parse(foreign)

        assertEquals(SubscriptionFormat.UNKNOWN, parsed.format)
        assertTrue(parsed.servers.isEmpty())
        assertTrue(
            "reason must be non-empty, got: ${parsed.issues}",
            parsed.issues.all { it.reason.isNotBlank() },
        )
    }

    @Test
    fun `xray json object is still accepted`() {
        val xray = """
            {"outbounds":[{"tag":"proxy","protocol":"socks",
              "settings":{"servers":[{"address":"127.0.0.1","port":1080}]}}]}
        """.trimIndent()
        val parsed = SubscriptionParser.parse(xray)

        assertEquals(SubscriptionFormat.XRAY, parsed.format)
        assertEquals(1, parsed.servers.size)
    }
}
