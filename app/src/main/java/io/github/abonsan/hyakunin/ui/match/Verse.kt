package io.github.abonsan.hyakunin.ui.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
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
import io.github.abonsan.hyakunin.game.ReadResult
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.game.verseBoundaries
import io.github.abonsan.hyakunin.ui.theme.KimarijiGreen
import io.github.abonsan.hyakunin.ui.theme.OverReadRed
import io.github.abonsan.hyakunin.ui.theme.ShipporiMincho
import io.github.abonsan.hyakunin.ui.theme.Sumi
import io.github.abonsan.hyakunin.ui.theme.Usuzumi

/** 表示する1句。[firstKana] は句の先頭の文字の通し番号（空白を除き、上の句の先頭を0として数える）。 */
internal data class VerseLine(val text: String, val firstKana: Int)

/**
 * 句ごとに分けた、表示する仮名（[VerseStyle] の仮名遣い）。文字の通し番号は表示する文字で数える。
 * エンジンの仮名の数（読みの仮名で数える）は [displayCount] で表示の文字数に直す。
 *
 * @param boundaries `boundaries[i]` は、読みの先頭 i 文字に対応する表示の文字数（`verseBoundaries` と同じ形）。
 */
internal class Verse(
    val kami: List<VerseLine>,
    val shimo: List<VerseLine>,
    private val boundaries: IntArray,
) {
    /** 空白を除き、上の句から下の句まで通した表示の文字。 */
    val text: String = (kami + shimo).joinToString("") { it.text }

    /** 読みの先頭から [kana] 文字（エンジンの数）に対応する、表示の文字数。 */
    fun displayCount(kana: Int): Int = boundaries[kana.coerceIn(0, boundaries.lastIndex)]

    /** 上の句の表示の文字数（下の句の先頭の文字の通し番号）。 */
    val kamiLength: Int get() = kami.sumOf { it.text.length }

    /** 下の句だけの歌。文字の通し番号は元のまま（上の句の先頭を 0 として数える）。 */
    fun shimoOnly(): Verse = Verse(emptyList(), shimo, boundaries)
}

/**
 * [poem] の上の句・下の句を、[style] の仮名遣いで句ごとに分けて文字の通し番号を付ける。
 * 歴史的仮名遣いでは、読みとの文字位置の対応を `verseBoundaries` で求める。
 */
internal fun verseOf(poem: Poem, style: VerseStyle): Verse {
    val kanaBoundaries = if (style == VerseStyle.KANA) {
        // 読みと対応づけできない歌（データの誤り）は、表示が崩れないよう読みで表示する
        try {
            verseBoundaries(poem)
        } catch (e: IllegalArgumentException) {
            null
        }
    } else {
        null
    }
    var next = 0
    fun lines(text: String): List<VerseLine> =
        text.split(' ', '　')
            .filter { it.isNotEmpty() }
            .map { phrase -> VerseLine(phrase, next).also { next += phrase.length } }
    if (kanaBoundaries != null) return Verse(lines(poem.kamiKana), lines(poem.shimoKana), kanaBoundaries)
    val kami = lines(poem.kamiYomi)
    val shimo = lines(poem.shimoYomi)
    // 読みで表示するときは、エンジンの数がそのまま表示の文字数になる
    return Verse(kami, shimo, IntArray(next + 1) { it })
}

/** 結果の色分けに使う、表示の文字数で数えた決まり字の長さと取った位置。 */
internal data class TakeMarks(
    /** 決まり字の文字数。 */
    val kimariji: Int,
    /** 札が取られた時点で読まれていた文字数。取られなかった場合は null。 */
    val taken: Int?,
)

/** [result] の決まり字の長さと取った位置（エンジンの仮名の数）を、[verse] の表示の文字数に直す。 */
internal fun takeMarks(result: ReadResult, verse: Verse) = TakeMarks(
    kimariji = verse.displayCount(result.kimarijiLength),
    taken = result.revealedAtTake?.let(verse::displayCount),
)

/** 表示している句の先頭から、決まり字の部分。 */
internal fun kimarijiText(verse: Verse, marks: TakeMarks): String = verse.text.take(marks.kimariji)

/** 結果表示での文字の区分。 */
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

/** 通し番号 [index] の文字の区分。[kimarijiLength] と [revealedAtTake] は表示の文字数。 */
internal fun kanaTone(index: Int, kimarijiLength: Int, revealedAtTake: Int?): KanaTone = when {
    index < kimarijiLength -> KanaTone.Kimariji
    revealedAtTake == null -> KanaTone.Plain
    index < revealedAtTake -> KanaTone.OverRead
    else -> KanaTone.Unread
}

/** 通し番号 [index] の文字の区分。 */
internal fun TakeMarks.toneOf(index: Int): KanaTone = kanaTone(index, kimariji, taken)

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

/** 1句の文字数の上限（100首で最も長い句は、読みでも歴史的仮名遣いでも8文字）。 */
internal const val MAX_PHRASE_KANA = 8

/** 句の行数（上の句3句・下の句2句）。 */
private const val VERSE_LINES = 5

private const val VERSE_LETTER_SPACING = 0.04f
private const val VERSE_LINE_HEIGHT = 1.42f

/** 上の句と下の句の間（文字の大きさに対する割合）。 */
private const val VERSE_GAP = 0.36f

/**
 * 句（5行）が幅 [width]・高さ [height] に収まる文字の大きさ。[min]〜[max] の範囲にする。
 * 歌によって大きさが変わらないよう、句の長さは最長の [MAX_PHRASE_KANA] 文字で見積もる。
 */
