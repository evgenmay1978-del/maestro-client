package com.maestrovpn.client.core.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Спецификация §7: формат sing-box (SING_BOX_CONFIG) не поддерживается.
 * JSON-объект без распознаваемых Xray outbounds — отказ с причиной (UNKNOWN),
 * а не определение чужого формата.
 */
class SubscriptionParserSingboxRemovedTest {

    @Test
    fun `json object without xray outbounds is unknown with reason`() {
        val foreign = """{"type":"socks","tag":"proxy","server":"127.0.0.1","server_port":1080}"""
        val parsed = SubscriptionParser.parse(foreign)

        assertEquals(SubscriptionFormat.UNKNOWN, parsed.format)
        assertTrue(parsed.servers.isEmpty())
        assertTrue(
            "reason must mention Xray outbounds, got: ${parsed.issues}",
            parsed.issues.any { it.reason.contains("Xray outbounds") },
        )
    }

    @Test
    fun `parsed subscription has no foreign config field`() {
        val parsed = SubscriptionParser.parse("{\"outbounds\":[]}")
        // Формат sing-box удалён из модели: поля singBoxConfig нет.
        assertNull(parsed.singBoxConfig)
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
