package com.maestrovpn.client.core.subscription

import android.content.Context
import com.maestrovpn.client.core.engine.VpnEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException

// Подписки (срез 3, движок — Xray с среза 7, issue #8): загрузка с
// CDN-фолбэком + парсер + validate-before-save.
// Дефект среза 2: добавление подписки Maestro (?format=xray) падало с пустой
// причиной — причин было две: (а) загрузка напрямую с wapmixx.ru:8911, а прямой
// TLS туда из российских сетей висит (диагностика 23.11: TCP ok, TLS timeout);
// (б) парсера Xray-формата не было вообще. Обе закрыты здесь: фолбэк на
// cdn-test.wapmixx.ru (тот же путь и query, как MaestroSub.cdnFallbackUrl
// у Maestro) и парсер (SubscriptionParser) + сборка Xray-конфига
// (XrayConfigBuilder, xhttp — обычный outbound в том же конфиге).
// Пустая причина ошибки больше невозможна: каждый отказ несёт текст.
@Serializable
data class SubscriptionInfo(
    val id: String,
    val name: String,
    val url: String,
    val lastUpdatedMillis: Long,
    val fileName: String,
    val updateIntervalHours: Int? = null,
    val supportUrl: String? = null,
    val webPageUrl: String? = null,
    val expire: Long? = null,
)

enum class DownloadVia { DIRECT, CDN }

sealed class DownloadResult {
    data class Ok(
        val content: String,
        val via: DownloadVia,
        /** Что пробовали, по порядку — для диагностики в сообщении об ошибке. */
        val attempts: List<String>,
        val headers: SubscriptionHeaders = SubscriptionHeaders(),
    ) : DownloadResult()

    data class Failed(val reason: String) : DownloadResult()
}

sealed class ImportResult {
    data class Success(
        /** Число серверов. */
        val servers: Int,
        val via: DownloadVia,
        val xhttpServers: Int,
        val issues: List<ParseIssue>,
    ) : ImportResult()

    data class Failure(val reason: String) : ImportResult()
}