internal fun fittingVerseFontSize(width: Dp, height: Dp, max: Dp, min: Dp): Dp {
    val byWidth = width / (MAX_PHRASE_KANA * (1f + VERSE_LETTER_SPACING) + 0.4f)
    val byHeight = height / (VERSE_LINES * VERSE_LINE_HEIGHT + VERSE_GAP)
    return minOf(byWidth, byHeight, max).coerceAtLeast(min)
}

/** 文字の大きさ [fontSize] で、句を1句1行（5行）に書いたときの高さ。 */
internal fun verseHeight(fontSize: Dp): Dp = fontSize * (VERSE_LINES * VERSE_LINE_HEIGHT + VERSE_GAP)

/** 文字の大きさ [fontSize] で、句を横に並べたとき（上の句・下の句をそれぞれ1行）の高さ。 */
internal fun flowedVerseHeight(fontSize: Dp): Dp = fontSize * (2 * VERSE_LINE_HEIGHT)

/** 上の句・下の句の文字数の上限（100首で最も長いものは、読みでも歴史的仮名遣いでも19文字と16文字）。 */
internal const val MAX_KAMI_KANA = 19
internal const val MAX_SHIMO_KANA = 16

/** [CompactVerseText] の句の間（文字の大きさに対する割合）。 */
private const val FLOW_PHRASE_GAP = 0.6f

/**
 * 句を横に並べたとき（[CompactVerseText]。上の句・下の句をそれぞれ1行）に幅 [width]・高さ [height] に
 * 収まる文字の大きさ。[min]〜[max] の範囲にする。句の長さは最長の歌で見積もる。
 */
internal fun fittingFlowedVerseFontSize(width: Dp, height: Dp, max: Dp, min: Dp): Dp {
    val byWidth = width / (MAX_KAMI_KANA * (1f + VERSE_LETTER_SPACING) + 2 * FLOW_PHRASE_GAP)
    val byHeight = height / (2 * VERSE_LINE_HEIGHT)
    return minOf(byWidth, byHeight, max).coerceAtLeast(min)
}

/**
 * 句の明朝体。大きさは画面の広さから決めるので、端末の文字の大きさの設定には左右されない。
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
 * 句を1句1行で中央にそろえて書く。文字ごとの見た目は [spanFor]（引数は文字の通し番号）で決める。
 * 上の句と下の句の間は文字の大きさに合わせて空ける。
 *
 * @param fade 新しく見せた文字の浮かび上がり。null なら [spanFor] のとおりにすぐ描く
 */
@Composable
internal fun VerseText(
    verse: Verse,
    style: TextStyle,
    spanFor: (Int) -> SpanStyle,
    modifier: Modifier = Modifier,
    fade: KanaFadeIn? = null,
) {
    val gap = with(LocalDensity.current) { (style.fontSize * VERSE_GAP).toDp() }
    Column(
        modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        verse.kami.forEach { VerseLineText(it, style, spanFor, fade) }
        // 下の句だけ（前置きの前の歌の下の句）のときは間を空けない
        if (verse.kami.isNotEmpty() && verse.shimo.isNotEmpty()) Spacer(Modifier.height(gap))
        verse.shimo.forEach { VerseLineText(it, style, spanFor, fade) }
    }
}

/**
 * 句を詰めて書く。上の句・下の句をそれぞれ句ごとに横へ並べ、入り切らなければ句の切れ目で折り返す。
 * 狭い欄で [VerseText] の代わりに使う。[fade] は [VerseText] と同じ。
 */
@Composable
internal fun CompactVerseText(
    verse: Verse,
    style: TextStyle,
    spanFor: (Int) -> SpanStyle,
    modifier: Modifier = Modifier,
    fade: KanaFadeIn? = null,
) {
    val gap = with(LocalDensity.current) { (style.fontSize * FLOW_PHRASE_GAP).toDp() }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        for (lines in listOf(verse.kami, verse.shimo)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)) {
                lines.forEach { VerseLineText(it, style, spanFor, fade) }
            }
        }
    }
}

/**
 * 1句を書く。[fade] があれば、元の色で描き終えた文字だけを下に描き、浮かび上がっている最中の文字は、同じ句を
 * その文字だけ元の色にして重ね、その不透明度だけを描くときに変える（文字の配置は変わらず、句も組み直さない）。
 */
@Composable
private fun VerseLineText(line: VerseLine, style: TextStyle, spanFor: (Int) -> SpanStyle, fade: KanaFadeIn?) {
    if (fade == null) {
        LineText(lineText(line, spanFor), style)
        return
    }
    Box {
        LineText(lineText(line) { if (fade.isSettled(it)) spanFor(it) else Hidden }, style)
        for (kana in fade.risingIn(line)) {
            key(kana) {
                LineText(
                    text = lineText(line) { if (it in kana) spanFor(it) else Hidden },
                    style = style,
                    modifier = Modifier.graphicsLayer {
                        alpha = kana.alpha
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                )
            }
        }
    }
}

/** [line] の文字を、通し番号ごとの見た目 [styleOf] で並べた文。 */
private fun lineText(line: VerseLine, styleOf: (Int) -> SpanStyle): AnnotatedString = buildAnnotatedString {
    line.text.forEachIndexed { i, char ->
        withStyle(styleOf(line.firstKana + i)) { append(char) }
    }
}

@Composable
private fun LineText(text: AnnotatedString, style: TextStyle, modifier: Modifier = Modifier) {
    // 1句は必ず1行に書く（文字が増えても折り返しで形が変わらないように）
    Text(text = text, modifier = modifier, style = style, textAlign = TextAlign.Center, softWrap = false, maxLines = 1)
}

/** [style] の仮名遣いで句ごとに分けた歌を覚えておく。 */
@Composable
internal fun rememberVerse(poem: Poem, style: VerseStyle): Verse = remember(poem, style) { verseOf(poem, style) }
