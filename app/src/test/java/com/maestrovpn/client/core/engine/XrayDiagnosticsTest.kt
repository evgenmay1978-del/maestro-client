package com.maestrovpn.client.core.engine

import java.io.File
import java.nio.file.Files
import java.util.Date
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Отчёт о сбое ядра: полный текст ошибки на диск, секреты замаскированы.
 * Причина появления — обрезанный хвост ошибки 26.11.2026.
 */
class XrayDiagnosticsTest {

    @Test
    fun masksUuidPasswordAndRealityKeys() {
        val json = """{"users":[{"id":"11111111-2222-3333-4444-555555555555","flow":"xtls-rprx-vision"}],""" +
            """"realitySettings":{"publicKey":"PUB","shortId":"abcdef","serverName":"v.example"},""" +
            """"password":"secret-pass"}"""
        val masked = XrayDiagnostics.maskSecrets(json)
        assertFalse(masked.contains("11111111-2222-3333-4444-555555555555"))
        assertFalse(masked.contains("secret-pass"))
        assertFalse(masked.contains("PUB"))
        assertFalse(masked.contains("abcdef"))
        // Не секретные поля остаются читаемыми — иначе отчёт бесполезен.
        assertTrue(masked.contains("xtls-rprx-vision"))
        assertTrue(masked.contains("v.example"))
        assertTrue(masked.contains("***"))
    }

    @Test
    fun keepsJsonShapeAfterMasking() {
        val masked = XrayDiagnostics.maskSecrets("""{"uuid":"u-1","address":"1.2.3.4"}""")
        assertEquals("""{"uuid":"***","address":"1.2.3.4"}""", masked)
    }

    @Test
    fun knowsWhichKeysAreSecret() {
        assertTrue(XrayDiagnostics.isSecretKey("uuid"))
        assertTrue(XrayDiagnostics.isSecretKey("publicKey"))
        assertTrue(XrayDiagnostics.isSecretKey("private_key"))
        assertFalse(XrayDiagnostics.isSecretKey("address"))
        assertFalse(XrayDiagnostics.isSecretKey("flow"))
    }

    @Test
    fun reportCarriesStageAndFullError() {
        val text = XrayDiagnostics.report(
            stage = "старт туннеля",
            error = "fail to get system interface information: route ip+net: netlink",
            configJson = """{"users":[{"id":"uuid-raw"}]}""",
            logTail = "read Android Tun Fd 3",
            now = Date(0),
        )
        assertTrue(text.contains("старт туннеля"))
        assertTrue(text.contains("route ip+net: netlink"))
        assertTrue(text.contains("read Android Tun Fd 3"))
        assertFalse(text.contains("uuid-raw"))
    }

    @Test
    fun tailDropsBlankLinesAndKeepsTheEnd() {
        val dir = Files.createTempDirectory("diag").toFile()
        val log = File(dir, "xray.log")
        log.writeText("start\n\n\nfirst\nsecond\n\n")
        assertEquals("start\nfirst\nsecond", XrayDiagnostics.tail(log))
        dir.deleteRecursively()
    }

    @Test
    fun writeFailureCreatesFileWithMaskedContent() {
        val dir = Files.createTempDirectory("diag-write").toFile()
        val file = XrayDiagnostics.writeFailure(
            dir = dir,
            stage = "запуск службы",
            error = "bad file descriptor",
            configJson = """{"id":"uuid-raw"}""",
            logTail = "",
        )
        assertNotNull(file)
        val text = file!!.readText()
        assertTrue(text.contains("bad file descriptor"))
        assertFalse(text.contains("uuid-raw"))
        assertEquals(XrayDiagnostics.FILE_NAME, file.name)
        dir.deleteRecursively()
    }

    @Test
    fun writeFailureWithoutDirIsNotACrash() {
        assertNull(XrayDiagnostics.writeFailure(null, "e", "x", null, ""))
    }
}
