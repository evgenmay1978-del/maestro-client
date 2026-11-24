package com.maestrovpn.client.bg

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.maestrovpn.client.core.engine.EngineManager
import com.maestrovpn.client.database.ProfileManager
import com.maestrovpn.client.database.Settings
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// Автозапуск после загрузки/обновления (срез 2): только если пользователь явно
// включал VPN (Settings.startedByUser — флаг ставит BoxService). Конфиг берётся
// из выбранного профиля в БД: currentConfigJson живёт только в памяти процесса
// и после перезагрузки пуст. CrashReportManager у Maestro здесь нет —
// crash-loop защита не переносится (нет ни репортов, ни их просмотра).
class BootReceiver : BroadcastReceiver() {
    @OptIn(DelicateCoroutinesApi::class)
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> {
            }

            else -> return
        }
        GlobalScope.launch(Dispatchers.IO) {
            if (!Settings.startedByUser) return@launch
            val profile = ProfileManager.get(Settings.selectedProfile) ?: return@launch
            val content = runCatching { File(profile.typed.path).readText() }
                .getOrNull()?.takeIf { it.isNotBlank() } ?: return@launch
            EngineManager.setConfig(content)
            withContext(Dispatchers.Main) {
                EngineManager.startService()
            }
        }
    }
}
