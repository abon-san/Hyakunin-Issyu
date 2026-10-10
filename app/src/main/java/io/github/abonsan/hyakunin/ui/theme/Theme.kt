package io.github.abonsan.hyakunin.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// 和紙の明るい配色だけを使う（ダイナミックカラーやダークテーマには切り替えない）。
private val WafuColorScheme = lightColorScheme(
    primary = Shu,
    onPrimary = Gofun,
    primaryContainer = ShuPale,
    onPrimaryContainer = ShuInk,
    secondary = Kon,
    onSecondary = Gofun,
    secondaryContainer = KonPale,
    onSecondaryContainer = Kon,
    tertiary = Kincha,
    onTertiary = Sumi,
    tertiaryContainer = KinchaPale,
    onTertiaryContainer = KinchaInk,
    background = Washi,
    onBackground = Sumi,
    surface = Washi,
    onSurface = Sumi,
    surfaceVariant = WashiDeep,
    onSurfaceVariant = UsuzumiText,
    surfaceTint = Color.Transparent,
    surfaceBright = WashiLight,
    surfaceDim = WashiDeep,
    surfaceContainerLowest = Gofun,
    surfaceContainerLow = WashiLight,
    surfaceContainer = WashiLight,
    surfaceContainerHigh = WashiLight,
    surfaceContainerHighest = WashiDeep,
    inverseSurface = Sumi,
    inverseOnSurface = Washi,
    inversePrimary = ShuLight,
    outline = Kincha,
    outlineVariant = KinchaLine,
    error = OverReadRed,
    onError = Gofun,
    errorContainer = ShuPale,
    onErrorContainer = ShuInk,
    scrim = Color.Black,
)

private val WafuShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(4.dp),
    medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun HyakuninTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WafuColorScheme,
        typography = WafuTypography,
        shapes = WafuShapes,
        content = content,
    )
}
