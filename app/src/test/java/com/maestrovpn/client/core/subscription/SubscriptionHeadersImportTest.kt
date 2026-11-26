package com.maestrovpn.client.core.subscription

import android.os.StrictMode
import androidx.test.core.app.ApplicationProvider
import com.maestrovpn.client.core.engine.VpnEngine
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Specii 7 (skvoznoy): podpiska s zagolovkami importiruetsya,
 * ime beretsya iz profile-title, metadannye sohranyayutsya.
 * Znacheniya — iz zhivogo obrazca wapmixx.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SubscriptionHeadersImportTest {
    private lateinit var server: HttpServer
    private var requestCount = 0

    private val engine = object : VpnEngine {
        override val version = "test"
        override val lastError: String? = null
        override fun validateConfig(configJson: String): Boolean {
            check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper()) {
                "Xray validation on main thread"
            }
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
        server.createContext("/sub") { exchange ->
            requestCount++
            // Zagolovki — kak u zhivogo istochnika (imenа bez izmeneniy).
            exchange.responseHeaders.add("profile-title", "base64:TWFlc3Ryb1ZQTg==")
            exchange.responseHeaders.add("profile-update-interval", "3")
            exchange.responseHeaders.add("profile-web-page-url", "https://t.me/maestrovpn")
            exchange.responseHeaders.add("support-url", "https://t.me/wapmixx")
            exchange.responseHeaders.add(
                "subscription-userinfo",
                "upload=0; download=0; total=0; expire=4394044800",
            )
            val body = "socks://127.0.0.1:1080#test".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())
    }

    @Test
    fun `import reads title from profile-title header`() = runBlocking {
        val manager = SubscriptionManager(ApplicationProvider.getApplicationContext(), engine)
        val url = "http://127.0.0.1:${server.address.port}/sub"

        val result = manager.importSubscription("", url)

        assertTrue("import failed: $result", result is ImportResult.Success)
        val info = manager.list().single()
        // Imena vzyato iz zagolovka, a ne iz URL (bylo by http://127.0.0.1:PORT/sub).
        assertEquals("MaestroVPN", info.name)
        assertEquals(3, info.updateIntervalHours)
        assertEquals("https://t.me/maestrovpn", info.webPageUrl)
        assertEquals("https://t.me/wapmixx", info.supportUrl)
        assertEquals(4394044800L, info.expire)
    }

    @Test
    fun `manual name wins over profile-title`() = runBlocking {
        val manager = SubscriptionManager(ApplicationProvider.getApplicationContext(), engine)
        val url = "http://127.0.0.1:${server.address.port}/sub"

        manager.importSubscription("My name", url)

        assertEquals("My name", manager.list().single().name)
    }

    @Test
    fun `subscription without headers still imports`() = runBlocking {
        val bare = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        bare.createContext("/sub") { exchange ->
            val body = "socks://127.0.0.1:1081#bare".toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        bare.start()
        try {
            val manager = SubscriptionManager(ApplicationProvider.getApplicationContext(), engine)
            val url = "http://127.0.0.1:${bare.address.port}/sub"
            val result = manager.importSubscription("", url)

            assertTrue("bare import failed: $result", result is ImportResult.Success)
            val info = manager.list().single()
            assertNull(info.updateIntervalHours)
            assertNull(info.expire)
        } finally {
            bare.stop(0)
        }
    }
}
