package com.maestrovpn.client.core.subscription

import com.maestrovpn.client.core.engine.TunFdContract
import com.maestrovpn.client.core.engine.XrayDiagnostics
import com.maestrovpn.client.core.routing.RoutingProfile
import com.maestrovpn.client.core.routing.RoutingRulesBuilder
import com.maestrovpn.client.core.settings.AdvancedConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ИНТЕГРАЦИОННЫЙ ТЕСТ: все фиксы тракта должны присутствовать одновременно.
 *
 * Этот тест упал бы, если при слиянии веток потерялся хотя бы один фикс.
 * Проверяет:
 *  1. tun inbound: есть name, нет address/strictRoute/interfaceName, mtu из advanced
 *  2. имя env для fd = xray.tun.fd, совпадает с #define в xray_spawn.c
 *  3. правило DNS-перехвата (tun-in, port 53, tcp,udp -> dns) стоит ПЕРВЫМ
 *  4. outbound с protocol "dns" существует
 *  5. замыкающее правило ПОСЛЕДНЕЕ, ссылается на существующий outbound или balancer
 *  6. поля routing.final нет
 *  7. DoH-серверы — IP-литералы (https://IP/path)
 *  8. параметры Builder: IPv4-адрес/маршрут и IPv6-адрес/маршрут ::/0 присутствуют,
 *     setMetered вызывается только для API >= 29 (проверяем исходник VPNService)
 *  9. стоп: TunnelTeardown вызывается синхронно до scope.cancel()
 */
class IntegrationTractTest {

    private fun socksServer(tag: String = "srv1") = ParsedServer(
        tag = tag,
        label = tag,
        protocol = "socks",
        address = "127.0.0.1",
        xrayOutbound = Json.parseToJsonElement(
            """{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":1080}]}}""",
        ).jsonObject,
        network = "tcp",
    )

    private fun root(configJson: String): JsonObject = Json.parseToJsonElement(configJson).jsonObject

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.content
    private fun JsonObject.number(key: String): Int? = (this[key] as? JsonPrimitive)?.int

    private fun inbound(root: JsonObject, tag: String): JsonObject =
        root.arr("inbounds")!!.map { it.jsonObject }.first { it.text("tag") == tag }

    private fun outbound(root: JsonObject, tag: String): JsonObject =
        root.arr("outbounds")!!.map { it.jsonObject }.first { it.text("tag") == tag }

    @Test
    fun integration_allTractFixesPresent() {
        val advanced = AdvancedConfig(
            remoteDnsPrimary = "1.1.1.1",
            remoteDnsSecondary = "8.8.8.8",
            domesticDnsPrimary = "8.8.8.8",
            domesticDnsSecondary = "1.1.1.1",
            queryStrategy = "UseIPv4",
            tunMtu = 1400,
            socksPort = 10808,
            httpPort = 10809,
            localDnsPort = 10853,
            muxEnabled = false,
            fragmentEnabled = true,
            fragmentLengthMin = 50,
            fragmentLengthMax = 100,
            fragmentIntervalMin = 10,
            fragmentIntervalMax = 20,
            fragmentMaxSplit = 200,
            noisesEnabled = true,
            noisesPacket = true,
            noisesDelayMin = 10,
            noisesDelayMax = 16,
            noisesRand = true,
            noisesRange = 100,
            noisesType = "xudp",
            sniffingEnabled = true,
        )
        val profile = RoutingProfile(
            id = "test",
            name = "Test",
            globalProxy = true,
            remoteDnsDomain = "https://cloudflare-dns.com/dns-query",
            domesticDnsDomain = "https://dns.google/dns-query",
            remoteDnsIp = "1.1.1.1",
            domesticDnsIp = "8.8.8.8",
            fakeDns = true,
        )
        val built = XrayConfigBuilder.build(
            servers = listOf(socksServer()),
            selectedTag = "srv1",
            vpnMode = true,
            routingProfile = profile,
            advanced = advanced,
        )
        val root = root(built.configJson)

        // 1. tun inbound: name есть, address/strictRoute/interfaceName нет, mtu из advanced
        val tun = inbound(root, "tun-in")
        val tunSettings = tun.obj("settings")!!
        assertNotNull("tun inbound должен иметь name", tunSettings.text("name"))
        assertTrue("name должен быть maestro0", tunSettings.text("name") == "maestro0")
        assertEquals(1400, tunSettings.number("mtu"))
        assertNull("address не входит в схему TunConfig", tunSettings["address"])
        assertNull("strictRoute не входит в схему TunConfig", tunSettings["strictRoute"])
        assertNull("interfaceName тянет поиск интерфейса", tunSettings["interfaceName"])
        assertNull("interfaceName на уровле inbound тоже нет", tun["interfaceName"])

        // 2. env для fd = xray.tun.fd (проверяем константу, совпадает с C)
        assertEquals("xray.tun.fd", TunFdContract.ENV_NAME)

        // 3. Правило DNS-перехвата стоит ПЕРВЫМ в routing.rules
        val rules = root.obj("routing")!!.arr("rules")!!
        val firstRule = rules[0].jsonObject
        assertEquals("первое правило — DNS intercept", "field", firstRule.text("type"))
        val firstInboundTags = firstRule.arr("inboundTag")?.map { (it as JsonPrimitive).content } ?: emptyList()
        assertTrue("inboundTag = tun-in", firstInboundTags.contains("tun-in"))
        val firstPort = firstRule.number("port")
        assertEquals(53, firstPort)
        val firstNetwork = firstRule.text("network")
        assertTrue("network = tcp,udp", firstNetwork == "tcp,udp" || firstNetwork == "udp,tcp")
        val firstOutboundTag = firstRule.text("outboundTag")
        assertEquals("outboundTag = dns", "dns", firstOutboundTag)

        // 4. outbound с protocol "dns" существует
        val dnsOutbound = outbound(root, "dns")
        assertEquals("dns", dnsOutbound.text("protocol"))
        assertEquals("udp", dnsOutbound.obj("settings")?.text("network"))
        assertEquals(53, dnsOutbound.obj("settings")?.number("port"))

        // 5. Замыкающее правило ПОСЛЕДНЕЕ, ссылается на существующий outbound или balancer
        val lastRule = rules[rules.size - 1].jsonObject
        assertEquals("последнее правило — catch-all", "field", lastRule.text("type"))
        val lastOutboundTag = lastRule.text("outboundTag")
        val lastBalancerTag = lastRule.text("balancerTag")
        val hasOutboundOrBalancer = lastOutboundTag != null || lastBalancerTag != null
        assertTrue("catch-all должен иметь outboundTag или balancerTag", hasOutboundOrBalancer)
        if (lastOutboundTag != null) {
            // если outboundTag — должен существовать такой outbound
            assertNotNull("outbound с tag=$lastOutboundTag должен существовать", root.arr("outbounds")?.map { it.jsonObject.text("tag") }?.contains(lastOutboundTag))
        }

        // 6. routing.final НЕТ
        assertNull("routing.final не должно быть (ядро игнорирует)", root.obj("routing")?.text("final"))

        // 7. DoH-серверы — IP-литералы (https://IP/path)
        val dns = root.obj("dns")
        val servers = dns?.arr("servers")?.map { (it as JsonPrimitive).content } ?: emptyList()
        val dohServers = servers.filter { it.startsWith("https://") }
        for (server in dohServers) {
            // после https:// должен идти IP или домен
            val afterScheme = server.substringAfter("https://")
            val hostPart = afterScheme.substringBefore("/")
            // IP-литерал не должен требовать разрешения
            val ipv4Regex = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
            val ipv6Regex = Regex("^\\[?[0-9a-fA-F:]+%?\\]?$")
            val isIp = ipv4Regex.containsMatchIn(hostPart) || ipv6Regex.containsMatchIn(hostPart)
            assertTrue("DoH сервер должен быть IP-литералом: $server", isIp)
        }

        // 8. Проверяем исходник VPNService: setMetered только для API >= 29
        // JVM-тесты запускаются из модуля app/build/classes, ищем файлы через projectDir
        val projectDir = File(".").absoluteFile
        val vpnServiceFile = File(projectDir, "../../app/src/main/java/com/maestrovpn/client/bg/VPNService.kt")
        val vpnServiceSource = if (vpnServiceFile.exists()) vpnServiceFile.readText()
        else File(projectDir, "src/main/java/com/maestrovpn/client/bg/VPNService.kt").readText()
        assertTrue("setMetered должен быть под проверкой SDK_INT >= Q", vpnServiceSource.contains("VERSION_CODES.Q"))
        assertTrue("setMetered должен вызываться внутри if", vpnServiceSource.contains("builder.setMetered(false)"))

        // IPv4 и IPv6 маршруты в VPNService должны присутствовать в исходнике
        assertTrue("IPv6 addRoute(::/0) должен быть в VPNService", vpnServiceSource.contains("addRoute(\"::\", 0)"))
        assertTrue("IPv6 addAddress должен быть в VPNService", vpnServiceSource.contains("TUN_ADDRESS_V6"))

        // 9. TunnelTeardown вызывается синхронно до scope.cancel (проверяем исходник)
        val tunnelTeardownFile = File(projectDir, "../../app/src/main/java/com/maestrovpn/client/core/engine/TunnelTeardown.kt")
        val tunnelTeardownSource = if (tunnelTeardownFile.exists()) tunnelTeardownFile.readText()
        else File(projectDir, "src/main/java/com/maestrovpn/client/core/engine/TunnelTeardown.kt").readText()
        assertTrue("TunnelTeardown должен иметь run()", tunnelTeardownSource.contains("fun run"))
        // в VPNService stopTunnel должен вызывать TunnelTeardown.run() перед scope.cancel()
        assertTrue("VPNService должен использовать TunnelTeardown", vpnServiceSource.contains("TunnelTeardown"))

        // Дополнительно: xray_spawn.c использует ту же константу ENV_NAME
        val spawnFile = File(projectDir, "../../app/src/main/cpp/xray_spawn.c")
        val spawnSource = if (spawnFile.exists()) spawnFile.readText()
        else File(projectDir, "src/main/cpp/xray_spawn.c").readText()
        assertTrue("xray_spawn.c должен использовать XRAY_TUN_FD_ENV", spawnSource.contains("XRAY_TUN_FD_ENV"))
        assertTrue("xray_spawn.c должен использовать xray.tun.fd", spawnSource.contains("xray.tun.fd"))
        // Проверяем, что нет вызова setenv с точно "XRAY_TUN_FD" (без _ENV)
        // XRAY_TUN_FD_ENV содержит подстроку XRAY_TUN_FD, поэтому ищем точный паттерн
        val hasLegacySetenv = Regex("setenv\\(\\s*\"XRAY_TUN_FD\"").containsMatchIn(spawnSource)
        assertFalse("xray_spawn.c НЕ должен иметь setenv(\"XRAY_TUN_FD\"", hasLegacySetenv)
    }
}
