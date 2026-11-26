package com.maestrovpn.client.core.transfer

import com.maestrovpn.client.core.transfer.TransferProtocol.SubscriptionPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferProtocolTest {

    @Test
    fun `request survives encode and decode`() {
        val payload = SubscriptionPayload("https://sub.example.com/abc?token=1", "Домашняя")
        val decoded = TransferProtocol.decodeRequest(TransferProtocol.encodeRequest(payload)).getOrThrow()
        assertEquals(payload, decoded)
    }

    @Test
    fun `decode rejects non subscription urls`() {
        val raw = """{"url":"vless://uuid@example.com:443","name":"x"}"""
        assertTrue(TransferProtocol.decodeRequest(raw).isFailure)
        assertTrue(TransferProtocol.decodeRequest("""{"name":"x"}""").isFailure)
        assertTrue(TransferProtocol.decodeRequest("не json").isFailure)
        val long = """{"url":"https://sub.example.com/""" + "a".repeat(TransferProtocol.MAX_URL_LENGTH) + """"}"""
        assertTrue(TransferProtocol.decodeRequest(long).isFailure)
    }

    @Test
    fun `name is trimmed and capped`() {
        val raw = """{"url":"https://s.example.com/x","name":"  """ + "n".repeat(200) + """"}"""
        val payload = TransferProtocol.decodeRequest(raw).getOrThrow()
        assertTrue(payload.name.length <= TransferProtocol.MAX_NAME_LENGTH)
        assertFalse(payload.name.startsWith(" "))
    }

    @Test
    fun `response roundtrip ok and refused`() {
        assertEquals(
            TransferReceipt.Ok(4),
            TransferProtocol.decodeResponse(TransferProtocol.encodeResponse(true, servers = 4)),
        )
        assertEquals(
            TransferReceipt.Refused("token"),
            TransferProtocol.decodeResponse(TransferProtocol.encodeResponse(false, error = "token")),
        )
        assertTrue(TransferProtocol.decodeResponse("мусор") is TransferReceipt.Failed)
    }

    @Test
    fun `subscription url rule`() {
        assertTrue(TransferProtocol.isSubscriptionUrl("http://1.2.3.4/sub"))
        assertTrue(TransferProtocol.isSubscriptionUrl("https://sub.example.com"))
        assertFalse(TransferProtocol.isSubscriptionUrl("https://"))
        assertFalse(TransferProtocol.isSubscriptionUrl("vless://a@b:1"))
        assertFalse(TransferProtocol.isSubscriptionUrl("https://exa mple.com"))
    }
}
