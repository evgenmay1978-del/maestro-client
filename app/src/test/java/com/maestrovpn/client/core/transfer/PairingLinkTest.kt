package com.maestrovpn.client.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingLinkTest {

    @Test
    fun `uri survives parse roundtrip`() {
        val link = PairingLink("192.168.1.10", 53412, "abcdef0123456789")
        val parsed = PairingLink.parse(link.toUri())
        assertEquals(link, parsed)
    }

    @Test
    fun `display shows host and port`() {
        assertEquals("10.0.0.5:8080", PairingLink("10.0.0.5", 8080, "token12345").display())
    }

    @Test
    fun `looks like only for own scheme`() {
        assertTrue(PairingLink.looksLike("maestro-pair://receive?host=1.2.3.4&port=1&token=abcdefgh"))
        assertFalse(PairingLink.looksLike("https://example.com/sub"))
        assertFalse(PairingLink.looksLike(null))
    }

    @Test
    fun `parse rejects broken pieces`() {
        assertNull(PairingLink.parse("maestro-pair://receive?host=1.2.3.4&port=0&token=abcdefgh"))
        assertNull(PairingLink.parse("maestro-pair://receive?host=1.2.3.4&port=70000&token=abcdefgh"))
        assertNull(PairingLink.parse("maestro-pair://receive?host=1.2.3.4&port=1234&token=short"))
        assertNull(PairingLink.parse("maestro-pair://receive?host=&port=1234&token=abcdefgh"))
        assertNull(PairingLink.parse("maestro-pair://receive?host=1.2.3.4&port=1234"))
        assertNull(PairingLink.parse("https://example.com"))
        assertNull(PairingLink.parse(null))
    }

    @Test
    fun `parse accepts single digits and host with dash`() {
        val link = PairingLink.parse("maestro-pair://receive?host=tv-kitchen.local&port=9&token=abcdefgh")
        assertEquals("tv-kitchen.local", link?.host)
        assertEquals(9, link?.port)
    }

    @Test
    fun `validators reject corrupted host`() {
        assertFalse(PairingLink.isValidHost(""))
        assertFalse(PairingLink.isValidHost("has space"))
        assertFalse(PairingLink.isValidHost(".hidden"))
        assertFalse(PairingLink.isValidHost("two..dots"))
        assertTrue(PairingLink.isValidHost("192.168.0.10"))
    }
}
