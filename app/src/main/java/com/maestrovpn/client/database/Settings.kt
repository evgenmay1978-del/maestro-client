package com.maestrovpn.client.database

import androidx.room.Room
import com.maestrovpn.client.App
import com.maestrovpn.client.constant.Path
import com.maestrovpn.client.constant.SettingsKey
import com.maestrovpn.client.database.preference.KeyValueDatabase
import com.maestrovpn.client.database.preference.RoomPreferenceDataStore
import com.maestrovpn.client.ktx.boolean
import com.maestrovpn.client.ktx.int
import com.maestrovpn.client.ktx.stringSet
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

/**
 * Настройки (срез 2, почищены в срезе 7, issue #8): только то, что нужно
 * Xray-ядру и службам — автозапуск, per-app proxy, системный прокси,
 * bypass. Выбранный профиль, режим службы и динамическое уведомление
 * ушли вместе с sing-box/Profile DB: режим службы теперь решает
 * XrayEngine по наличию tun inbound в конфиге, а конфиг лежит в файле.
 *
 * Выпилено ранее: автообновление приложения, порядок элементов дашборда,
 * Tailscale SSH, кэш обновлений, счётчики неудачных установок, aivpnKey,
 * privilegeSettings, oomKiller — Maestro-специфика; в standalone-клиенте
 * появится только по мере переноса соответствующих экранов.
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
    var startedByUser by dataStore.boolean(SettingsKey.STARTED_BY_USER)
    // One-shot: have we already asked the system to add our quick-settings tile? (Android 13+)
    var qsTilePrompted by dataStore.boolean(SettingsKey.QS_TILE_PROMPTED)

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
}
