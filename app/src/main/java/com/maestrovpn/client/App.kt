package com.maestrovpn.client

import android.app.Application
import android.app.NotificationManager
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import androidx.core.content.getSystemService
import com.maestrovpn.client.core.engine.EngineManager

// Точка входа приложения (срез 2): системные сервисы для ядра + инициализация движка.
// Движок — XrayEngine (срез 7, issue #8); sing-box/libbox удалён полностью.
// Companion — те же ленивые сервисы, что у Maestro Application.
class App : Application() {
    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)
        application = this
    }

    override fun onCreate() {
        super.onCreate()
        EngineManager.init(this)
    }

    companion object {
        lateinit var application: App
        val notification by lazy { application.getSystemService<NotificationManager>()!! }
        val notificationManager by lazy { application.getSystemService<NotificationManager>()!! }
        val connectivity by lazy { application.getSystemService<ConnectivityManager>()!! }
        val packageManager by lazy { application.packageManager }
        val powerManager by lazy { application.getSystemService<PowerManager>()!! }
        val wifiManager by lazy { application.getSystemService<WifiManager>()!! }
        val clipboard by lazy { application.getSystemService<ClipboardManager>()!! }
    }
}
