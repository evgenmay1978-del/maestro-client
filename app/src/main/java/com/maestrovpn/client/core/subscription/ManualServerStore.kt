package com.maestrovpn.client.core.subscription

import android.content.Context
import com.maestrovpn.client.core.engine.VpnEngine
import com.maestrovpn.client.core.settings.AdvancedSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// Хранилище ручных серверов (срез 5, issue #1; переведено на Xray-only ядро
// в срезе 7). То же файловое хранилище, что у подписок (SubscriptionManager):
// filesDir, index в одном JSON-файле, запись через tmp+rename.
// Путь загрузки подписок НЕ трогается: этот класс не вызывает download,
// downloadWithFallback, importSubscription или refresh.
class ManualServerStore(
    private val context: Context,
    private val engine: VpnEngine,
) {
    private val dir: File
        get() = File(context.filesDir, "manual-servers").apply { mkdirs() }

    private val indexFile: File
        get() = File(dir, "servers.json")

    private val json = Json { ignoreUnknownKeys = true }

    fun list(): List<ManualServer> {
        val f = indexFile
        if (!f.exists()) return emptyList()
        return try {
            json.decodeFromString<List<ManualServer>>(f.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(id: String): ManualServer? = list().firstOrNull { it.id == id }

    /**
     * Сохранить (создать или обновить). Возвращает ошибку валидации/дубля
     * или null при успехе. Проверка движком — до записи (validate-before-save,
     * как у подписок): битый сервер не сохраняется.
     */
    fun save(server: ManualServer): String? {
        val errors = server.validate()
        if (errors.isNotEmpty()) return errors.first()
        if (list().any { it.id != server.id && it.tag.equals(server.tag, ignoreCase = true) }) {
            return "Сервер с именем «${server.tag}» уже есть"
        }
        val (parsed, reason) = server.toParsedServer()
        if (parsed == null) return reason ?: "Сервер не удалось преобразовать в конфиг"
        // Сохранение проверяет базовый конфиг; настройки применяются
        // при подключении (buildConfigFor) и там же валидируются движком.
        val built = XrayConfigBuilder.build(listOf(parsed), parsed.tag)
        if (!engine.validateConfig(built.configJson)) {
            return "Собранный конфиг не прошёл проверку движка: " +
                (engine.lastError?.takeIf { it.isNotBlank() } ?: "причина не сообщена")
        }
        val updated = list().filterNot { it.id == server.id } + server
        writeIndex(updated)
        return null
    }

    fun delete(server: ManualServer) {
        writeIndex(list().filterNot { it.id == server.id })
        if (loadSelection()?.serverId == server.id) clearSelection()
    }

    private fun writeIndex(list: List<ManualServer>) {
        val tmp = File(dir, indexFile.name + ".tmp")
        tmp.writeText(json.encodeToString(list))
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(json.encodeToString(list))
            tmp.delete()
        }
    }

    /**
     * Собрать Xray-конфиг для выбора сервера (тот же путь, что
     * SubscriptionManager.buildConfigFor у подписок: сборка -> проверка
     * движком). Возвращает JSON конфига; отдельный манифест больше не нужен —
     * xhttp-узлы теперь обычные Xray outbound'ы в том же конфиге.
     */
    fun buildConfigFor(server: ManualServer): Result<String> =
        runCatching {
            val pair = server.toParsedServer()
            val parsed = pair.first
                ?: throw IllegalStateException(pair.second ?: "Сервер не удалось преобразовать")
            val built = XrayConfigBuilder.build(
                servers = listOf(parsed),
                selectedTag = parsed.tag,
                advanced = AdvancedSettings.snapshot(),
            )
            if (!engine.validateConfig(built.configJson)) {
                throw IllegalStateException(
                    "Собранный конфиг не прошёл проверку движка: " +
                        (engine.lastError?.takeIf { it.isNotBlank() } ?: "причина не сообщена"),
                )
            }
            built.configJson
        }

    // Выбор ручного сервера (аналог ServerSelection у подписок).
    @Serializable
    data class ManualSelection(val serverId: String, val tag: String)

    private val selectionFile: File
        get() = File(context.filesDir, "selected-manual-server.json")

    fun saveSelection(serverId: String, tag: String) {
        val tmp = File(context.filesDir, selectionFile.name + ".tmp")
        tmp.writeText(json.encodeToString(ManualSelection(serverId, tag)))
        if (!tmp.renameTo(selectionFile)) {
            selectionFile.writeText(json.encodeToString(ManualSelection(serverId, tag)))
            tmp.delete()
        }
    }

    fun loadSelection(): ManualSelection? {
        val f = selectionFile
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<ManualSelection>(f.readText()) }.getOrNull()
    }

    fun clearSelection() {
        selectionFile.delete()
    }
}