package com.maestrovpn.client.core.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Specii 7: zagolovki otveta podpiski chitayutsya.
 * Znacheniya vzyaty iz zhivyh obrazcov (wapmixx, akonit) bez izmeneniy.
 */
class SubscriptionHeadersTest {

    @Test
    fun `wapmixx headers are parsed`() {
        val headers = SubscriptionHeaders.parse(
            mapOf(
                "Subscription-Userinfo" to listOf("upload=0; download=0; total=0; expire=4394044800"),
                "profile-title" to listOf("base64:TWFlc3Ryb1ZQTg=="),
                "announce" to listOf("base64:8J+SsyDQntC/0LvQsNGC0LAg0LIg0LHQvtGC0LUgQE1hZXN0cm9TZWN1cmVWUE5fYm90"),
                "support-url" to listOf("https://t.me/wapmixx"),
                "profile-web-page-url" to listOf("https://t.me/maestrovpn"),
                "profile-update-interval" to listOf("1"),
            ),
        )

        assertEquals("MaestroVPN", headers.title)
        assertEquals(1, headers.updateIntervalHours)
        assertEquals("https://t.me/maestrovpn", headers.webPageUrl)
        assertEquals(0L, headers.upload)
        assertEquals(0L, headers.download)
        assertNull(headers.total)
        assertEquals(4394044800L, headers.expire)
        assertEquals("https://t.me/wapmixx", headers.supportUrl)
        // announce дешифрован из base64 и не пустой
        assertTrue(headers.announce?.contains("MaestroSecureVPN_bot") == true)
    }

    @Test
    fun `akonit style headers are parsed`() {
        val headers = SubscriptionHeaders.parse(
            mapOf(
                "Profile-Title" to listOf("Akonit Premium"),
                "Profile-Update-Interval" to listOf("1"),
                "Support-Url" to listOf("https://t.me/akonit_support_bot"),
                "Announce" to listOf("base64:4pqh77iPIOKAlCDRgdC60L7RgNC+0YHRgtC90L7QuSDQutCw0L3QsNC7"),
                "Subscription-Userinfo" to listOf("upload=0; download=0; total=0; expire=1805731840"),
            ),
        )

        assertEquals("Akonit Premium", headers.title)
        assertEquals("https://t.me/akonit_support_bot", headers.supportUrl)
        assertEquals(1805731840L, headers.expire)
        assertTrue(headers.daysUntilExpire(1764000000000L)!! > 0)
    }

    @Test
    fun `broken values do not break import`() {
        val headers = SubscriptionHeaders.parse(
            mapOf(
                "profile-title" to listOf("   "),
                "profile-update-interval" to listOf("not-a-number"),
                "subscription-userinfo" to listOf("garbage without pairs"),
            ),
        )

        assertNull(headers.title)
        assertNull(headers.updateIntervalHours)
        assertNull(headers.expire)
    }

    @Test
    fun `expired subscription reports zero days`() {
        val headers = SubscriptionHeaders.parse(
            mapOf(
                "subscription-userinfo" to listOf("upload=0; download=0; total=0; expire=1000"),
            ),
        )
        assertEquals(0, headers.daysUntilExpire(System.currentTimeMillis()))
    }
}
