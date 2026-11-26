package com.maestrovpn.client.core.routing

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Хранилище профилей маршрутизации (раздел 7).
 *
 * Файлы в filesDir (тот же паттерн, что у SubscriptionManager):
 * - routing-profiles.json — список профилей;
 * - routing-active.json — активный профиль + привязка к подписке.
 *
 * Запись атомарная (tmp + rename), как у подписок.
 */
class RoutingProfileStore(private val context: Context) {

    @Serializable
    data class ActiveProfile(
        val profileId: String,
        /** null — профиль общий (server list), иначе привязан к подписке. */
        val subscriptionId: String?,
    )

    private val dir: File
        get() = File(context.filesDir, "routing").apply { mkdirs() }

    private val indexFile: File
        get() = File(dir, "routing-profiles.json")

    private val activeFile: File
        get() = File(dir, "routing-active.json")

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }

    fun list(): List<RoutingProfile> {
        val f = indexFile
        if (!f.exists()) return emptyList()
        return try {
            json.decodeFromString<List<RoutingProfile>>(f.readText())
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun get(id: String): RoutingProfile? = list().firstOrNull { it.id == id }

    /** Сохранить (создать или обновить). Пустой id создаёт новый профиль. */
    fun save(profile: RoutingProfile): RoutingProfile {
        val newId = if (profile.id.isBlank()) UUID.randomUUID().toString() else profile.id
        val updated = profile.copy(id = newId, lastUpdated = System.currentTimeMillis())
        val all = list().filterNot { it.id == updated.id } + updated
        writeIndex(all)
        return updated
    }

    fun delete(profile: RoutingProfile) {
        writeIndex(list().filterNot { it.id == profile.id })
        if (active()?.profileId == profile.id) clearActive()
    }

    /**
     * Профили, привязанные к подписке: свой набор у каждой подписки
     * (раздел 7 — «individual rule set»).
     */
    fun forSubscription(subscriptionId: String): List<RoutingProfile> =
        list().filter { it.subscriptionId == subscriptionId }

    // ---------- активный профиль ----------

    fun active(): ActiveProfile? {
        val f = activeFile
        if (!f.exists()) return null
        return runCatching { json.decodeFromString<ActiveProfile>(f.readText()) }.getOrNull()
    }

    /**
     * Сделать профиль активным. Привязанный к подписке профиль работает
     * только для своей подписки; общий — для выбранного сервера.
     */
    fun setActive(profile: RoutingProfile, subscriptionId: String?) {
        val bound = profile.subscriptionId
        val allowed = bound == null || bound == subscriptionId
        if (!allowed) return
        writeActive(ActiveProfile(profile.id, subscriptionId))
    }

    fun clearActive() {
        activeFile.delete()
    }

    /** Активный профиль для конкретной подписки (или общий). */
    fun activeFor(subscriptionId: String?): RoutingProfile? {
        val a = active() ?: return null
        if (a.subscriptionId != null && a.subscriptionId != subscriptionId) return null
        return get(a.profileId)
    }

    // ---------- запись ----------

    private fun writeIndex(list: List<RoutingProfile>) {
        write(indexFile, json.encodeToString(list))
    }

    private fun writeActive(active: ActiveProfile) {
        write(activeFile, json.encodeToString(active))
    }

    private fun write(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }
}
