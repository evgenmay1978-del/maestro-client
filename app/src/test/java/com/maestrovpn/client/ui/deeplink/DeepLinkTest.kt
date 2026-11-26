package com.maestrovpn.client.ui.deeplink

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Specii 4: razbor deeplink-komand (chisty JVM, bez Android).
 */
class DeepLinkTest {

    @Test
    fun `add with url parses`() {
        val link = DeepLink.parse("maestro://add?url=https://x/sub/token")
        assertEquals(DeepLink.Add("https://x/sub/token"), link)
    }

    @Test
    fun `add without url is unsupported`() {
        assertTrue(DeepLink.parse("maestro://add") is DeepLink.Unsupported)
    }

    @Test
    fun `add with non-http url is unsupported`() {
        assertTrue(DeepLink.parse("maestro://add?url=ftp://x") is DeepLink.Unsupported)
    }

    @Test
    fun `connect disconnect toggle parse`() {
        assertEquals(DeepLink.Connect, DeepLink.parse("maestro://connect"))
        assertEquals(DeepLink.Disconnect, DeepLink.parse("maestro://disconnect"))
        assertEquals(DeepLink.Toggle, DeepLink.parse("maestro://toggle"))
    }

    @Test
    fun `unknown host is unsupported`() {
        assertTrue(DeepLink.parse("maestro://routing") is DeepLink.Unsupported)
    }

    @Test
    fun `foreign scheme is null`() {
        assertNull(DeepLink.parse("https://x/y"))
        assertNull(DeepLink.parse("otherapp://routing/add/abc"))
    }

    @Test
    fun `case insensitive host`() {
        assertEquals(DeepLink.Connect, DeepLink.parse("maestro://CONNECT"))
    }

    @Test
    fun `empty and null are safe`() {
        assertNull(DeepLink.parse(null))
        assertNull(DeepLink.parse(""))
        assertTrue(DeepLink.parse("maestro://") is DeepLink.Unsupported)
    }

    @Test
    fun `url encoded value is decoded`() {
        val link = DeepLink.parse("maestro://add?url=https%3A%2F%2Fx%2Fsub%2Ft")
        assertEquals(DeepLink.Add("https://x/sub/t"), link)
    }
}
