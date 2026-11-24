package com.maestrovpn.client.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

// Монета — точный перенос MonogramMedallion.kt из MaestroVPN (HEAD 3dc6878),
// без перерисовки: тот же кадровый цикл withFrameNanos, режимы IDLE / SPIN_DOWN /
// SPIN, IDLE 10 с с паузами в анфас ±56°, SPIN 3,6 с/круг, SPIN_DOWN — трение
// exp(−t/400 мс) + доводка smoothstep + покачивание ±3° (~2,2–2,7 с), цилиндр
// в одном Canvas, экструзия буквы M из кадра mobile_medallion_m_idle.webp,
// сокет виден только при вращении, точные градиенты ребра/лица из кода.
// Этот файл — только тонкая обёртка с той же сигнатурой, что была у заглушки.
@Composable
fun Coin(
    size: Dp,
    connected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    connecting: Boolean = false,
) {
    MonogramMedallion(
        connected = connected,
        connecting = connecting,
        modifier = modifier
            .size(size)
            .clickable(onClick = onClick),
    )
}
