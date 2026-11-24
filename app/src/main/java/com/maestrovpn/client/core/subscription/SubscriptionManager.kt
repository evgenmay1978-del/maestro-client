package com.maestrovpn.client.core.subscription

import android.content.Context
import com.maestrovpn.client.core.engine.VpnEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// Подписки (срез 1): загрузка по URL + validate-before-save + атомарная запись.
// Парсер share-ссылок / Xray JSON — следующий срез (таблица соответствия §3.10, НЕТ).
// Загрузка идёт напрямую; tunnel-fetch — срез 2 (см. core-survey).
@Serializable
data class SubscriptionInfo(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdatedMillis: Long,
    val fileName: String,
)

class SubscriptionManager(
    private val context: Context,
    private val engine: VpnEngine,
) {
    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024 // как у Maestro (ProfileImportHandler)
        const val TIMEOUT_MS = 10_000
        const val USER_AGENT = "MaestroClient/0.1.0-slice1"
    }

    private val dir: File
        get() = File(context.filesDir, "subscriptions").apply { mkdirs() }

    private val indexFile: File
        get() = File(dir, "index.json")

    private val json = Json { ignoreUnknownKeys = true }

    fun list(): List<SubscriptionInfo> {
        val f = indexFile
        if (!f.exists()) return emptyList()
        return try {
            json.decodeFromString<List<SubscriptionInfo>>(f.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveIndex(list: List<SubscriptionInfo>) {
        indexFile.writeText(json.encodeToString(list))
    }

    /** Скачать содержимое подписки. Без парсинга — сырые байты как текст. */
    fun download(url: String): Result<String> = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = conn.responseCode
            require(code in 200..299) { "HTTP $code" }
            val bytes = conn.inputStream.use { it.readBytes() }
            require(bytes.size <= MAX_BYTES) { "Подписка больше ${MAX_BYTES / 1024 / 1024} МБ" }
            String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Сохранить подписку только после проверки движком (validate-before-save):
     * битая подписка живой конфиг не трогает. Запись атомарная (tmp + rename).
     */
    fun saveValidated(info: SubscriptionInfo, content: String): Result<Unit> = runCatching {
        require(engine.validateConfig(content)) { "Конфиг подписки не прошёл проверку движка" }
        val target = File(dir, info.fileName)
        val tmp = File(dir, info.fileName + ".tmp")
        tmp.writeText(content)
        require(tmp.renameTo(target)) { "Не удалось сохранить файл подписки" }
        val updated = list().filterNot { it.id == info.id } + info
        saveIndex(updated)
    }

    fun loadContent(info: SubscriptionInfo): String? {
        val f = File(dir, info.fileName)
        return if (f.exists()) f.readText() else null
    }

    fun delete(info: SubscriptionInfo) {
        File(dir, info.fileName).delete()
        saveIndex(list().filterNot { it.id == info.id })
    }

    /** Обновить подписку: скачать заново и сохранить только если проверка прошла. */
    fun refresh(info: SubscriptionInfo): Result<Unit> = runCatching {
        val content = download(info.url).getOrThrow()
        saveValidated(info.copy(lastUpdatedMillis = System.currentTimeMillis()), content).getOrThrow()
    }
}
