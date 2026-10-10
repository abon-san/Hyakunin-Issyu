package io.github.abonsan.hyakunin.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.LineBreak
import io.github.abonsan.hyakunin.R

/** しっぽり明朝（アプリで使う文字だけのサブセット）。 */
val ShipporiMincho = FontFamily(
    Font(R.font.shippori_mincho_regular, FontWeight.Normal),
    Font(R.font.shippori_mincho_bold, FontWeight.Bold),
)

/** 文はすべて日本語。 */
val JapaneseLocale = LocaleList("ja-JP")

/** 文節の途中では改行しない（Android 13 以上で効く。それより前は通常の改行）。 */
val JapaneseLineBreak = LineBreak(
    strategy = LineBreak.Strategy.HighQuality,
    strictness = LineBreak.Strictness.Strict,
    wordBreak = LineBreak.WordBreak.Phrase,
)

private val Base = Typography()

private fun TextStyle.mincho(weight: FontWeight? = fontWeight) = copy(
    fontFamily = ShipporiMincho,
    fontWeight = weight,
    localeList = JapaneseLocale,
    lineBreak = JapaneseLineBreak,
)

/** すべての文字を明朝体にした Typography。見出しとボタンは太字。 */
val WafuTypography = Typography(
    displayLarge = Base.displayLarge.mincho(),
    displayMedium = Base.displayMedium.mincho(),
    displaySmall = Base.displaySmall.mincho(),
    headlineLarge = Base.headlineLarge.mincho(),
    headlineMedium = Base.headlineMedium.mincho(),
    headlineSmall = Base.headlineSmall.mincho(),
    titleLarge = Base.titleLarge.mincho(FontWeight.Bold),
    titleMedium = Base.titleMedium.mincho(FontWeight.Bold),
    titleSmall = Base.titleSmall.mincho(FontWeight.Bold),
    bodyLarge = Base.bodyLarge.mincho(),
    bodyMedium = Base.bodyMedium.mincho(),
    bodySmall = Base.bodySmall.mincho(),
    labelLarge = Base.labelLarge.mincho(FontWeight.Bold),
    labelMedium = Base.labelMedium.mincho(FontWeight.Normal),
    labelSmall = Base.labelSmall.mincho(FontWeight.Normal),
)
