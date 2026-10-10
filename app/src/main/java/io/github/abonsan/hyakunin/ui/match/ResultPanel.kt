package io.github.abonsan.hyakunin.ui.match

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import io.github.abonsan.hyakunin.data.Poem
import io.github.abonsan.hyakunin.game.ReadResult
import io.github.abonsan.hyakunin.game.ReadingState
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.game.Side
import io.github.abonsan.hyakunin.game.Transfer
import io.github.abonsan.hyakunin.ui.components.PrimaryButton
import io.github.abonsan.hyakunin.ui.components.fadingBottomEdge
import io.github.abonsan.hyakunin.ui.theme.Gofun
import io.github.abonsan.hyakunin.ui.theme.KimarijiGreen
import io.github.abonsan.hyakunin.ui.theme.Kincha
import io.github.abonsan.hyakunin.ui.theme.Kon
import io.github.abonsan.hyakunin.ui.theme.OverReadRed
import io.github.abonsan.hyakunin.ui.theme.Shu
import io.github.abonsan.hyakunin.ui.theme.ShuDeep
import io.github.abonsan.hyakunin.ui.theme.ShuPale
import io.github.abonsan.hyakunin.ui.theme.Sumi
import io.github.abonsan.hyakunin.ui.theme.UsuzumiText
import io.github.abonsan.hyakunin.ui.theme.WashiDeep
import io.github.abonsan.hyakunin.ui.theme.WashiLight

/** 結果表示の読みの大きさの上限と下限。読み上げ中より一回り小さくする。 */
private val ResultFontMax = 24.dp
private val ResultFontMin = 18.dp

/** 結果表示で、読み以外（見出し・説明・凡例・ボタン）に使う高さの目安。 */
private val ResultChromeHeight = 300.dp

/** これより低い欄では、見出しを1行にまとめ、読みを句ごとに詰めて並べる。 */
private val CompactPanelHeight = 380.dp

/** 詰めて並べるときの読みの大きさ。 */
private val CompactFontSize = 19.dp

/**
 * 1回の読みの結果。読みを全文表示し、決まり字を緑、取った時点で決まり字を超えて読まれていた部分を赤で示す。
 * 何が起きたかの説明と色分けした読みを先に、お手つき・送り札の内訳、歌の表記と作者を後に置く。
 * 入り切らないときは中だけ動かせる（ボタンや案内は常に見える）。
 *
 * @param pendingSends 自陣から送る札をあと何枚選ぶか。0 より大きい間は「次へ」の代わりに案内を出す。
 * @param onNext 「次へ」。null なら出さない（試合終了後など）。
 */
@Composable
internal fun ResultPanel(
    result: ReadResult,
    reading: ReadingState?,
    ruleMode: RuleMode,
    pendingSends: Int,
    onNext: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    CenterPanel(modifier, shadowElevation = 3.dp) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < CompactPanelHeight
            val fontSize = if (compact) {
                CompactFontSize
            } else {
                fittingVerseFontSize(
                    width = maxWidth - 32.dp,
                    height = maxHeight - ResultChromeHeight,
                    max = ResultFontMax,
                    min = ResultFontMin,
                )
            }
            val verse = rememberVerse(result.poem)
            val spanFor = { index: Int -> kanaTone(index, result.kimarijiLength, result.revealedAtTake).spanStyle() }
            val message = splitResultMessage(result.message)
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(1f)
                        .fadingBottomEdge(scroll, WashiLight)
                        .verticalScroll(scroll)
                        .padding(horizontal = 16.dp, vertical = if (compact) 10.dp else 12.dp),
                ) {
                    if (compact) {
                        CompactResultHeader(result, message)
                        Spacer(Modifier.height(8.dp))
                        CompactVerseText(verse, verseTextStyle(fontSize), spanFor, Modifier.fillMaxWidth())
                    } else {
                        ResultHeader(result, reading?.takeIf { it.poem.no == result.poem.no })
                        Spacer(Modifier.height(8.dp))
                        ResultMessage(message)
                        Spacer(Modifier.height(14.dp))
                        VerseText(verse, verseTextStyle(fontSize), spanFor, Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.height(10.dp))
                    KimarijiLegend(result, Modifier.fillMaxWidth())
                    val notes = resultNotes(result, ruleMode)
                    if (notes.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        notes.forEach { NoteLine(it) }
                    }
                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = Kincha.copy(alpha = 0.35f))
                    Spacer(Modifier.height(10.dp))
                    PoemInfo(result.poem)
                }
                ResultFooter(pendingSends, onNext, compact)
            }
        }
    }
}

