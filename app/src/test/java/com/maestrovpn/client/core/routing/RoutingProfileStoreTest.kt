package com.maestrovpn.client.core.routing

import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Раздел 7: хранилище профилей — CRUD, привязка к подписке, активный профиль.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RoutingProfileStoreTest {

    private lateinit var store: RoutingProfileStore

    @Before
    fun setUp() {
        store = RoutingProfileStore(ApplicationProvider.getApplicationContext())
    }

    @After
    fun tearDown() {
        store.list().forEach { store.delete(it) }
    }

    @Test
    fun `save assigns id and persists`() {
        val saved = store.save(RoutingProfile(id = "", name = "P1"))
        assertTrue(saved.id.isNotBlank())

        val reloaded = RoutingProfileStore(ApplicationProvider.getApplicationContext()).list()
        assertEquals(1, reloaded.size)
        assertEquals("P1", reloaded[0].name)
    }

    @Test
    fun `save updates existing profile`() {
        val saved = store.save(RoutingProfile(id = "", name = "P1"))
        store.save(saved.copy(name = "P1 renamed"))
        assertEquals(1, store.list().size)
        assertEquals("P1 renamed", store.list()[0].name)
    }

    @Test
    fun `delete removes profile`() {
        val saved = store.save(RoutingProfile(id = "", name = "P"))
        store.delete(saved)
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `profile bound to subscription`() {
        store.save(RoutingProfile(id = "", name = "Sub1", subscriptionId = "sub-1"))
        store.save(RoutingProfile(id = "", name = "Sub2", subscriptionId = "sub-2"))
        store.save(RoutingProfile(id = "", name = "Common"))

        assertEquals(1, store.forSubscription("sub-1").size)
        assertEquals(1, store.forSubscription("sub-2").size)
        assertEquals("Sub1", store.forSubscription("sub-1")[0].name)
    }

    @Test
    fun `active profile is scoped to its subscription`() {
        val bound = store.save(RoutingProfile(id = "", name = "B", subscriptionId = "sub-1"))
        store.setActive(bound, "sub-1")

        assertEquals(bound.id, store.activeFor("sub-1")?.id)
        assertNull(store.activeFor("sub-2"))
    }

    @Test
    fun `bound profile cannot be activated for another subscription`() {
        val bound = store.save(RoutingProfile(id = "", name = "B", subscriptionId = "sub-1"))
        store.setActive(bound, "sub-2")

        assertNull(store.active())
    }

    @Test
    fun `deleting active profile clears activation`() {
        val p = store.save(RoutingProfile(id = "", name = "P"))
        store.setActive(p, null)
        store.delete(p)

        assertNull(store.active())
    }

    @Test
    fun `empty store is silent`() {
        assertTrue(store.list().isEmpty())
        assertNull(store.active())
        assertNull(store.activeFor(null))
        assertFalse(store.list().isNotEmpty())
    }
}
