package com.maestrovpn.client.core.engine

import android.app.Application
import android.util.Log
import com.maestrovpn.client.App
import com.maestrovpn.client.bg.BoxService
import com.maestrovpn.client.bg.UpdateProfileWork
import com.maestrovpn.client.constant.Bugs
import com.maestrovpn.client.database.Profile
import com.maestrovpn.client.database.ProfileManager
import com.maestrovpn.client.database.Settings
import com.maestrovpn.client.database.TypedProfile
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import com.maestrovpn.client.whitelist.SubXhttpRuntime
import com.maestrovpn.client.whitelist.SubXhttpSession
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Реальный движок sing-box (срез 2): Libbox.setup + BoxService вместо StubEngine.
 *
 * Загрузка — через reflection из EngineManager (см. VpnEngine): основной код
 * компилируется без libbox.aar, а этот файл — только когда AAR есть
 * (src/libbox/java в сборке).
 *
 * Старт: выбранный конфиг пишется в профиль (filesDir/configs/<fileID>.json,
 * конвенция Maestro — ClaimViewModel/NewProfileViewModel), профиль выбирается,
 * режим службы пересчитывается (tun inbound → VPNService, иначе ProxyService),
 * дальше — BoxService.start(). Полный openTun — внутри Maestro VPNService
 * (bg/VPNService.kt): MTU clamp 1280, маршруты, per-app списки, HTTP-прокси.
 */
@OptIn(DelicateCoroutinesApi::class)
class SingBoxEngine : VpnEngine {
    companion object {
        private const val TAG = "SingBoxEngine"
    }

    override val version: String
        get() = runCatching { "sing-box " + Libbox.version() }.getOrDefault("sing-box")

    override var lastError: String? = null
        private set

    @Volatile
    private var libboxReady = false

    override fun init(app: Application) {
        if (libboxReady) return
        runCatching { Libbox.setLocale(Locale.getDefault().toLanguageTag().replace("-", "_")) }
            .onFailure { Log.d(TAG, "set locale: ${it.message}") }
        val baseDir = app.filesDir.apply { mkdirs() }
        val workingDir = app.getExternalFilesDir(null) ?: baseDir
        workingDir.mkdirs()
        val tempDir = app.cacheDir.apply { mkdirs() }
        Libbox.setup(SetupOptions().also {
            it.basePath = baseDir.path
            it.workingPath = workingDir.path
            it.tempPath = tempDir.path
            it.fixAndroidStack = Bugs.fixAndroidStack
            it.logMaxLines = 100   // smaller Go log ring buffer — pure resident-RAM trim (weak 1GB TVs)
            it.debug = false       // never run libbox in debug (extra formatting/overhead) even in a debug build
            it.crashReportSource = "SingBoxEngine"
            // OOM-киллер Maestro в standalone-клиенте выключен: его настройки
            // (Settings.oomKiller*) не переносятся в срезе 2.
            it.oomKillerEnabled = false
            it.oomKillerDisabled = true
            it.oomMemoryLimit = 50L * 1024L * 1024L
        })
        libboxReady = true
        // Периодический автоапдейт подписок — как у Maestro Application.onCreate.
        GlobalScope.launch(Dispatchers.IO) { UpdateProfileWork.reconfigureUpdater() }
    }

    override fun validateConfig(configJson: String): Boolean =
        runCatching { Libbox.checkConfig(configJson) }.isSuccess

    override fun start(configJson: String): Boolean {
        if (!libboxReady) {
            lastError = "Движок sing-box не инициализирован"
            return false
        }
        return try {
            runBlocking(Dispatchers.IO) {
                ensureProfile(configJson)
                Settings.rebuildServiceMode()
            }
            BoxService.start()
            true
        } catch (e: Exception) {
            lastError = e.message
            Log.e(TAG, "start failed", e)
            false
        }
    }

    override fun stop() {
        BoxService.stop()
    }

    /**
     * Подготовка сервера (срез 3): засеять порты xhttp-узлов из конфига
     * (patchDirectRules) и поднять/остановить раннер под выбранный тег.
     * Для нативного узла — остановить раннер (ветка Regular). При рестарте
     * приложения то же делает SubXhttpSession.restore() в applySelectedContent.
     */
    override suspend fun prepareServer(tag: String, configJson: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                SubXhttpRuntime.patchDirectRules(configJson)
                SubXhttpSession.onSelected(null, tag)
            }.getOrDefault(false)
        }

    /** Манифест раннера переписан — сбросить кэш узлов в памяти. */
    override fun onSubscriptionChanged() {
        SubXhttpRuntime.invalidate()
    }

    /**
     * Конфиг — в файл выбранного профиля (перезаписывается на месте), либо в новый
     * Local-профиль «Maestro Client», если выбранного ещё нет. Писать сюда же будет
     * и автоапдейт подписок (UpdateProfileWork) — один источник правды для BoxService.
     */
    private suspend fun ensureProfile(configJson: String): Profile {
        val selectedId = Settings.selectedProfile
        val existing = if (selectedId != -1L) ProfileManager.get(selectedId) else null
        if (existing != null) {
            File(existing.typed.path).writeText(configJson)
            return existing
        }
        val typed = TypedProfile().apply {
            type = TypedProfile.Type.Local
        }
        val profile = Profile(name = "Maestro Client", typed = typed).apply {
            userOrder = ProfileManager.nextOrder()
        }
        val fileID = ProfileManager.nextFileID()
        val dir = File(App.application.filesDir, "configs").also { it.mkdirs() }
        val file = File(dir, "$fileID.json")
        typed.path = file.path
        file.writeText(configJson)
        return ProfileManager.create(profile, andSelect = true)
    }
}
