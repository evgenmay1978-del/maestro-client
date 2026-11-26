package com.maestrovpn.client.core.transfer

import java.io.InputStream

/**
 * Минимальный разбор HTTP-запроса: строка запроса, заголовки, тело по Content-Length.
 * Полноценный HTTP-сервер здесь не нужен — приёмник подписки принимает один POST
 * от нашего же клиента в локальной сети.
 */
internal object HttpRequestReader {
    data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String,
    )

    fun read(input: InputStream, maxBody: Int = TransferProtocol.MAX_BODY_BYTES): Request? {
        val requestLine = readLine(input) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 3) return null

        val headers = LinkedHashMap<String, String>()
        var guard = 0
        while (true) {
            val line = readLine(input) ?: return null
            if (line.isEmpty()) break
            guard++
            if (guard > 64) return null
            val index = line.indexOf(':')
            if (index <= 0) continue
            headers[line.substring(0, index).trim().lowercase()] = line.substring(index + 1).trim()
        }

        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > maxBody) return null
        val body = if (length == 0) {
            ""
        } else {
            val buffer = ByteArray(length)
            var read = 0
            while (read < length) {
                val chunk = input.read(buffer, read, length - read)
                if (chunk < 0) break
                read += chunk
            }
            String(buffer, 0, read, Charsets.UTF_8)
        }
        return Request(parts[0].uppercase(), parts[1], headers, body)
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val byte = input.read()
            if (byte < 0) return if (builder.isEmpty()) null else builder.toString()
            if (byte == '\n'.code) return builder.toString().trimEnd('\r')
            builder.append(byte.toChar())
            if (builder.length > 8192) return null
        }
    }
}
