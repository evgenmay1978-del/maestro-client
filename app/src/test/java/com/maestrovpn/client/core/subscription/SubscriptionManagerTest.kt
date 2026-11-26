package com.maestrovpn.client.core.subscription

import android.os.Looper
import android.os.StrictMode
import androidx.test.core.app.ApplicationProvider
import com.maestrovpn.client.core.engine.VpnEngine
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.InetSocketAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SubscriptionManagerTest {
    private lateinit var server: HttpServer
    private var requestCount = 0
    private val engine = object : VpnEngine {
        override val version = "test"
        override val lastError: String? = null
        override fun validateConfig(configJson: String): Boolean {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Xray validation on main thread" }
            return true
        }
        override fun start(configJson: String) = false
        override fun stop() = Unit
    }

    @Before
    fun setUp() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeath().build(),
        )
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/subscription") { exchange ->
            requestCount++
            val body = "socks://127.0.0.1:1080#test".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/error") { exchange ->
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())
    }

    @Test
    fun importAndRefreshDoNotUseNetworkOnAndroidMainThread() = runBlocking {
        val manager = SubscriptionManager(ApplicationProvider.getApplicationContext(), engine)
        val url = "http://127.0.0.1:${server.address.port}/subscription"

        val imported = manager.importSubscription("test", url)
        assertTrue("Import failed: $imported", imported is ImportResult.Success)
        val info = manager.list().single()
        val refreshed = manager.refresh(info)

        assertTrue("Refresh failed: $refreshed", refreshed is ImportResult.Success)
        assertEquals(2, requestCount)
        assertEquals(1, manager.list().size)
    }

    @Test
    fun httpErrorIsReturnedInsteadOfEscapingMainThread() = runBlocking {
        val manager = SubscriptionManager(ApplicationProvider.getApplicationContext(), engine)
        val url = "http://127.0.0.1:${server.address.port}/error"

        val result = manager.importSubscription("test", url)

        assertTrue(result is ImportResult.Failure)
        assertTrue((result as ImportResult.Failure).reason.contains("HTTP 503"))
        assertTrue(manager.list().isEmpty())
    }
}
