package com.maestrovpn.client.core.transfer

import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Проверка приёма и отправки на настоящих сокетах: сервер поднимается на loopback,
 * клиент ходит по HTTP. Так ловится и разбор запроса, и токен, и таймауты.
 */
class TransferServerTest {

    private fun startServer(
        received: AtomicReference<TransferProtocol.SubscriptionPayload>,
        servers: Int = 3,
    ): Pair<TransferServer, TransferServer.Started> {
        val server = TransferServer({ payload ->
            received.set(payload)
            Result.success(servers)
        })
        val started = server.start().getOrThrow()
        return server to started
    }

    @Test
    fun `subscription is delivered to receiver`() {
        val received = AtomicReference<TransferProtocol.SubscriptionPayload>()
        val (server, started) = startServer(received)
        try {
            val link = PairingLink("127.0.0.1", started.port, started.token)
            val payload = TransferProtocol.SubscriptionPayload("https://sub.example.com/abc", "Домашняя")
            val receipt = TransferClient.send(link, payload)
            assertEquals(TransferReceipt.Ok(3), receipt)
            assertEquals(payload, received.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun `wrong token is refused and payload not delivered`() {
        val received = AtomicReference<TransferProtocol.SubscriptionPayload>()
        val (server, started) = startServer(received)
        try {
            val link = PairingLink("127.0.0.1", started.port, "wrong-token-value")
            val receipt = TransferClient.send(
                link,
                TransferProtocol.SubscriptionPayload("https://sub.example.com/abc", "x"),
            )
            assertTrue(receipt is TransferReceipt.Refused)
            assertEquals("token", (receipt as TransferReceipt.Refused).reason)
            assertEquals(null, received.get())
        } finally {
            server.close()
        }
    }

    @Test
    fun `receiver failure is reported back to sender`() {
        val server = TransferServer({ Result.failure(IllegalStateException("подписка не разобрана")) })
        val started = server.start().getOrThrow()
        try {
            val receipt = TransferClient.send(
                PairingLink("127.0.0.1", started.port, started.token),
                TransferProtocol.SubscriptionPayload("https://sub.example.com/abc", "x"),
            )
            assertEquals(TransferReceipt.Refused("подписка не разобрана"), receipt)
        } finally {
            server.close()
        }
    }

    @Test
    fun `closed port gives network error`() {
        val port = ServerSocket(0).use { it.localPort }
        val receipt = TransferClient.send(
            PairingLink("127.0.0.1", port, "abcdefgh12345678"),
            TransferProtocol.SubscriptionPayload("https://sub.example.com/abc", "x"),
        )
        assertTrue("ожидали сетевую ошибку, получили $receipt", receipt is TransferReceipt.Failed)
    }

    @Test
    fun `server generates distinct tokens`() {
        val first = TransferServer.newToken()
        val second = TransferServer.newToken()
        assertEquals(32, first.length)
        assertNotNull(first)
        assertTrue(first != second)
        assertTrue(PairingLink.isValidToken(first))
    }

    @Test
    fun `start is refused twice`() {
        val server = TransferServer({ Result.success(0) })
        server.start().getOrThrow()
        try {
            assertTrue(server.start().isFailure)
        } finally {
            server.close()
        }
    }
}
