package com.maestrovpn.client.core.subscription

import android.content.Context
import com.maestrovpn.client.core.engine.VpnEngine
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

// Хранилище ручных серверов (срез 5, issue #1).
// То же файловое хранилище, что у подписок (SubscriptionManager): filesDir,
// index в одном JSON-файле, запись через tmp+rename. Room (Profile/RemoteServer)
// не трогается: RemoteServer — это URL удалённых профилей Maestro, а не серверы.
// Путь загрузки подписок НЕ трогается: этот класс не вызывает download,
// downloadWithFallback, importSubscription или refresh.
class ManualServerStore(
    private val context: Context,
    private val engine: VpnEngine,
) {
    companion object {
        const val MANIFEST_FILE_NAME = "subxhttp-nodes.json"
    }

    private val dir: File
        get() = File(context.filesDir, "manual-servers").apply { mkdirs() }

    private val indexFile: File
        get() = File(dir, "servers.json")

    private val manifestFile: File
        get() = File(context.filesDir, MANIFEST_FILE_NAME)

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
        val built = SingBoxConfigBuilder.build(listOf(parsed), parsed.tag)
        if (!engine.validateConfig(built.configJson)) {
            return "Собранный конфиг не прошёл проверку движка"
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
     * Собрать sing-box конфиг для выбора сервера (тот же путь, что
     * SubscriptionManager.buildConfigFor у подписок: сборка -> проверка
     * движком -> манифест раннера). Манифест переписывается под выбранный
     * сервер: при xhttp — манифест узла, иначе старый манифест удаляется,
     * чтобы раннер не поднялся по stale-узлу другой подписки/сервера.
     */
    fun buildConfigFor(server: ManualServer): Result<SingBoxConfigBuilder.BuiltConfig> =
        runCatching {
            val pair = server.toParsedServer()
            val parsed = pair.first
                ?: throw IllegalStateException(pair.second ?: "Сервер не удалось преобразовать")
            val built = SingBoxConfigBuilder.build(listOf(parsed), parsed.tag)
            if (!engine.validateConfig(built.configJson)) {
                throw IllegalStateException("Собранный конфиг не прошёл проверку движка")
            }
            if (built.manifestJson != null) {
                val tmp = File(context.filesDir, manifestFile.name + ".tmp")
                tmp.writeText(built.manifestJson)
                if (!tmp.renameTo(manifestFile)) {
                    manifestFile.writeText(built.manifestJson)
                    tmp.delete()
                }
            } else {
                manifestFile.delete()
            }
            engine.onSubscriptionChanged()
            built
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
