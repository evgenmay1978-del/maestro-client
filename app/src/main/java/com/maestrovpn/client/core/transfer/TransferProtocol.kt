package com.maestrovpn.client.core.transfer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Результат отправки подписки на приёмник. */
sealed class TransferReceipt {
    data class Ok(val servers: Int?) : TransferReceipt()

    data class Refused(val reason: String) : TransferReceipt()

    data class Failed(val reason: String) : TransferReceipt()
}

/**
 * Проводной формат обмена «телефон → приёмник»: POST на /subscription с JSON
 * {"url": "...", "name": "..."} и токеном из [PairingLink] в заголовке.
 * Ответ: {"ok":true,"servers":N} либо {"ok":false,"error":"причина"}.
 *
 * Формат держим в одном месте, потому что его проверяют тесты на реальных сокетах,
 * а не только экран.
 */
object TransferProtocol {
    const val PATH = "/subscription"
    const val TOKEN_HEADER = "X-Maestro-Token"
    const val CONTENT_TYPE = "application/json; charset=utf-8"
    const val MAX_BODY_BYTES = 64 * 1024
    const val MAX_URL_LENGTH = 4096
    const val MAX_NAME_LENGTH = 128

    private val json = Json { ignoreUnknownKeys = true }

    data class SubscriptionPayload(val url: String, val name: String)

    fun encodeRequest(payload: SubscriptionPayload): String = buildJsonObject {
        put("url", payload.url)
        put("name", payload.name)
    }.toString()

    fun decodeRequest(body: String): Result<SubscriptionPayload> = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        val url = obj["url"]?.jsonPrimitive?.content?.trim().orEmpty()
        if (url.isEmpty()) throw IllegalArgumentException("Нет ссылки на подписку")
        if (url.length > MAX_URL_LENGTH) throw IllegalArgumentException("Ссылка слишком длинная")
        if (!isSubscriptionUrl(url)) throw IllegalArgumentException("Ссылка должна быть http(s)")
        val name = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty().take(MAX_NAME_LENGTH)
        SubscriptionPayload(url, name)
    }

    fun encodeResponse(ok: Boolean, error: String? = null, servers: Int? = null): String = buildJsonObject {
        put("ok", ok)
        if (error != null) put("error", error)
        if (servers != null) put("servers", servers)
    }.toString()

    fun decodeResponse(body: String): TransferReceipt = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        val ok = obj["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        if (ok) {
            TransferReceipt.Ok(obj["servers"]?.jsonPrimitive?.content?.toIntOrNull())
        } else {
            TransferReceipt.Refused(obj["error"]?.jsonPrimitive?.content ?: "приёмник отказал")
        }
    }.getOrElse { TransferReceipt.Failed("ответ приёмника не разобран") }

    fun isSubscriptionUrl(value: String): Boolean {
        val lower = value.lowercase()
        return (lower.startsWith("http://") || lower.startsWith("https://")) &&
            value.length > "https://".length && !value.any { it.isWhitespace() }
    }
}
