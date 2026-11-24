package com.maestrovpn.client.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Палитра — из MaestroVPN (compose/theme/Theme.kt, HEAD 3dc6878):
// тёмная премиум-тема: дерево/золото/изумруд. Только тёмная тема (решение среза 1).
val MaestroGold = Color(0xFFE8C877)
val MaestroOnGold = Color(0xFF241506)
val MaestroGoldContainer = Color(0xFF4A3218)
val MaestroOnGoldContainer = Color(0xFFFFE6B0)
val MaestroEmerald = Color(0xFF73D98A)
val MaestroEmeraldContainer = Color(0xFF17351E)
// Зелёный медальона — точное значение из Maestro (compose/premium/MobilePremiumTokens.kt,
// HEAD 3dc6878): используется MonogramMedallion.kt для свечения и колец при подключении.
val DarkGreen = Color(0xFF2FE0A4)
val MaestroBackground = Color(0xFF090603)
val MaestroOnBackground = Color(0xFFF1EEE6)
val MaestroSurface = Color(0xFF171008)
val MaestroSurfaceVariant = Color(0xFF2B1D10)
val MaestroOnSurfaceVariant = Color(0xFFD5C5A8)
val MaestroSurfaceContainer = Color(0xFF21160C)
val MaestroSurfaceContainerHigh = Color(0xFF2B1D10)
val MaestroSurfaceContainerHighest = Color(0xFF352513)
val MaestroOutline = Color(0xFFA87C3A)
val MaestroOutlineVariant = Color(0xFF5C3F20)

private val MaestroColorScheme: ColorScheme = darkColorScheme(
    primary = MaestroGold,
    onPrimary = MaestroOnGold,
    primaryContainer = MaestroGoldContainer,
    onPrimaryContainer = MaestroOnGoldContainer,
    secondary = MaestroEmerald,
    onSecondary = Color(0xFF071B0C),
    secondaryContainer = MaestroEmeraldContainer,
    onSecondaryContainer = Color(0xFFB9F3C5),
    background = MaestroBackground,
    onBackground = MaestroOnBackground,
    surface = MaestroSurface,
    onSurface = MaestroOnBackground,
    surfaceVariant = MaestroSurfaceVariant,
    onSurfaceVariant = MaestroOnSurfaceVariant,
    surfaceContainerLowest = Color(0xFF0D0905),
    surfaceContainerLow = MaestroSurface,
    surfaceContainer = MaestroSurfaceContainer,
    surfaceContainerHigh = MaestroSurfaceContainerHigh,
    surfaceContainerHighest = MaestroSurfaceContainerHighest,
    outline = MaestroOutline,
    outlineVariant = MaestroOutlineVariant,
)

@Composable
fun MaestroClientTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaestroColorScheme,
        typography = Typography(),
        content = content,
    )
}
