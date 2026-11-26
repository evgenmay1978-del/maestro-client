package com.maestrovpn.client.core.routing

import com.maestrovpn.client.core.subscription.ParsedServer
import com.maestrovpn.client.core.subscription.SubscriptionFormat
import com.maestrovpn.client.core.subscription.XrayConfigBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Раздел 10: конфиг с правилами профиля проходит настоящий
 * `xray run -test` (бинарь из jniLibs, как в SDK-срезе).
 *
 * Тест пропускается, если бинаря нет (система без Android-сборки).
 */
class RoutingConfigXrayTest {

    private val socksServer = ParsedServer(
        tag = "srv1",
        label = "srv1",
        protocol = "socks",
        address = "127.0.0.1",
        xrayOutbound = Json.parseToJsonElement(
            """{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":1080}]}}""",
        ).jsonObject,
        network = "tcp",
    )

    private val binary: File?
        get() = listOf(
            "src/main/jniLibs/arm64-v8a/libmaestro_subxhttp.so",
            "src/main/jniLibs/x86_64/libmaestro_subxhttp.so",
        ).map { File(it) }.firstOrNull { it.canExecute() || it.setExecutable(true) }

    /**
     * Xray ищет геоданные рядом с бинарём. В JVM-тесте assets недоступны,
     * поэтому кладём geoip.dat туда сами (из src/main/assets).
     */
    private fun prepareGeoData(bin: File) {
        val dir = bin.parentFile
        val geoip = File("src/main/assets/geoip.dat")
        if (geoip.exists()) {
            val target = File(dir, "geoip.dat")
            if (!target.exists() || target.length() != geoip.length()) {
                geoip.copyTo(target, overwrite = true)
            }
        }
    }

    @Test
    fun `config with routing profile passes xray run -test`() {
        val bin = binary ?: assumeTrue("xray binary missing", false).let { null }
            ?: return
        prepareGeoData(bin)
        // geoip-правила работают с bundled geoip.dat (проверено xray run -test);
        // geosite.dat из Loyalsoldier — protobuf-формат, Xray его не читает,
        // поэтому geosite: тестом не покрываем.
        val profile = RoutingProfile(
            id = "p", name = "P",
            directSites = listOf("domain:example.com"),
            directIp = listOf("geoip:private", "geoip:ru"),
            blockIp = listOf("geoip:ru"),
            dnsHosts = mapOf("local.test" to "127.0.0.1"),
        )
        val config = XrayConfigBuilder.build(listOf(socksServer), "srv1", true, profile)
        val tmp = Files.createTempFile("xray-route-", ".json").toFile()
        tmp.writeText(config.configJson)
        try {
            val proc = ProcessBuilder(bin.absolutePath, "run", "-test", "-c", tmp.absolutePath)
                .redirectErrorStream(true).start()
            proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(0, proc.exitValue())
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `config without profile still passes xray run -test`() {
        val bin = binary ?: return
        prepareGeoData(bin)
        val config = XrayConfigBuilder.build(listOf(socksServer), "srv1")
        val tmp = Files.createTempFile("xray-default-", ".json").toFile()
        tmp.writeText(config.configJson)
        try {
            val proc = ProcessBuilder(bin.absolutePath, "run", "-test", "-c", tmp.absolutePath)
                .redirectErrorStream(true).start()
            proc.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(0, proc.exitValue())
        } finally {
            tmp.delete()
        }
    }
}
