package com.maestrovpn.client.utils

import io.nekohasekai.libbox.Libbox
import com.maestrovpn.client.BuildConfig
import com.maestrovpn.client.ktx.unwrap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.util.Locale

/**
 * Срез 2: упрощённый HTTP-клиент подписок — только прямой fetch.
 *
 * У Maestro getString() шёл через цепочку MaestroSub.cdnEndpoints + TunnelFetch +
 * CdnPinnedClient (пины CDN-edge, туннельный прокси). В standalone-клиенте нет
 * ни фиксированного хоста панели, ни CDN, ни tunnel-fetch: один прямой запрос
 * через libbox HTTPClient. API сохранён: SubscriptionFetch (Ok/HttpError/Network),
 * SubscriptionHttpException, httpFetchSubscription, httpGetStringTimed.
 */

/**
 * /sub answered with a non-200 status. The status IS the diagnosis: 402 expired,
 * 404/410 the token no longer exists, 5xx the panel is sick. Collapsing it into
 * "null = timeout" hid a deleted subscription behind a fake network error (21.10.2026).
 */
class SubscriptionHttpException(val status: Int) : IOException("subscription HTTP $status")

/**
 * Keep the /sub HTTP status. 402 (expired) and 404/410 (token gone) are the diagnosis,
 * not a transient network failure.
 */
internal fun requireSubscriptionHttpStatus(status: Int) {
    if (status != 200) throw SubscriptionHttpException(status)
}

/** Outcome of a control-plane subscription fetch with the HTTP status preserved. */
sealed interface SubscriptionFetch {
    data class Ok(val body: String) : SubscriptionFetch
    data class HttpError(val status: Int) : SubscriptionFetch
    data object Network : SubscriptionFetch
}

class HTTPClient : Closeable {
    companion object {
        val userAgent by lazy {
            var userAgent = "MaestroClient/"
            userAgent += BuildConfig.VERSION_NAME
            userAgent += " ("
            userAgent += BuildConfig.VERSION_CODE
            userAgent += "; sing-box "
            userAgent += Libbox.version()
            userAgent += "; language "
            userAgent += Locale.getDefault().toLanguageTag().replace("-", "_")
            userAgent += ")"
            userAgent
        }
    }

    private val client = Libbox.newHTTPClient()

    init {
        client.modernTLS()
    }

    fun getString(url: String, timeoutMs: Long = 15_000): String {
        val request = client.newRequest()
        request.setUserAgent(userAgent)
        request.setURL(url)
        val response = request.execute()
        return response.content.unwrap
    }

    override fun close() {
        client.close()
    }
}

/**
 * Fetch a subscription (or any control-plane URL) and keep the HTTP status.
 *
 * Same hard caller-side timeout as before: libbox's HTTPClient sets no client timeout, so a
 * panel that completes TLS and then stalls would hang the caller forever. The native call is
 * blocking and ignores cancellation — run it detached and bound only the WAIT; the client is
 * closed on timeout, which aborts the in-flight request.
 */
@OptIn(DelicateCoroutinesApi::class)
suspend fun httpFetchSubscription(url: String, timeoutMs: Long = 15_000): SubscriptionFetch {
    val result = CompletableDeferred<SubscriptionFetch>()
    val client = HTTPClient()
    GlobalScope.launch(Dispatchers.IO) {
        val value = try {
            SubscriptionFetch.Ok(client.getString(url, timeoutMs))
        } catch (e: SubscriptionHttpException) {
            SubscriptionFetch.HttpError(e.status)
        } catch (t: Throwable) {
            SubscriptionFetch.Network
        }
        result.complete(value)
    }
    return try {
        withTimeoutOrNull(timeoutMs) { result.await() } ?: SubscriptionFetch.Network
    } finally {
        runCatching { client.close() }
    }
}

/**
 * Legacy nullable wrapper for callers that only need the body. Prefer
 * [httpFetchSubscription] where the status matters (subscription health, activation).
 */
suspend fun httpGetStringTimed(url: String, timeoutMs: Long = 15_000): String? =
    (httpFetchSubscription(url, timeoutMs) as? SubscriptionFetch.Ok)?.body
