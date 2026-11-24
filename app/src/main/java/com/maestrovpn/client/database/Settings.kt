package com.maestrovpn.client.database

import androidx.room.Room
import com.maestrovpn.client.App
import com.maestrovpn.client.constant.Path
import com.maestrovpn.client.constant.ServiceMode
import com.maestrovpn.client.constant.SettingsKey
import com.maestrovpn.client.database.preference.KeyValueDatabase
import com.maestrovpn.client.database.preference.RoomPreferenceDataStore
import com.maestrovpn.client.ktx.boolean
import com.maestrovpn.client.ktx.int
import com.maestrovpn.client.ktx.long
import com.maestrovpn.client.ktx.string
import com.maestrovpn.client.ktx.stringSet
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File

/**
 * Настройки (срез 2): урезанная копия Settings из Maestro — только то, что нужно
 * ядру BoxService (выбранный профиль, режим службы, автозапуск, per-app proxy,
 * системный прокси, динамическое уведомление).
 *
 * Выпилено: автообновление приложения (updateSource/checkUpdate/silentInstall/
 * fdroid), порядок элементов дашборда, Tailscale SSH, кэш обновлений, счётчики
 * неудачных установок, aivpnKey, privilegeSettings, oomKiller — всё это
 * Maestro-специфика; в standalone-клиенте появится только по мере переноса
 * соответствующих экранов (таблица соответствия).
 */
object Settings {
    @OptIn(DelicateCoroutinesApi::class)
    private val instance by lazy {
        App.application.getDatabasePath(Path.SETTINGS_DATABASE_PATH).parentFile?.mkdirs()
        Room.databaseBuilder(
            App.application,
            KeyValueDatabase::class.java,
            Path.SETTINGS_DATABASE_PATH,
        ).allowMainThreadQueries()
            .fallbackToDestructiveMigration()
            .enableMultiInstanceInvalidation()
            .setQueryExecutor { GlobalScope.launch { it.run() } }
            .build()
    }
    val dataStore = RoomPreferenceDataStore(instance.keyValuePairDao())
    var selectedProfile by dataStore.long(SettingsKey.SELECTED_PROFILE) { -1L }
    var serviceMode by dataStore.string(SettingsKey.SERVICE_MODE) { ServiceMode.NORMAL }
    var startedByUser by dataStore.boolean(SettingsKey.STARTED_BY_USER)
    // One-shot: have we already asked the system to add our quick-settings tile? (Android 13+)
    var qsTilePrompted by dataStore.boolean(SettingsKey.QS_TILE_PROMPTED)

    // Динамическое уведомление со скоростью (ServiceNotification.start): по умолчанию
    // ВЫКЛЮЧЕНО — в отличие от Maestro, где default зависит от памяти устройства.
    // Второй независимый 1-секундный фид статуса libbox — чистый шум на слабых ТВ-боксах.
    var dynamicNotification by dataStore.boolean(SettingsKey.DYNAMIC_NOTIFICATION) { false }

    const val PER_APP_PROXY_DISABLED = 0
    const val PER_APP_PROXY_EXCLUDE = 1
    const val PER_APP_PROXY_INCLUDE = 2

    var autoRedirect by dataStore.boolean(SettingsKey.AUTO_REDIRECT) { false }
    var perAppProxyEnabled by dataStore.boolean(SettingsKey.PER_APP_PROXY_ENABLED) { false }
    // INCLUDE = the user picks which apps GO THROUGH the VPN (the "choose apps for
    // VPN" model the owner asked for); an empty list means all apps use the VPN.
    var perAppProxyMode by dataStore.int(SettingsKey.PER_APP_PROXY_MODE) { PER_APP_PROXY_INCLUDE }
    var perAppProxyList by dataStore.stringSet(SettingsKey.PER_APP_PROXY_LIST) { emptySet() }
    var perAppProxyManagedMode by dataStore.boolean(SettingsKey.PER_APP_PROXY_MANAGED_MODE) { false }
    var perAppProxyManagedList by dataStore.stringSet(SettingsKey.PER_APP_PROXY_MANAGED_LIST) { emptySet() }

    fun getEffectivePerAppProxyMode(): Int = if (perAppProxyManagedMode) {
        PER_APP_PROXY_EXCLUDE
    } else {
        perAppProxyMode
    }

    fun getEffectivePerAppProxyList(): Set<String> = if (perAppProxyManagedMode) {
        perAppProxyManagedList
    } else {
        perAppProxyList
    }

    var allowBypass by dataStore.boolean(SettingsKey.ALLOW_BYPASS) { false }
    var systemProxyEnabled by dataStore.boolean(SettingsKey.SYSTEM_PROXY_ENABLED) { true }

    /**
     * Класс службы по режиму. VPNService/ProxyService живут в src/libbox/java,
     * поэтому прямая ссылка из main-sourceSet невозможна (сборка без AAR должна
     * компилироваться) — класс ищется по имени.
     */
    fun serviceClass(): Class<*> = when (serviceMode) {
        ServiceMode.VPN -> Class.forName("com.maestrovpn.client.bg.VPNService")
        else -> Class.forName("com.maestrovpn.client.bg.ProxyService")
    }

    suspend fun rebuildServiceMode(): Boolean {
        var newMode = ServiceMode.NORMAL
        try {
            if (needVPNService()) {
                newMode = ServiceMode.VPN
            }
        } catch (_: Exception) {
        }
        if (serviceMode == newMode) {
            return false
        }
        serviceMode = newMode
        return true
    }

    private suspend fun needVPNService(): Boolean {
        val selectedProfileId = selectedProfile
        if (selectedProfileId == -1L) return false
        val profile = ProfileManager.get(selectedProfile) ?: return false
        val content = JSONObject(File(profile.typed.path).readText())
        val inbounds = content.getJSONArray("inbounds")
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.getJSONObject(index)
            if (inbound.getString("type") == "tun") {
                return true
            }
        }
        return false
    }
}
