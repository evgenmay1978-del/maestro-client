package com.maestrovpn.client.core.engine

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Регресс 26.11.2026: ядро читает номер tun fd из xray.tun.fd, а спавнер
 * отдавал XRAY_TUN_FD — старт падал на netlink. Тест держит имя переменной
 * и способ передачи fd в согласии между C и Kotlin.
 */
class TunFdContractTest {

    private val spawner: String by lazy { File("src/main/cpp/xray_spawn.c").readText() }

    @Test
    fun spawnerDeclaresTheSameEnvNameAsEngine() {
        val match = Regex("#define\\s+XRAY_TUN_FD_ENV\\s+\"([^\"]+)\"").find(spawner)
        assertTrue("в xray_spawn.c нет #define XRAY_TUN_FD_ENV", match != null)
        assertEquals(TunFdContract.ENV_NAME, match!!.groupValues[1])
    }

    @Test
    fun spawnerExportsOnlyTheEnvNameTheEngineReads() {
        val legacy = Regex("setenv\\(\\s*\"XRAY_TUN_FD\"")
        assertFalse("спавнер снова экспортирует XRAY_TUN_FD", legacy.containsMatchIn(spawner))
        assertTrue(spawner.contains("setenv(XRAY_TUN_FD_ENV"))
    }

    @Test
    fun spawnerClearsCloexecAndDuplicatesToValidFd() {
        // Копия fd без CLOEXEC обязана появиться до exec, иначе ядро не увидит
        // номер после exec, и номер в env разойдётся с реальным.
        assertTrue(spawner.contains("F_DUPFD"))
        assertTrue(spawner.contains("F_SETFD"))
    }

    @Test
    fun envValueIsDecimalNumber() {
        assertEquals("3", TunFdContract.envValue(3))
        assertEquals("1024", TunFdContract.envValue(1024))
    }

    @Test
    fun stdioDescriptorsAreNotHandedToTheEngine() {
        assertFalse(TunFdContract.isValidFd(0))
        assertFalse(TunFdContract.isValidFd(1))
        assertFalse(TunFdContract.isValidFd(2))
        assertTrue(TunFdContract.isValidFd(3))
    }

    @Test
    fun describeNamesBothFdAndEnv() {
        val text = TunFdContract.describe(42)
        assertTrue(text.contains("42"))
        assertTrue(text.contains(TunFdContract.ENV_NAME))
    }
}
