package com.maestrovpn.client.core.settings

import com.maestrovpn.client.constant.SettingsKey
import com.maestrovpn.client.database.Settings

/**
 * Хранилище продвинутых настроек (issue #3) поверх того же
 * Room-KeyValue, что и остальные настройки приложения.
 *
 * Геттеры/сеттеры явные, а не делегаты `by dataStore.int(...)`: делегат
 * вычисляется при инициализации object и дёргал бы Room в момент class-init.
 * Кривое/пустое значение из БД всегда схлопывается к дефолту AdvancedConfig.
 */
object AdvancedSettings {
    private val ds get() = Settings.dataStore

    var remoteDnsPrimary: String
        get() = ds.getString(SettingsKey.ADVANCED_REMOTE_DNS_PRIMARY)
            ?.takeIf { AdvancedConfig.isValidDnsServer(it) }
            ?: AdvancedConfig.DEFAULT_DNS_PRIMARY
        set(value) = ds.putString(
            SettingsKey.ADVANCED_REMOTE_DNS_PRIMARY,
            value.trim().takeIf { AdvancedConfig.isValidDnsServer(it) },
        )

    var remoteDnsSecondary: String
        get() = ds.getString(SettingsKey.ADVANCED_REMOTE_DNS_SECONDARY)
            ?.takeIf { AdvancedConfig.isValidDnsServer(it) }
            ?: AdvancedConfig.DEFAULT_DNS_SECONDARY
        set(value) = ds.putString(
            SettingsKey.ADVANCED_REMOTE_DNS_SECONDARY,
            value.trim().takeIf { AdvancedConfig.isValidDnsServer(it) },
        )

    var queryStrategy: String
        get() = AdvancedConfig.normalizeQueryStrategy(
            ds.getString(SettingsKey.ADVANCED_QUERY_STRATEGY).orEmpty(),
        )
        set(value) = ds.putString(
            SettingsKey.ADVANCED_QUERY_STRATEGY,
            AdvancedConfig.normalizeQueryStrategy(value),
        )

    var tunMtu: Int
        get() = ds.getInt(SettingsKey.ADVANCED_TUN_MTU, AdvancedConfig.DEFAULT_TUN_MTU)
            .takeIf { it in AdvancedConfig.MTU_MIN..AdvancedConfig.MTU_MAX }
            ?: AdvancedConfig.DEFAULT_TUN_MTU
        set(value) = ds.putInt(
            SettingsKey.ADVANCED_TUN_MTU,
            value.takeIf { it in AdvancedConfig.MTU_MIN..AdvancedConfig.MTU_MAX }
                ?: AdvancedConfig.DEFAULT_TUN_MTU,
        )

    var socksPort: Int
        get() = ds.getInt(SettingsKey.ADVANCED_SOCKS_PORT, AdvancedConfig.DEFAULT_SOCKS_PORT)
            .takeIf { it in AdvancedConfig.PORT_MIN..AdvancedConfig.PORT_MAX }
            ?: AdvancedConfig.DEFAULT_SOCKS_PORT
        set(value) = ds.putInt(
            SettingsKey.ADVANCED_SOCKS_PORT,
            value.takeIf { it in AdvancedConfig.PORT_MIN..AdvancedConfig.PORT_MAX }
                ?: AdvancedConfig.DEFAULT_SOCKS_PORT,
        )

    var httpPort: Int
        get() = ds.getInt(SettingsKey.ADVANCED_HTTP_PORT, AdvancedConfig.DEFAULT_HTTP_PORT)
            .takeIf { it in AdvancedConfig.PORT_MIN..AdvancedConfig.PORT_MAX }
            ?: AdvancedConfig.DEFAULT_HTTP_PORT
        set(value) = ds.putInt(
            SettingsKey.ADVANCED_HTTP_PORT,
            value.takeIf { it in AdvancedConfig.PORT_MIN..AdvancedConfig.PORT_MAX }
                ?: AdvancedConfig.DEFAULT_HTTP_PORT,
        )

    var muxEnabled: Boolean
        get() = ds.getBoolean(SettingsKey.ADVANCED_MUX_ENABLED, false)
        set(value) = ds.putBoolean(SettingsKey.ADVANCED_MUX_ENABLED, value)

    var muxConcurrency: Int
        get() = ds.getInt(SettingsKey.ADVANCED_MUX_CONCURRENCY, AdvancedConfig.DEFAULT_MUX_CONCURRENCY)
            .takeIf { it in AdvancedConfig.MUX_MIN..AdvancedConfig.MUX_MAX }
            ?: AdvancedConfig.DEFAULT_MUX_CONCURRENCY
        set(value) = ds.putInt(
            SettingsKey.ADVANCED_MUX_CONCURRENCY,
            value.takeIf { it in AdvancedConfig.MUX_MIN..AdvancedConfig.MUX_MAX }
                ?: AdvancedConfig.DEFAULT_MUX_CONCURRENCY,
        )

    var muxXudpConcurrency: Int
        get() = ds.getInt(
            SettingsKey.ADVANCED_MUX_XUDP_CONCURRENCY,
            AdvancedConfig.DEFAULT_MUX_XUDP_CONCURRENCY,
        ).takeIf { it in AdvancedConfig.MUX_MIN..AdvancedConfig.MUX_MAX }
            ?: AdvancedConfig.DEFAULT_MUX_XUDP_CONCURRENCY
        set(value) = ds.putInt(
            SettingsKey.ADVANCED_MUX_XUDP_CONCURRENCY,
            value.takeIf { it in AdvancedConfig.MUX_MIN..AdvancedConfig.MUX_MAX }
                ?: AdvancedConfig.DEFAULT_MUX_XUDP_CONCURRENCY,
        )

    var muxXudpBehavior: String
        get() = AdvancedConfig.normalizeMuxBehavior(
            ds.getString(SettingsKey.ADVANCED_MUX_XUDP_BEHAVIOR).orEmpty(),
        )
        set(value) = ds.putString(
            SettingsKey.ADVANCED_MUX_XUDP_BEHAVIOR,
            AdvancedConfig.normalizeMuxBehavior(value),
        )

    /** Снимок для сборки конфига: одно чтение, согласованный набор значений. */
    fun snapshot(): AdvancedConfig = AdvancedConfig(
        remoteDnsPrimary = remoteDnsPrimary,
        remoteDnsSecondary = remoteDnsSecondary,
        queryStrategy = queryStrategy,
        tunMtu = tunMtu,
        socksPort = socksPort,
        httpPort = httpPort,
        muxEnabled = muxEnabled,
        muxConcurrency = muxConcurrency,
        muxXudpConcurrency = muxXudpConcurrency,
        muxXudpBehavior = muxXudpBehavior,
    )

    fun reset() {
        Keys.all.forEach { ds.remove(it) }
    }

    private object Keys {
        val all = listOf(
            SettingsKey.ADVANCED_REMOTE_DNS_PRIMARY,
            SettingsKey.ADVANCED_REMOTE_DNS_SECONDARY,
            SettingsKey.ADVANCED_QUERY_STRATEGY,
            SettingsKey.ADVANCED_TUN_MTU,
            SettingsKey.ADVANCED_SOCKS_PORT,
            SettingsKey.ADVANCED_HTTP_PORT,
            SettingsKey.ADVANCED_MUX_ENABLED,
            SettingsKey.ADVANCED_MUX_CONCURRENCY,
            SettingsKey.ADVANCED_MUX_XUDP_CONCURRENCY,
            SettingsKey.ADVANCED_MUX_XUDP_BEHAVIOR,
        )
    }
}
