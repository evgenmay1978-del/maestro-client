package com.maestrovpn.client.core.transfer

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * IPv4-адреса этого устройства в локальной сети: их показываем рядом с QR-кодом,
 * если у приёмника несколько интерфейсов (Wi-Fi и Ethernet на ТВ-приставке).
 */
object LocalAddresses {
    fun ipv4(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
            .flatMap { nic -> nic.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
            .map { it.hostAddress.orEmpty() }
            .filter { it.isNotBlank() }
            .distinct()
            .toList()
    }.getOrDefault(emptyList())
}
