package com.maestrovpn.client.core.transfer

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * Приёмник подписки: слушает свободный порт в локальной сети, принимает один POST
 * с подпиской и отдаёт результат импорта. Токен из QR-кода обязателен — без него
 * запрос отклоняется, чтобы «отправить себе» нельзя было с чужого устройства.
 *
 * Колбэк [onSubscription] выполняется в сетевом потоке приёмника и возвращает
 * число добавленных серверов либо ошибку — то же самое уходит отправителю.
 */
class TransferServer(
    private val onSubscription: (TransferProtocol.SubscriptionPayload) -> Result<Int>,
    private val token: String = newToken(),
) : Closeable {
    data class Started(val port: Int, val token: String)

    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null
    private var worker: Thread? = null

    fun start(): Result<Started> = runCatching {
        check(!running) { "Приёмник уже запущен" }
        val socket = ServerSocket(0)
        serverSocket = socket
        running = true
        worker = Thread({ acceptLoop(socket) }, "maestro-transfer-receiver").apply {
            isDaemon = true
            start()
        }
        Started(socket.localPort, token)
    }

    /** Ссылка для QR-кода на конкретном адресе этого устройства. */
    fun linkFor(host: String, started: Started): PairingLink =
        PairingLink(host = host, port = started.port, token = started.token)

    override fun close() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        worker?.interrupt()
        worker = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            client.use { connection ->
                runCatching {
                    connection.soTimeout = SOCKET_TIMEOUT_MS
                    handle(connection)
                }
            }
        }
    }

    private fun handle(client: Socket) {
        val input = client.getInputStream()
        val request = HttpRequestReader.read(input)
            ?: return respond(client, 400, TransferProtocol.encodeResponse(false, "bad request"))

        if (request.method != "POST" || request.path.substringBefore('?') != TransferProtocol.PATH) {
            return respond(client, 404, TransferProtocol.encodeResponse(false, "not found"))
        }
        val sentToken = request.headers[TransferProtocol.TOKEN_HEADER.lowercase()].orEmpty()
        if (!constantTimeEquals(sentToken, token)) {
            return respond(client, 403, TransferProtocol.encodeResponse(false, "token"))
        }
        val payload = TransferProtocol.decodeRequest(request.body).getOrElse { error ->
            return respond(client, 400, TransferProtocol.encodeResponse(false, error.message ?: "payload"))
        }
        val result = onSubscription(payload)
        result.fold(
            onSuccess = { servers ->
                respond(client, 200, TransferProtocol.encodeResponse(true, servers = servers))
            },
            onFailure = { error ->
                respond(client, 200, TransferProtocol.encodeResponse(false, error.message ?: "import failed"))
            },
        )
    }

    private fun respond(client: Socket, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(statusText(code)).append("\r\n")
            append("Content-Type: ").append(TransferProtocol.CONTENT_TYPE).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        val output: OutputStream = client.getOutputStream()
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(bytes)
        output.flush()
    }

    private fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        else -> "Not Found"
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        if (left.length != right.length) return false
        var diff = 0
        for (index in left.indices) diff = diff or (left[index].code xor right[index].code)
        return diff == 0
    }

    companion object {
        private const val SOCKET_TIMEOUT_MS = 5000
        private val random = SecureRandom()

        fun newToken(length: Int = 32): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
            return buildString(length) {
                repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) }
            }
        }
    }
}
