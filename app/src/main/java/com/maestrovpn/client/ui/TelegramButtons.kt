package com.maestrovpn.client.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maestrovpn.client.R

// Единственные внешние ссылки в приложении (решение Евгения 24.11.2026):
// бот @MaestroSecureVPN_bot и личный Telegram @wapmixx.
// Никаких сторонних сервисов здесь нет и быть не должно.
//
// Срез 3: высота и размер шрифта — как у кнопок сетки главного экрана
// (56dp, одна строка без переносов), чтобы ряд не выбивался из сетки.
const val TELEGRAM_BOT_URL = "https://t.me/MaestroSecureVPN_bot"
const val TELEGRAM_PERSONAL_URL = "https://t.me/wapmixx"

@Composable
fun TelegramButtons(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedButton(
            modifier = Modifier.weight(1f).height(56.dp),
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_BOT_URL)))
            },
        ) {
            Text(stringResource(R.string.telegram_bot), maxLines = 1, softWrap = false, fontSize = 14.sp)
        }
        OutlinedButton(
            modifier = Modifier.weight(1f).height(56.dp),
            onClick = {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(TELEGRAM_PERSONAL_URL)))
            },
        ) {
            Text(stringResource(R.string.telegram_personal), maxLines = 1, softWrap = false, fontSize = 14.sp)
        }
    }
}