@Composable
private fun ResultHeader(result: ReadResult, reading: ReadingState?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TakerBadge(result)
        Spacer(Modifier.weight(1f))
        if (reading != null) {
            Text(
                text = readCounterText(reading.readNumber, reading.totalReads),
                style = MaterialTheme.typography.labelMedium,
                color = UsuzumiText,
            )
        }
    }
}

/** 低い欄の見出し。印と最初の文を1行に並べ、続く文は小さく添える。 */
@Composable
private fun CompactResultHeader(result: ReadResult, message: ResultMessageParts) {
    Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TakerBadge(result)
            Spacer(Modifier.width(10.dp))
            Text(text = message.headline, style = MaterialTheme.typography.titleSmall, color = Sumi)
        }
        if (message.detail != null) {
            Text(text = message.detail, style = MaterialTheme.typography.bodySmall, color = Sumi)
        }
    }
}

/** 誰が取ったか（空札・取られず）の印。 */
@Composable
private fun TakerBadge(result: ReadResult) {
    val (label, color) = when {
        result.isEmptyCard -> "空札" to UsuzumiText
        result.takenBy == Side.PLAYER -> "あなたの取り" to Kon
        result.takenBy == Side.CPU -> "相手の取り" to ShuDeep
        else -> "取られず" to UsuzumiText
    }
    Text(
        text = label,
        modifier = Modifier
            .background(color, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelLarge,
        color = Gofun,
        maxLines = 1,
    )
}

/** 結果の説明。最初の文を見出しにし、続く文（お手つき・送り札の案内など）はその下にまとめて添える。 */
@Composable
private fun ResultMessage(message: ResultMessageParts) {
    Column(Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Text(text = message.headline, style = MaterialTheme.typography.titleMedium, color = Sumi)
        if (message.detail != null) {
            Text(text = message.detail, style = MaterialTheme.typography.bodyMedium, color = Sumi)
        }
    }
}

/** 結果の説明を見出し（最初の文）と、続く文に分けたもの。 */
internal data class ResultMessageParts(val headline: String, val detail: String?)

/** 説明文を「。」で区切り、最初の文を見出しにする。続く文は「。」でつなぎ直す。 */
internal fun splitResultMessage(message: String): ResultMessageParts {
    val sentences = message.split('。').map { it.trim() }.filter { it.isNotEmpty() }
    return ResultMessageParts(
        headline = sentences.firstOrNull().orEmpty(),
        detail = sentences.drop(1).joinToString("。").ifEmpty { null },
    )
}

/** 凡例と、決まり字・取った位置のまとめ。 */
@Composable
private fun KimarijiLegend(result: ReadResult, modifier: Modifier = Modifier) {
    val taken = result.revealedAtTake
    val early = taken != null && taken < result.kimarijiLength
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            LegendItem(
                color = KimarijiGreen,
                text = "決まり字「${kimarijiOf(result.poem, result.kimarijiLength)}」",
                underline = true,
            )
            if (taken != null && taken > result.kimarijiLength) {
                LegendItem(color = OverReadRed, text = "決まり字を超えて読まれた部分")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = takeSummary(result),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (early) FontWeight.Bold else null,
            color = if (early) Kon else UsuzumiText,
            textAlign = TextAlign.Center,
        )
    }
}

/** 決まり字の長さと、取ったのが何文字目だったか。 */
internal fun takeSummary(result: ReadResult): String {
    val taken = result.revealedAtTake
    return when {
        taken == null -> "決まり字は ${result.kimarijiLength} 字"
        taken < result.kimarijiLength -> "決まり字が出る前に取りました（$taken 字目）"
        else -> "決まり字は ${result.kimarijiLength} 字・$taken 字目で取りました"
    }
}

