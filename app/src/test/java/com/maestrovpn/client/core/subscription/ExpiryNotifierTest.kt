package com.maestrovpn.client.core.subscription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * issue #5: пороги напоминания об истечении (Happ — за 3 дня, ежедневно).
 */
class ExpiryNotifierTest {

    private val day = 86_400_000L
    private val now = 1_000_000_000_000L

    @Test
    fun `far future is silent`() {
        val level = ExpiryNotifier.level(now + 10 * day, now)
        assertEquals(ExpiryNotifier.Level.NONE, level)
    }

    @Test
    fun `inside warning window warns with rounded-up days`() {
        // 0, 1, 2 полных дня до срока и остаток менее суток — всё окно.
        for (d in listOf(0L, 1L, 2L)) {
            for (extra in listOf(0L, day - 1)) {
                val level = ExpiryNotifier.level(now + d * day + extra, now)
                assertEquals("d=$d extra=$extra", ExpiryNotifier.Level.WARNING, level)
            }
        }
    }

    @Test
    fun `exactly on the third day warns`() {
        val level = ExpiryNotifier.level(now + 3 * day, now)
        assertEquals(ExpiryNotifier.Level.WARNING, level)
    }

    @Test
    fun `just past threshold is silent`() {
        val level = ExpiryNotifier.level(now + 3 * day + 1000, now)
        assertEquals(ExpiryNotifier.Level.NONE, level)
    }

    @Test
    fun `already expired`() {
        assertEquals(
            ExpiryNotifier.Level.EXPIRED,
            ExpiryNotifier.level(now - day, now),
        )
    }

    @Test
    fun `missing expire is silent`() {
        assertEquals(ExpiryNotifier.Level.NONE, ExpiryNotifier.level(null, now))
        assertEquals(ExpiryNotifier.Level.NONE, ExpiryNotifier.level(0L, now))
    }

    @Test
    fun `day key changes per day and per level`() {
        val k1 = ExpiryNotifier.dayKey("sub", ExpiryNotifier.Level.WARNING, now)
        val k2 = ExpiryNotifier.dayKey("sub", ExpiryNotifier.Level.WARNING, now + day)
        val k3 = ExpiryNotifier.dayKey("sub", ExpiryNotifier.Level.EXPIRED, now)
        assertNotEquals(k1, k2)
        assertNotEquals(k1, k3)
        assertEquals(k1, ExpiryNotifier.dayKey("sub", ExpiryNotifier.Level.WARNING, now + 60_000))
    }

    @Test
    fun `messages carry name and days`() {
        assertTrue(ExpiryNotifier.message("X", ExpiryNotifier.Level.WARNING, 2).contains("X"))
        assertTrue(ExpiryNotifier.message("X", ExpiryNotifier.Level.WARNING, 2).contains("2"))
        assertTrue(ExpiryNotifier.message("X", ExpiryNotifier.Level.EXPIRED, 0).contains("истекла"))
    }
}
