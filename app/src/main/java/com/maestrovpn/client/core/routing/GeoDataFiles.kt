package com.maestrovpn.client.core.routing

import android.content.Context
import android.system.Os
import java.io.File

/**
 * Геоданные (§8) для Xray-core: geoip.dat и geosite.dat из assets.
 *
 * Прежняя версия писала файлы рядом с бинарём ядра, то есть в nativeLibraryDir.
 * Этот каталог Android делает только для чтения: ошибка записи глоталась, и
 * ядро падало, если профиль содержал правила geosite: или geoip:.
 *
 * Теперь файлы лежат в filesDir/xray-assets, а путь передаётся ядру через
 * переменную окружения xray.location.asset (ключ есть в бинаре ядра).
 */
object GeoDataFiles {

    /** Переменная, которую читает Xray-core для каталога ресурсов. */
    const val ASSET_LOCATION_ENV = "xray.location.asset"

    private const val DIR_NAME = "xray-assets"
    private val NAMES = listOf("geoip.dat", "geosite.dat")

    /** Каталог с геоданными; доступен приложению для записи. */
    fun assetDir(context: Context): File = File(context.filesDir, DIR_NAME)

    /**
     * Копирует геоданные из assets. Идемпотентно: файл нужного размера не трогаем.
     * При сбое бросает исключение с именем файла, чтобы вызывающий показал причину.
     */
    fun install(context: Context): File {
        val dir = assetDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IllegalStateException("не создать каталог геоданных: ${dir.absolutePath}")
        }
        for (name in NAMES) {
            val expected = assetLength(context, name)
            val target = File(dir, name)
            if (target.isFile && target.length() == expected) continue
            context.assets.open(name).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            if (target.length() != expected) {
                throw IllegalStateException(
                    "$name скопирован не полностью: ${target.length()} из $expected байт",
                )
            }
        }
        return dir
    }

    /**
     * Экспорт пути в окружение процесса. Дочерний процесс, созданный fork'ом
     * (XrayNative.nativeSpawn), наследует его. Вызывается один раз при старте приложения.
     */
    fun exportEnvironment(context: Context) {
        runCatching {
            Os.setenv(ASSET_LOCATION_ENV, assetDir(context).absolutePath, true)
        }
    }

    /** Нужны ли геоданные: есть ли в конфиге правила geosite: или geoip:. */
    fun configNeedsGeo(configJson: String): Boolean =
        configJson.contains("geosite:") || configJson.contains("geoip:")

    private fun assetLength(context: Context, name: String): Long {
        val fd = context.assets.openFd(name)
        return try {
            fd.length
        } finally {
            fd.close()
        }
    }
}