@Composable
private fun LegendItem(color: Color, text: String, underline: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .background(color, MaterialTheme.shapes.extraSmall),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = color,
            textDecoration = if (underline) TextDecoration.Underline else null,
        )
    }
}

/** 歌番号・作者・漢字表記の歌。 */
@Composable
private fun PoemInfo(poem: Poem) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${poem.no}番",
                style = MaterialTheme.typography.labelLarge,
                color = Kon,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = poem.author,
                style = MaterialTheme.typography.bodyMedium,
                color = UsuzumiText,
            )
        }
        Spacer(Modifier.height(4.dp))
        PhraseFlow(poem.kami, MaterialTheme.typography.bodyLarge)
        PhraseFlow(poem.shimo, MaterialTheme.typography.bodyLarge, Modifier.padding(start = 28.dp))
    }
}

/** 句（全角スペースで区切る）の途中では折り返さずに並べる。 */
@Composable
private fun PhraseFlow(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        text.split('　', ' ').filter { it.isNotEmpty() }.forEach { phrase ->
            Text(text = phrase, style = style, color = Sumi, softWrap = false)
        }
    }
}

/** 結果に添える注記（お手つき・送り札など）。 */
internal data class ResultNote(val label: String, val text: String, val isFault: Boolean = false)

internal fun resultNotes(result: ReadResult, ruleMode: RuleMode): List<ResultNote> = buildList {
    if (result.faults.isNotEmpty()) {
        val who = Side.entries.filter { it in result.faults }.joinToString("・") { it.displayName }
        val offset = ruleMode == RuleMode.COMPETITIVE && result.faults.size == Side.entries.size
        add(ResultNote("お手つき", if (offset) "$who（罰の送り札は相殺）" else who, isFault = true))
    }
    Side.entries.filter { it in result.safeMisses }.forEach {
        add(ResultNote("空振り", "${it.displayName}は同じ陣の別の札に触れました（お手つきなし）"))
    }
    result.transfers
        .groupBy { Triple(it.reason, it.from, it.to) }
        .forEach { (key, transfers) ->
            val (reason, from, to) = key
            val label = when (reason) {
                Transfer.Reason.SEND_AFTER_TAKE -> "送り札"
                Transfer.Reason.FAULT_PENALTY -> "罰の送り札"
            }
            add(ResultNote(label, "${from.displayName} → ${to.displayName}（${transfers.size}枚）"))
        }
}

internal val Side.displayName: String
    get() = when (this) {
        Side.PLAYER -> "あなた"
        Side.CPU -> "相手"
    }

@Composable
private fun NoteLine(note: ResultNote) {
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = note.label,
            modifier = Modifier
                .background(if (note.isFault) ShuPale else WashiDeep, MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = if (note.isFault) ShuDeep else Sumi,
        )
        Spacer(Modifier.width(8.dp))
        Text(text = note.text, style = MaterialTheme.typography.bodyMedium, color = Sumi)
    }
}

@Composable
private fun ResultFooter(pendingSends: Int, onNext: (() -> Unit)?, compact: Boolean) {
    val margin = if (compact) 8.dp else 12.dp
    when {
        pendingSends > 0 -> Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = margin, end = margin, bottom = margin)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            shape = MaterialTheme.shapes.medium,
            color = ShuPale,
            border = BorderStroke(1.5.dp, Shu),
        ) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "送る札を自陣から選んでください（あと $pendingSends 枚）",
                    style = MaterialTheme.typography.titleSmall,
                    color = ShuDeep,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = "▼ 下の自陣の札に触れると相手へ送ります",
                    style = MaterialTheme.typography.bodySmall,
                    color = Sumi,
                    textAlign = TextAlign.Center,
                )
            }
        }
        onNext != null -> PrimaryButton(
            text = "次へ",
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = margin, end = margin, bottom = margin),
            minHeight = if (compact) 44.dp else 52.dp,
        )
    }
}
