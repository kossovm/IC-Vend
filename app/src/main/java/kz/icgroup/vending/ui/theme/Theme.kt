package kz.icgroup.vending.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val IcColors = lightColorScheme(
    primary = IcGreen,
    onPrimary = Color.White,
    secondary = IcBlue,
    onSecondary = Color.White,
    error = IcRed,
    onError = Color.White,
    background = IcBackground,
    onBackground = IcText,
    surface = IcSurface,
    onSurface = IcText,
    outline = IcLine,
)

/** Всегда светлая тема в цветах IC Group: автомат стоит в помещении, тёмный режим не нужен. */
@Composable
fun IcVendingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = IcColors,
        typography = Typography,
        content = content,
    )
}