class SubscriptionManager(
    private val context: Context,
    private val engine: VpnEngine,
) {
    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024 // как у Maestro (ProfileImportHandler)
        const val TIMEOUT_MS = 10_000
        const val USER_AGENT = "MaestroClient/0.1.0-slice3"

        /** Наш CDN-домен — тот же, что у Maestro (cdn-test.wapmixx.ru). */
        const val CDN_HOST = "cdn-test.wapmixx.ru"

        /** Фолбэк только для нашей панели; чужие подписки идут только напрямую. */
        fun cdnFallbackUrl(url: String): String? = runCatching {
            val source = URI(url)
            if (!source.scheme.equals("https", ignoreCase = true)) return null
            if (!source.host.equals("wapmixx.ru", ignoreCase = true)) return null
            if (source.rawUserInfo != null) return null
            val path = source.rawPath ?: return null
            if (!path.startsWith("/sub/")) return null
            "https://$CDN_HOST$path" + (source.rawQuery?.let { "?$it" } ?: "")
        }.getOrNull()
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

    /** Одно скачивание без фолбэка. Исключение всегда с непустым текстом. */
    data class Downloaded(val content: String, val headers: SubscriptionHeaders)

    fun download(url: String): Result<Downloaded> = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "*/*")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw java.io.IOException("HTTP $code")
            }
            val headers = SubscriptionHeaders.parse(conn.headerFields)
            val bytes = conn.inputStream.use { it.readBytes() }
            if (bytes.size > MAX_BYTES) {
                throw java.io.IOException("Подписка больше ${MAX_BYTES / 1024 / 1024} МБ")
            }
            Downloaded(String(bytes, Charsets.UTF_8), headers)
        } finally {
            conn.disconnect()
        }
    }.recoverCatching { e ->
        throw java.io.IOException(describeError(e), e)
    }

    /**
     * Скачивание с фолбэком: сначала прямой URL, при транспортной ошибке
     * (таймаут/сеть/TLS) — тот же URL на CDN. HTTP-ошибки (404/403) не
     * фолбэчатся: CDN вернёт то же самое, а причина станет непонятной.
     */
    fun downloadWithFallback(url: String): DownloadResult {
        val attempts = ArrayList<String>()
        val direct = download(url)
        if (direct.isSuccess) {
            val value = direct.getOrThrow()
            return DownloadResult.Ok(value.content, DownloadVia.DIRECT, attempts, value.headers)
        }
        val directError = direct.exceptionOrNull()
        attempts += "напрямую: " + describeError(directError)
        // Фолбэк только при транспортной ошибке; HTTP-код — это ответ сервера.
        val isTransportError = directError?.let { isTransportError(it) } ?: false
        val fallbackUrl = cdnFallbackUrl(url)
        if (!isTransportError || fallbackUrl == null) {
            return DownloadResult.Failed(
                "Не удалось загрузить подписку (" + attempts.joinToString("; ") + ")",
            )
        }
        val viaCdn = download(fallbackUrl)
        if (viaCdn.isSuccess) {
            val value = viaCdn.getOrThrow()
            return DownloadResult.Ok(value.content, DownloadVia.CDN, attempts, value.headers)
        }
        attempts += "через CDN: " + describeError(viaCdn.exceptionOrNull())
        return DownloadResult.Failed(
            "Не удалось загрузить подписку (" + attempts.joinToString("; ") + ")",
        )
    }

    private fun isTransportError(e: Throwable?): Boolean {
        var current = e
        while (current != null) {
            if (current is SocketTimeoutException ||
                current is UnknownHostException ||
                current is SSLException ||
                current is java.net.ConnectException ||
                current is java.io.IOException &&
                current.message?.contains("HTTP ") != true
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /** Человекочитаемая причина; никогда не пустая (минимум — имя класса). */
    fun describeError(e: Throwable?): String {
        if (e == null) return "неизвестная ошибка"
        val message = e.message?.trim().orEmpty()
        if (message.isNotEmpty()) {
            return when (e) {
                is SocketTimeoutException -> "таймаут ${TIMEOUT_MS / 1000} с ($message)"
                else -> message
            }
        }
        val cause = e.cause
        if (cause != null && !cause.message.isNullOrBlank()) return describeError(cause)
        return e.javaClass.simpleName
    }

    /**
     * Полный импорт: скачать (с фолбэком) → распарсить → собрать Xray
     * конфиг → проверить движком → сохранить сырое тело.
     * Битая подписка живой конфиг не трогает (validate-before-save).
     */
    suspend fun importSubscription(name: String, url: String): ImportResult = withContext(Dispatchers.IO) {
        val trimmedUrl = url.trim()
        if (list().any { it.url.trim() == trimmedUrl }) {
            return@withContext ImportResult.Failure("Такая подписка уже добавлена")
        }
        val downloaded = downloadWithFallback(trimmedUrl)
        if (downloaded is DownloadResult.Failed) {
            return@withContext ImportResult.Failure(downloaded.reason)
        }
        downloaded as DownloadResult.Ok
        saveParsed(
            info = SubscriptionInfo(
                id = java.util.UUID.randomUUID().toString(),
                name = name.ifBlank { downloaded.headers.title ?: trimmedUrl },
                updateIntervalHours = downloaded.headers.updateIntervalHours,
                supportUrl = downloaded.headers.supportUrl,
                webPageUrl = downloaded.headers.webPageUrl,
                expire = downloaded.headers.expire,
                url = trimmedUrl,
                lastUpdatedMillis = System.currentTimeMillis(),
                fileName = java.util.UUID.randomUUID().toString() + ".txt",
            ),
            content = downloaded.content,
            via = downloaded.via,
        )
    }

    /** Обновить подписку: скачать заново и сохранить только если проверка прошла. */
    suspend fun refresh(info: SubscriptionInfo): ImportResult = withContext(Dispatchers.IO) {
        val downloaded = downloadWithFallback(info.url)
        if (downloaded is DownloadResult.Failed) {
            return@withContext ImportResult.Failure(downloaded.reason)
        }
        downloaded as DownloadResult.Ok
        saveParsed(
            info = info.copy(
                lastUpdatedMillis = System.currentTimeMillis(),
                updateIntervalHours = downloaded.headers.updateIntervalHours ?: info.updateIntervalHours,
                supportUrl = downloaded.headers.supportUrl ?: info.supportUrl,
                webPageUrl = downloaded.headers.webPageUrl ?: info.webPageUrl,
                expire = downloaded.headers.expire ?: info.expire,
            ),
            content = downloaded.content,
            via = downloaded.via,
        )
    }

    private fun saveParsed(
        info: SubscriptionInfo,
        content: String,
        via: DownloadVia,
    ): ImportResult {
        val parsed = SubscriptionParser.parse(content)
        if (parsed.servers.isEmpty()) {
            return ImportResult.Failure(noServersReason(parsed))
        }
        val built = XrayConfigBuilder.build(parsed.servers, selectedTag = null)
        if (!engine.validateConfig(built.configJson)) {
            return ImportResult.Failure(
                "Собранный конфиг не прошёл проверку движка " +
                    "(${parsed.servers.size} серверов распознано, но Xray отклонил конфиг)",
            )
        }
        saveRaw(info, content)
        return ImportResult.Success(
            servers = parsed.servers.size,
            via = via,
            xhttpServers = parsed.servers.count { it.network == "xhttp" },
            issues = parsed.issues,
        )
    }

    private fun saveRaw(info: SubscriptionInfo, content: String) {
        val target = File(dir, info.fileName)
        val tmp = File(dir, info.fileName + ".tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
        val updated = list().filterNot { it.id == info.id } + info
        saveIndex(updated)
        engine.onSubscriptionChanged()
    }

    private fun noServersReason(parsed: ParsedSubscription): String {
        val sb = StringBuilder("Серверов не найдено (формат: ${parsed.format}")
        if (parsed.ignoredLines > 0) sb.append(", пропущено строк: ${parsed.ignoredLines}")
        sb.append(")")
        val shown = parsed.issues.take(3)
        if (shown.isNotEmpty()) {
            sb.append(": ")
            sb.append(shown.joinToString("; ") { "${it.hint} — ${it.reason}" })
        }
        if (parsed.issues.size > 3) {
            sb.append(" (и ещё ${parsed.issues.size - 3})")
        }
        if (parsed.format == SubscriptionFormat.UNKNOWN) {
            sb.append(". Подписка должна быть списком ссылок, base64-списком или Xray JSON")
        }
        return sb.toString()
    }

    /** Распарсить сохранённую подписку (сырое тело → серверы). */
    fun servers(info: SubscriptionInfo): ParsedSubscription {
        val content = loadContent(info) ?: return ParsedSubscription(
            SubscriptionFormat.EMPTY, emptyList(),
            listOf(ParseIssue(info.name.take(80), "файл подписки не найден")), 0,
        )
        return SubscriptionParser.parse(content)
    }

    /**
     * Собрать Xray конфиг для выбора сервера. null — только при пустой
     * подписке; причина — в тексте исключения.
     */
    fun buildConfigFor(info: SubscriptionInfo, selectedTag: String?): Result<String> =
        runCatching {
            val parsed = servers(info)
            if (parsed.servers.isEmpty()) {
                throw IllegalStateException(noServersReason(parsed))
            }
            val built = XrayConfigBuilder.build(parsed.servers, selectedTag)
            if (!engine.validateConfig(built.configJson)) {
                throw IllegalStateException("Собранный конфиг не прошёл проверку движка")
            }
            built.configJson
        }

    fun loadContent(info: SubscriptionInfo): String? {
        val f = File(dir, info.fileName)
        return if (f.exists()) f.readText() else null
    }

    fun delete(info: SubscriptionInfo) {
        File(dir, info.fileName).delete()
        saveIndex(list().filterNot { it.id == info.id })
    }

    // Выбор сервера (срез 3): подписка + тег, чтобы выбор пережил рестарт.
    // Файл маленький, пишется через tmp+rename, как манифест раннера.
    @Serializable
    data class ServerSelection(val subscriptionId: String, val tag: String)

    private val selectionFile: File
        get() = File(context.filesDir, "selected-server.json")

    fun saveSelection(subscriptionId: String, tag: String) {
        val tmp = File(context.filesDir, selectionFile.name + ".tmp")
        tmp.writeText(json.encodeToString(ServerSelection(subscriptionId, tag)))
        if (!tmp.renameTo(selectionFile)) {
            selectionFile.writeText(json.encodeToString(ServerSelection(subscriptionId, tag)))
            tmp.delete()
        }
    }

    fun loadSelection(): ServerSelection? {
        val f = selectionFile
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<ServerSelection>(f.readText()) }.getOrNull()
    }

    fun clearSelection() {
        selectionFile.delete()
    }
}
