package com.maestrovpn.client.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannedPayloadTest {

    @Test
    fun `pairing link wins over subscription`() {
        val link = PairingLink("192.168.1.10", 53412, "abcdef0123456789")
        val payload = ScannedPayload.classify(link.toUri())
        assertTrue(payload is ScannedPayload.Pairing)
        assertEquals(link, (payload as ScannedPayload.Pairing).link)
    }

    @Test
    fun `broken pairing link is reported as unknown`() {
        val payload = ScannedPayload.classify("maestro-pair://receive?host=1.2.3.4&port=0&token=abcdefgh")
        assertTrue(payload is ScannedPayload.Unknown)
    }

    @Test
    fun `http and https become subscriptions`() {
        assertEquals(
            ScannedPayload.Subscription("https://sub.example.com/abc"),
            ScannedPayload.classify("https://sub.example.com/abc"),
        )
        assertEquals(
            ScannedPayload.Subscription("http://10.0.0.1:8080/sub?token=x"),
            ScannedPayload.classify("  http://10.0.0.1:8080/sub?token=x  "),
        )
    }

    @Test
    fun `broken http link is unknown`() {
        assertTrue(ScannedPayload.classify("https://") is ScannedPayload.Unknown)
        assertTrue(ScannedPayload.classify("https://exa mple.com") is ScannedPayload.Unknown)
    }

    @Test
    fun `server schemes are recognised`() {
        val payload = ScannedPayload.classify("vless://uuid@example.com:443?type=tcp#node")
        assertTrue(payload is ScannedPayload.ServerLink)
        assertEquals("vless", (payload as ScannedPayload.ServerLink).scheme)
        assertTrue(ScannedPayload.classify("hy2://pass@example.com:443") is ScannedPayload.ServerLink)
    }

    @Test
    fun `command and garbage are reported`() {
        assertTrue(ScannedPayload.classify("maestro://connect") is ScannedPayload.Unknown)
        assertTrue(ScannedPayload.classify("просто текст") is ScannedPayload.Unknown)
        assertTrue(ScannedPayload.classify("") is ScannedPayload.Unknown)
        assertTrue(ScannedPayload.classify(null) is ScannedPayload.Unknown)
        assertTrue(ScannedPayload.classify("ftp://example.com/file") is ScannedPayload.Unknown)
    }
}
