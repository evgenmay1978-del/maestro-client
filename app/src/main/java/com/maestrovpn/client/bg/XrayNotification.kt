package com.maestrovpn.client.bg

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.maestrovpn.client.MainActivity
import com.maestrovpn.client.R
import kotlin.math.roundToInt

/**
 * Уведомление foreground-службы туннеля. Только текст: состояние и трафик
 * сессии из Stats API. Скорость в реальном времени не считаем — счётчики
 * Xray это накопительные итоги, а не мгновенная скорость.
 */
object XrayNotification {
    const val CHANNEL_ID = "maestro_client_tunnel"
    const val NOTIFICATION_ID = 1

    const val ACTION_STOP = "com.maestrovpn.client.bg.STOP_TUNNEL"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Maestro Client",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Статус VPN-подключения" },
        )
    }

    fun build(
        context: Context,
        serviceClass: Class<*>,
        title: String,
        text: String,
    ): Notification {
        createChannel(context)
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            context,
            0,
            Intent(context, serviceClass).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_fox)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .addAction(0, "Отключить", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun starting(context: Context, serviceClass: Class<*>): Notification =
        build(context, serviceClass, "Maestro Client", "Подключение…")

    fun running(
        context: Context,
        serviceClass: Class<*>,
        upBytes: Long,
        downBytes: Long,
    ): Notification = build(
        context,
        serviceClass,
        "Maestro Client — подключено",
        "↑ ${formatBytes(upBytes)} · ↓ ${formatBytes(downBytes)}",
    )

    fun formatBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes Б"
        val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
        var value = bytes.toDouble()
        var unit = 0
        while (value >= 1024 && unit < units.size - 1) {
            value /= 1024
            unit++
        }
        val rounded = (value * 10).roundToInt() / 10.0
        return "$rounded ${units[unit]}"
    }
}
