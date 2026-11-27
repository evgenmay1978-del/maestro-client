package com.maestrovpn.client.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Порядок уборки туннеля: сигналы -> проверка живости -> освобождение
 * ресурсов. Дефект 26.11.2026 — ресурсы не освобождались, потому что уборка
 * шла в корутине, которую отменял onDestroy(); здесь проверяется, что
 * синхронная уборка доводится до конца на всех путях, включая ошибки.
 *
 * Часы и сон подменены: реального времени тест не ждёт (иначе висящий
 * цикл ожидания подвесил бы весь прогон).
 */
class TunnelTeardownTest {

    private class FakeProcess(private val termKills: Boolean) {
        val signals = mutableListOf<Int>()
        var alive = true

        fun signal(pid: Long, sig: Int) {
            signals += sig
            if (sig == TunnelTeardown.SIGKILL) alive = false
            if (sig == TunnelTeardown.SIGTERM && termKills) alive = false
        }

        fun isAlive(pid: Long): Boolean = alive
    }

    /** Виртуальное время: now() идёт только вместе с sleep(). */
    private class FakeClock {
        var time = 0L
        val now: () -> Long = { time }
        val sleep: (Long) -> Unit = { ms -> time += ms }
    }

    @Test
    fun `sigterm only when process exits by itself`() {
        val process = FakeProcess(termKills = true)
        val clock = FakeClock()
        val outcome = TunnelTeardown(
            signal = { pid, sig -> process.signal(pid, sig) },
            isAlive = { pid -> process.isAlive(pid) },
            releaseResources = {},
            now = clock.now,
            sleep = clock.sleep,
        ).run(4242L)

        assertEquals(listOf(TunnelTeardown.SIGTERM), process.signals)
        assertTrue(outcome.signalledTerm)
        assertFalse(outcome.escalatedToKill)
        assertTrue(outcome.terminated)
        assertTrue(outcome.resourcesReleased)
        assertFalse(outcome.alreadyDone)
    }

    @Test
    fun `escalates to sigkill when process ignores sigterm`() {
        val process = FakeProcess(termKills = false)
        val clock = FakeClock()
        val outcome = TunnelTeardown(
            signal = { pid, sig -> process.signal(pid, sig) },
            isAlive = { pid -> process.isAlive(pid) },
            releaseResources = {},
            now = clock.now,
            sleep = clock.sleep,
        ).run(4242L)

        assertEquals(listOf(TunnelTeardown.SIGTERM, TunnelTeardown.SIGKILL), process.signals)
        assertTrue(outcome.escalatedToKill)
        assertTrue(outcome.terminated)
        assertTrue(outcome.resourcesReleased)
    }

    @Test
    fun `releases resources when signal throws`() {
        val process = FakeProcess(termKills = true)
        val clock = FakeClock()
        var released = 0
        val outcome = TunnelTeardown(
            signal = { _, _ -> throw IllegalStateException("нет прав на сигнал") },
            isAlive = { pid -> process.isAlive(pid) },
            releaseResources = { released++ },
            now = clock.now,
            sleep = clock.sleep,
        ).run(4242L)

        assertEquals(1, released)
        assertFalse(outcome.signalledTerm)
        assertTrue(outcome.resourcesReleased)
    }

    @Test
    fun `releases resources when process never dies`() {
        val clock = FakeClock()
        var released = 0
        var signals = 0
        val outcome = TunnelTeardown(
            signal = { _, _ -> signals++ },
            isAlive = { true },
            releaseResources = { released++ },
            now = clock.now,
            sleep = clock.sleep,
        ).run(777L)

        assertEquals(1, released)
        assertFalse(outcome.terminated)
        assertTrue(outcome.resourcesReleased)
        assertEquals(2, signals)
    }

    @Test
    fun `second run is idempotent`() {
        val process = FakeProcess(termKills = true)
        val clock = FakeClock()
        var released = 0
        val teardown = TunnelTeardown(
            signal = { pid, sig -> process.signal(pid, sig) },
            isAlive = { pid -> process.isAlive(pid) },
            releaseResources = { released++ },
            now = clock.now,
            sleep = clock.sleep,
        )

        val first = teardown.run(4242L)
        val second = teardown.run(4242L)

        assertTrue(first.resourcesReleased)
        assertTrue(second.alreadyDone)
        assertFalse(second.signalledTerm)
        assertFalse(second.resourcesReleased)
        assertEquals(1, released)
        assertEquals(1, process.signals.size)
    }

    @Test
    fun `no pid only releases resources`() {
        val clock = FakeClock()
        var released = 0
        val outcome = TunnelTeardown(
            signal = { _, _ -> throw AssertionError("сигнал процессу 0 отправлять нельзя") },
            isAlive = { false },
            releaseResources = { released++ },
            now = clock.now,
            sleep = clock.sleep,
        ).run(0L)

        assertTrue(outcome.terminated)
        assertEquals(1, released)
        assertTrue(outcome.resourcesReleased)
    }

    @Test
    fun `interrupted wait still releases resources`() {
        val process = FakeProcess(termKills = false)
        var released = 0
        val outcome = TunnelTeardown(
            signal = { _, _ -> },
            isAlive = { pid -> process.isAlive(pid) },
            releaseResources = { released++ },
            now = { 0L },
            sleep = { throw InterruptedException("прервали") },
        ).run(4242L)

        assertTrue(Thread.interrupted()) // флаг восстановлен самим teardown
        assertEquals(1, released)
        assertTrue(outcome.resourcesReleased)
        assertFalse(outcome.terminated)
    }

    @Test
    fun `broken alive check counts as dead`() {
        val clock = FakeClock()
        var released = 0
        val outcome = TunnelTeardown(
            signal = { _, _ -> },
            isAlive = { throw IllegalStateException("нет процесса") },
            releaseResources = { released++ },
            now = clock.now,
            sleep = clock.sleep,
        ).run(4242L)

        assertTrue(outcome.terminated)
        assertEquals(1, released)
    }

    @Test
    fun `release failure is reported not thrown`() {
        val process = FakeProcess(termKills = true)
        val clock = FakeClock()
        val outcome = TunnelTeardown(
            signal = { pid, sig -> process.signal(pid, sig) },
            isAlive = { pid -> process.isAlive(pid) },
            releaseResources = { throw IllegalStateException("wake lock уже освобождён") },
            now = clock.now,
            sleep = clock.sleep,
        ).run(4242L)

        assertFalse(outcome.resourcesReleased)
        assertTrue(outcome.terminated)
        assertTrue(outcome.describe().contains("resourcesReleased=false"))
    }

    @Test
    fun `sigterm waits before kill`() {
        val clock = FakeClock()
        val process = FakeProcess(termKills = false)
        var timeAtKill = -1L
        TunnelTeardown(
            signal = { _, sig ->
                if (sig == TunnelTeardown.SIGKILL) timeAtKill = clock.time
                process.signal(1L, sig)
            },
            isAlive = { pid -> process.isAlive(pid) },
            releaseResources = {},
            now = clock.now,
            sleep = clock.sleep,
        ).run(4242L)

        // SIGTERM ждёт полный graceMs, прежде чем эскалироваться.
        assertTrue(timeAtKill >= TunnelTeardown.DEFAULT_GRACE_MS)
    }
}
