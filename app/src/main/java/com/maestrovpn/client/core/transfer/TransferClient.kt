package com.maestrovpn.client.core.transfer

import java.net.HttpURLConnection
import java.net.URL

/**
 * Отправка подписки на приёмник по адресу из QR-кода. Работает по локальной сети:
 * ни сервера, ни интернета для этого шага не нужно.
 */
object TransferClient {
    private const val CONNECT_TIMEOUT_MS = 4000
    private const val READ_TIMEOUT_MS = 15000

    fun send(
        link: PairingLink,
        payload: TransferProtocol.SubscriptionPayload,
    ): TransferReceipt {
        val body = TransferProtocol.encodeRequest(payload)
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("http://${link.host}:${link.port}${TransferProtocol.PATH}").openConnection()
                as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", TransferProtocol.CONTENT_TYPE)
                setRequestProperty(TransferProtocol.TOKEN_HEADER, link.token)
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            if (text.isBlank()) {
                TransferReceipt.Failed("приёмник не ответил ($code)")
            } else {
                TransferProtocol.decodeResponse(text)
            }
        } catch (error: Exception) {
            TransferReceipt.Failed(describe(error))
        } finally {
            connection?.disconnect()
        }
    }

    private fun describe(error: Exception): String = when (error) {
        is java.net.SocketTimeoutException -> "приёмник не ответил вовремя"
        is java.net.ConnectException -> "приёмник недоступен: то же ли это Wi-Fi?"
        is java.net.UnknownHostException -> "адрес из QR-кода не разрешается"
        else -> error.message ?: error::class.java.simpleName
    }
}
