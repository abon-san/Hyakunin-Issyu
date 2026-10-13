package io.github.abonsan.hyakunin.ui.match

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import io.github.abonsan.hyakunin.game.LeadInKind
import io.github.abonsan.hyakunin.game.LeadInState
import io.github.abonsan.hyakunin.game.LeadInVerse
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.game.kanaCount
import io.github.abonsan.hyakunin.ui.theme.Usuzumi

/** 間合いの間の、読み終えた句（控えめに見せる）。 */
private val Subdued = SpanStyle(color = Usuzumi)

/** 前置きの見出し（「序歌」「前の歌の下の句（61番）」）。 */
internal fun leadInTitle(leadIn: LeadInState): String = when (leadIn.kind) {
    LeadInKind.JOKA -> "序歌"
    LeadInKind.PREVIOUS_SHIMO -> "前の歌の下の句（${leadIn.poem.no}番）"
}

/**
 * 前置きで見せる仮名の数（読みの仮名で、上の句の先頭から数える）。
 * 上の句を読んでいる間はその仮名まで、下の句では上の句をすべてと下の句のその仮名まで（同じ下の句の2回目は、
 * 下の句を初めから見せ直す）。間合いに入ったらすべて。
 */
internal fun leadInRevealedKana(leadIn: LeadInState): Int {
    val kami = kanaCount(leadIn.poem.kamiYomi)
    return when {
        leadIn.inMa -> kami + kanaCount(leadIn.poem.shimoYomi)
        leadIn.verse == LeadInVerse.KAMI -> leadIn.revealedKana
        else -> kami + leadIn.revealedKana
    }
}

/**
 * 競技かるた式の前置き（序歌・前の歌の下の句）の表示。見出しの下に、読み上げ中と同じ句の部品（[VersePanel]）で
 * 読んだところまでを見せる。前の歌の下の句は下の句だけを書く。間合い（[LeadInState.inMa]）の間は、句をすべて
 * 控えめな色で見せる。新しく読んだ文字は、読み上げ中と同じく透明からその色まで浮かび上がらせる。
 * 前置きの間は札を取れず、「読み始める」やカウントダウンも出さない。
 */
@Composable
internal fun LeadInPanel(leadIn: LeadInState, verseStyle: VerseStyle, modifier: Modifier = Modifier) {
    val full = rememberVerse(leadIn.poem, verseStyle)
    val shimoOnly = leadIn.kind == LeadInKind.PREVIOUS_SHIMO
    val verse = remember(full, shimoOnly) { if (shimoOnly) full.shimoOnly() else full }
    val shown = full.displayCount(leadInRevealedKana(leadIn))
    val first = if (shimoOnly) full.kamiLength else 0
    val tone = if (leadIn.inMa) Subdued else Revealed
    VersePanel(
        title = leadInTitle(leadIn),
        verse = verse,
        spanFor = { index -> if (index < shown) tone else Hidden },
        fade = rememberKanaFadeIn(verse, shown),
        description = full.text.substring(first, shown.coerceAtLeast(first)),
        notice = null,
        modifier = modifier,
        titleRequired = true,
    )
}
