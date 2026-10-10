package io.github.abonsan.hyakunin.ui.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.em
import io.github.abonsan.hyakunin.data.Poem
import io.github.abonsan.hyakunin.ui.theme.KimarijiGreen
import io.github.abonsan.hyakunin.ui.theme.OverReadRed
import io.github.abonsan.hyakunin.ui.theme.ShipporiMincho
import io.github.abonsan.hyakunin.ui.theme.Sumi
import io.github.abonsan.hyakunin.ui.theme.Usuzumi

/** 読みの1句。[firstKana] は句の先頭の仮名の通し番号（空白を除き、上の句の先頭を0として数える）。 */
internal data class VerseLine(val text: String, val firstKana: Int)

/** 句ごとに分けた読み。 */
internal data class Verse(val kami: List<VerseLine>, val shimo: List<VerseLine>)

/** 上の句・下の句の読みを句ごとに分け、仮名の通し番号を付ける。 */
internal fun verseOf(poem: Poem): Verse {
    var next = 0
    fun lines(yomi: String): List<VerseLine> =
        yomi.split(' ', '　')
            .filter { it.isNotEmpty() }
            .map { phrase -> VerseLine(phrase, next).also { next += phrase.length } }
    val kami = lines(poem.kamiYomi)
    val shimo = lines(poem.shimoYomi)
    return Verse(kami, shimo)
}

/** 上の句の読みの先頭から [length] 文字（決まり字）。 */
internal fun kimarijiOf(poem: Poem, length: Int): String =
    (poem.kamiYomi + poem.shimoYomi).filterNot { it.isWhitespace() }.take(length)

/** 結果表示での仮名の区分。 */
internal enum class KanaTone {
    /** 決まり字（緑）。 */
    Kimariji,

    /** 取った時点で決まり字を超えて読まれていた部分（赤）。 */
    OverRead,

    /** 取った時点でまだ読まれていなかった部分（薄墨）。 */
    Unread,

    /** 取られなかった札の決まり字以外（墨）。 */
    Plain,
}

/** 通し番号 [index] の仮名の区分。 */
internal fun kanaTone(index: Int, kimarijiLength: Int, revealedAtTake: Int?): KanaTone = when {
    index < kimarijiLength -> KanaTone.Kimariji
    revealedAtTake == null -> KanaTone.Plain
    index < revealedAtTake -> KanaTone.OverRead
    else -> KanaTone.Unread
}

internal fun KanaTone.spanStyle(): SpanStyle = when (this) {
    // 色だけに頼らないよう、決まり字には下線も付ける
    KanaTone.Kimariji -> SpanStyle(
        color = KimarijiGreen,
        fontWeight = FontWeight.Bold,
        textDecoration = TextDecoration.Underline,
    )
    KanaTone.OverRead -> SpanStyle(color = OverReadRed, fontWeight = FontWeight.Bold)
    KanaTone.Unread -> SpanStyle(color = Usuzumi)
    KanaTone.Plain -> SpanStyle(color = Sumi)
}

/** 1句の仮名の数の上限（100首の読みで最も長い句は8文字）。 */
internal const val MAX_PHRASE_KANA = 8

/** 読みの行数（上の句3句・下の句2句）。 */
private const val VERSE_LINES = 5

private const val VERSE_LETTER_SPACING = 0.04f
private const val VERSE_LINE_HEIGHT = 1.42f

/** 上の句と下の句の間（文字の大きさに対する割合）。 */
private const val VERSE_GAP = 0.36f

/**
 * 読み（5行）が幅 [width]・高さ [height] に収まる文字の大きさ。[min]〜[max] の範囲にする。
 * 歌によって大きさが変わらないよう、句の長さは最長の [MAX_PHRASE_KANA] 文字で見積もる。
 */
internal fun fittingVerseFontSize(width: Dp, height: Dp, max: Dp, min: Dp): Dp {
    val byWidth = width / (MAX_PHRASE_KANA * (1f + VERSE_LETTER_SPACING) + 0.4f)
    val byHeight = height / (VERSE_LINES * VERSE_LINE_HEIGHT + VERSE_GAP)
    return minOf(byWidth, byHeight, max).coerceAtLeast(min)
}

/**
 * 読みの明朝体。大きさは画面の広さから決めるので、端末の文字の大きさの設定には左右されない。
 */
@Composable
internal fun verseTextStyle(fontSize: Dp): TextStyle = with(LocalDensity.current) {
    TextStyle(
        fontFamily = ShipporiMincho,
        fontSize = fontSize.toSp(),
        lineHeight = (fontSize * VERSE_LINE_HEIGHT).toSp(),
        letterSpacing = VERSE_LETTER_SPACING.em,
        color = Sumi,
    )
}

/**
 * 読みを1句1行で中央にそろえて書く。仮名ごとの見た目は [spanFor]（引数は仮名の通し番号）で決める。
 * 上の句と下の句の間は文字の大きさに合わせて空ける。
 */
@Composable
internal fun VerseText(
    verse: Verse,
    style: TextStyle,
    spanFor: (Int) -> SpanStyle,
    modifier: Modifier = Modifier,
) {
    val gap = with(LocalDensity.current) { (style.fontSize * VERSE_GAP).toDp() }
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        verse.kami.forEach { VerseLineText(it, style, spanFor) }
        Spacer(Modifier.height(gap))
        verse.shimo.forEach { VerseLineText(it, style, spanFor) }
    }
}

/**
 * 読みを詰めて書く。上の句・下の句をそれぞれ句ごとに横へ並べ、入り切らなければ句の切れ目で折り返す。
 * 狭い欄で [VerseText] の代わりに使う。
 */
@Composable
internal fun CompactVerseText(
    verse: Verse,
    style: TextStyle,
    spanFor: (Int) -> SpanStyle,
    modifier: Modifier = Modifier,
) {
    val gap = with(LocalDensity.current) { (style.fontSize * 0.6f).toDp() }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        for (lines in listOf(verse.kami, verse.shimo)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
                lines.forEach { VerseLineText(it, style, spanFor) }
            }
        }
    }
}

@Composable
private fun VerseLineText(line: VerseLine, style: TextStyle, spanFor: (Int) -> SpanStyle) {
    val text = buildAnnotatedString {
        line.text.forEachIndexed { i, char ->
            withStyle(spanFor(line.firstKana + i)) { append(char) }
        }
    }
    // 1句は必ず1行に書く（文字が増えても折り返しで形が変わらないように）
    Text(text = text, style = style, textAlign = TextAlign.Center, softWrap = false, maxLines = 1)
}

/** 句ごとの読みを覚えておく。 */
@Composable
internal fun rememberVerse(poem: Poem): Verse = remember(poem) { verseOf(poem) }
