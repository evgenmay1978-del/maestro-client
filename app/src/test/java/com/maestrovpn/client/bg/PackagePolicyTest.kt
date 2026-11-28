package com.maestrovpn.client.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Матрица режимов PackagePolicy — до 28.11.2026 тестов на неё не было, хотя
 * комментарий в коде на них ссылался.
 *
 * Повод: агент владельца (DSHA) уходил в туннель вместе со всеми приложениями.
 * Когда туннель поднят, но не пропускает трафик, канал диагностики рвётся ровно
 * тогда, когда он нужнее всего. CONTROL_PACKAGES обязаны оставаться снаружи во
 * всех четырёх режимах.
 */
class PackagePolicyTest {

    private val app = "com.maestrovpn.client"
    private val agent = "dsh.android.client"
    private val browser = "org.mozilla.firefox"

    /** Всё, что обязано остаться вне туннеля: свой пакет и агент диагностики. */
    private val alwaysOutside = (PackagePolicy.CONTROL_PACKAGES + app).toSet()

    @Test
    fun noPerAppList_tunnelsEverythingExceptSelfAndControl() {
        val d = PackagePolicy.decide(app, perAppEnabled = false, includeMode = false, appList = emptySet())
        assertEquals(emptySet<String>(), d.include.toSet())
        assertEquals(alwaysOutside, d.exclude.toSet())
        assertTrue("агент обязан быть снаружи", agent in d.exclude)
    }

    @Test
    fun excludeMode_keepsUserListAndAddsSelfAndControl() {
        val d = PackagePolicy.decide(app, perAppEnabled = true, includeMode = false, appList = setOf(browser))
        assertEquals(emptySet<String>(), d.include.toSet())
        assertEquals(alwaysOutside + browser, d.exclude.toSet())
    }

    @Test
    fun excludeMode_userCannotTunnelTheAgent() {
        val d = PackagePolicy.decide(
            app,
            perAppEnabled = true,
            includeMode = false,
            appList = setOf(agent, browser),
        )
        assertTrue(agent in d.exclude)
        assertEquals(alwaysOutside + browser, d.exclude.toSet())
    }

    @Test
    fun includeMode_userListCannotContainSelfOrControl() {
        val d = PackagePolicy.decide(
            app,
            perAppEnabled = true,
            includeMode = true,
            appList = setOf(browser, agent, app),
        )
        assertEquals(listOf(browser), d.include)
        assertEquals(emptySet<String>(), d.exclude.toSet())
    }

    @Test
    fun includeMode_withNothingSelectedInvertsToExcludeSelfAndControl() {
        val d = PackagePolicy.decide(app, perAppEnabled = true, includeMode = true, appList = emptySet())
        assertEquals(emptySet<String>(), d.include.toSet())
        assertEquals(alwaysOutside, d.exclude.toSet())
    }

    @Test
    fun includeMode_withOnlyControlSelectedIsTreatedAsEmpty() {
        val d = PackagePolicy.decide(app, perAppEnabled = true, includeMode = true, appList = setOf(agent, app))
        assertEquals(emptySet<String>(), d.include.toSet())
        assertEquals(alwaysOutside, d.exclude.toSet())
    }

    @Test
    fun decisionNeverCarriesBothLists() {
        val cases = listOf(
            Triple(false, false, emptySet<String>()),
            Triple(true, false, setOf(browser)),
            Triple(true, true, setOf(browser)),
            Triple(true, true, emptySet<String>()),
        )
        cases.forEach { (perApp, includeMode, list) ->
            val d = PackagePolicy.decide(app, perApp, includeMode, list)
            assertFalse(
                "include и exclude одновременно для perApp=$perApp includeMode=$includeMode",
                d.include.isNotEmpty() && d.exclude.isNotEmpty(),
            )
        }
    }

    @Test
    fun controlPackageListCoversTheAgentIdsThatExistOnDevices() {
        assertTrue(agent in PackagePolicy.CONTROL_PACKAGES)
        assertTrue("dsh.android.client.fork" in PackagePolicy.CONTROL_PACKAGES)
        assertTrue("com.dsh.client.fork" in PackagePolicy.CONTROL_PACKAGES)
    }

    @Test
    fun excludeListHasNoDuplicatesEvenWhenUserNamesSelfAndControl() {
        val d = PackagePolicy.decide(
            app,
            perAppEnabled = true,
            includeMode = false,
            appList = setOf(browser, agent, app),
        )
        assertEquals(d.exclude.size, d.exclude.toSet().size)
    }
}
