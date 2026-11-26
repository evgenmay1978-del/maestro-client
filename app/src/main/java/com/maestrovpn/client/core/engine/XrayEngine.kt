package com.maestrovpn.client.core.engine

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.maestrovpn.client.App
import com.maestrovpn.client.bg.ProxyService
import com.maestrovpn.client.core.routing.GeoDataFiles
import com.maestrovpn.client.bg.VPNService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Единственный движок клиента — Xray-core (срез 7, issue #8).
 *
 * Бинарь — app/src/main/jniLibs/<abi>/libmaestro_subxhttp.so, запускается
 * дочерним процессом через нативный fork/exec (XrayNative): только так
 * дочернему Xray можно передать tun fd от VpnService — ProcessBuilder
 * закрывает все fd >= 3 при exec.
 *
 * Режимы:
 * - VPN: конфиг содержит tun inbound — поднимается [VPNService].
 * - Только прокси: SOCKS :10808 + HTTP :10809 — [ProxyService], без VPN.
 *
 * Статистика — Xray Stats API (gRPC через `xray api statsquery`):
 * конфиг обязан содержать api inbound + routing-правило
 * inboundTag ["api"] -> outboundTag "api", иначе dokodemo-door форвардит
 * соединение сам на себя и процесс сжирает все fd (проверено на 26.11.9).
 */
class XrayEngine : VpnEngine {

    companion object {
        const val CONFIG_FILE_NAME = "xray-config.json"
        const val LOG_FILE_NAME = "xray.log"
        const val API_PORT = 10085
        const val TUN_INBOUND_TAG = "tun-in"

        /** Имя нативной библиотеки Xray в jniLibs. */
        const val BINARY_NAME = "libmaestro_subxhttp.so"

        private const val VALIDATE_TIMEOUT_MS = 15_000L
        private const val STATS_TIMEOUT_MS = 5_000L
    }

    private var app: Application? = null

    override var lastError: String? = null
        private set

    override val version: String
        get() = cachedVersion ?: queryVersion().also { cachedVersion = it }

    private var cachedVersion: String? = null

    override fun init(app: Application) {
        this.app = app
    }

    fun binaryFile(): File? {
        val app = this.app ?: App.application
        return File(app.applicationInfo.nativeLibraryDir, BINARY_NAME)
            .takeIf { it.exists() }
    }

    fun configFile(): File = File(App.application.filesDir, CONFIG_FILE_NAME)

    fun logFile(): File = File(App.application.filesDir, LOG_FILE_NAME)

    /** Есть ли в конфиге tun inbound — решает, VPN это или «только прокси». */
    fun isVpnMode(configJson: String): Boolean {
        return try {
            val inbounds = JSONObject(configJson).optJSONArray("inbounds") ?: return false
            for (i in 0 until inbounds.length()) {
                if (inbounds.optJSONObject(i)?.optString("protocol") == "tun") return true
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun serviceClass(configJson: String?): Class<*> =
        if (configJson != null && isVpnMode(configJson)) VPNService::class.java
        else ProxyService::class.java

    /**
     * Проверка конфига без запуска: `xray run -test -c <file>`.
     * Возвращает false + [lastError] при невалидном конфиге или таймауте.
     */
    override fun validateConfig(configJson: String): Boolean {
        lastError = null
        val binary = binaryFile() ?: run {
            lastError = "Xray binary not found"
            return false
        }
        // Геоданные (§8): Xray ищет geoip.dat/geosite.dat рядом с бинарём ядра.
        binary.parentFile?.let { GeoDataFiles.install(it) }
        return try {
            val tmp = File.createTempFile("xray-validate-", ".json", App.application.cacheDir)
            try {
                tmp.writeText(configJson)
                val result = runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(VALIDATE_TIMEOUT_MS) {
                        runProcess(
                            listOf(binary.absolutePath, "run", "-test", "-c", tmp.absolutePath),
                            VALIDATE_TIMEOUT_MS,
                        )
                    }
                } ?: run {
                    lastError = "Config validation timed out"
                    return false
                }
                if (result.exitCode == 0) {
                    true
                } else {
                    lastError = result.output.lines().lastOrNull { it.isNotBlank() }
                        ?.take(300) ?: "Invalid Xray config"
                    false
                }
            } finally {
                tmp.delete()
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    /**
     * Запустить туннель: валидация конфига, атомарная запись в
     * filesDir/xray-config.json, старт нужной службы. Реальный статус
     * приходит по AIDL от службы; false — только при отказе до старта.
     */
    override fun start(configJson: String): Boolean {
        lastError = null
        if (!validateConfig(configJson)) return false
        val configFile = configFile()
        try {
            val tmp = File(configFile.parentFile, "${CONFIG_FILE_NAME}.tmp")
            tmp.writeText(configJson)
            if (!tmp.renameTo(configFile)) {
                tmp.delete()
                configFile.writeText(configJson)
            }
        } catch (e: Exception) {
            lastError = "Cannot save config: ${e.message}"
            return false
        }
        val context: Context = App.application
        val service = serviceClass(configJson)
        val intent = Intent(context, service).apply {
            putExtra("config_path", configFile.absolutePath)
        }
        return try {
            ContextCompat.startForegroundService(context, intent)
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    override fun stop() {
        val context: Context = App.application
        // Останавливаем обе службы: работает только та, что запущена.
        context.stopService(Intent(context, VPNService::class.java))
        context.stopService(Intent(context, ProxyService::class.java))
    }

    /**
     * Трафик tun inbound за сессию (байты): uplink, downlink.
     * null — ядро не отвечает (не запущено / API недоступен).
     * Счётчик без трафика приходит без поля "value" — это 0, а не ошибка.
     */
    fun queryStats(): Pair<Long, Long>? {
        val binary = binaryFile() ?: return null
        return try {
            val result = runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(STATS_TIMEOUT_MS) {
                    runProcess(
                        listOf(
                            binary.absolutePath, "api", "statsquery",
                            "--server=127.0.0.1:$API_PORT",
                            "-pattern", "inbound>>>$TUN_INBOUND_TAG>>>traffic",
                        ),
                        STATS_TIMEOUT_MS,
                    )
                }
            } ?: return null
            if (result.exitCode != 0) return null
            parseStats(result.output)
        } catch (_: Exception) {
            null
        }
    }

    internal fun parseStats(json: String): Pair<Long, Long>? {
        return try {
            val stats = JSONObject(json).optJSONArray("stat") ?: return null
            var up = 0L
            var down = 0L
            for (i in 0 until stats.length()) {
                val item = stats.optJSONObject(i) ?: continue
                val value = item.optLong("value", 0L)
                when {
                    item.optString("name").endsWith(">>>uplink") -> up = value
                    item.optString("name").endsWith(">>>downlink") -> down = value
                }
            }
            up to down
        } catch (_: Exception) {
            null
        }
    }

    private fun queryVersion(): String {
        val binary = binaryFile() ?: return "xray"
        return try {
            val result = runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(5_000L) {
                    runProcess(listOf(binary.absolutePath, "version"), 5_000L)
                }
            } ?: return "xray"
            // "Xray 26.11.9 (Xray, Penetrates Everything.) ..."
            val firstLine = result.output.lineSequence().firstOrNull().orEmpty()
            val m = Regex("""^Xray\s+([0-9.]+)""").find(firstLine)
            if (m != null) "xray ${m.groupValues[1]}" else "xray"
        } catch (_: Exception) {
            "xray"
        }
    }

    private data class ProcessResult(val exitCode: Int, val output: String)

    private fun runProcess(command: List<String>, timeoutMs: Long): ProcessResult {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) {
            process.destroyForcibly()
            process.waitFor(2, TimeUnit.SECONDS)
            return ProcessResult(-1, "timeout")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        return ProcessResult(process.exitValue(), output)
    }
}
