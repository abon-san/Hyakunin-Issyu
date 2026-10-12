package io.github.abonsan.hyakunin.ui.match

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.abonsan.hyakunin.game.MatchPhase
import io.github.abonsan.hyakunin.game.MatchUiState
import io.github.abonsan.hyakunin.game.ReadingState
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.game.Side
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.ui.components.OrnamentTitle
import io.github.abonsan.hyakunin.ui.components.WashiPanel
import io.github.abonsan.hyakunin.ui.theme.Shu
import io.github.abonsan.hyakunin.ui.theme.ShuDeep
import io.github.abonsan.hyakunin.ui.theme.ShuPale
import io.github.abonsan.hyakunin.ui.theme.Sumi

/** 読み上げた文字。 */
internal val Revealed = SpanStyle(color = Sumi)

/** まだ読んでいない文字（場所だけ取る）。 */
internal val Hidden = SpanStyle(color = Color.Transparent)

/** 読み上げ中の文字の大きさの上限と下限。 */
private val ReadingFontMax = 34.dp
private val ReadingFontMin = 17.dp

/** 幅も高さも [LargePanelSize] 以上ある欄（タブレットなど）での、文字の大きさの上限。 */
private val LargeReadingFontMax = 48.dp

/** 文字の大きさの上限を上げる欄の大きさ。 */
internal val LargePanelSize = 560.dp

/** お手つきの知らせを出す欄の高さ。知らせが出ても読みの位置が動かないよう、常に空けておく。 */
private val NoticeSlotHeight = 64.dp

/** 狭い欄では知らせを2段に積むので、欄を高くする。 */
private val NarrowNoticeSlotHeight = 92.dp

/** 知らせ（印と文）を横に並べられる欄の幅。これより狭ければ積む。 */
private val WideNoticeMinWidth = 320.dp

/** 「第 n 首 ／ 全 m 首」とその下の間の高さ。 */
private val ReadingHeaderHeight = 40.dp

internal fun readCounterText(readNumber: Int, totalReads: Int) = "第 $readNumber 首 ／ 全 $totalReads 首"

/**
 * 読み上げ中の表示。句を [verseStyle] の仮名遣いで書き、[ReadingState.revealedKana]（読みの仮名の数）に
 * 対応する文字まで見せる。まだ読んでいない文字は透明で描き、文字が増えても配置が動かないようにする。
 * 配置は [VersePanel]。
 */
@Composable
internal fun ReadingPanel(
    reading: ReadingState,
    verseStyle: VerseStyle,
    notice: LockoutNotice?,
    modifier: Modifier = Modifier,
) {
    val verse = rememberVerse(reading.poem, verseStyle)
    val revealed = verse.displayCount(reading.revealedKana)
    VersePanel(
        title = readCounterText(reading.readNumber, reading.totalReads),
        verse = verse,
        spanFor = { index -> if (index < revealed) Revealed else Hidden },
        // 読み上げ済みの部分（表示している文字）だけを読み上げ機能に渡す
        description = verse.text.take(revealed),
        notice = notice,
        modifier = modifier,
    )
}

/**
 * 見出しと句（[verse]）の欄。文字ごとの見た目は [spanFor]（引数は文字の通し番号）で決める。
 * 下にはお手つきの知らせの欄を空けておき、残りの高さに収まる範囲で文字をなるべく大きくする。
 * 1句1行では収まらない低い欄（分割画面など）では、句を横に並べて詰める。
 *
 * @param title 句の上の飾り罫つきの見出し
 * @param description 読み上げ機能に渡す句の文字（表示している部分）
 * @param titleRequired 見出しを省けない（低い欄でも出す）なら true。省ける見出しは、句を横に並べても
 *   入り切らない低い欄では省く
 */
@Composable
internal fun VersePanel(
    title: String,
    verse: Verse,
    spanFor: (Int) -> SpanStyle,
    description: String,
    notice: LockoutNotice?,
    modifier: Modifier = Modifier,
    titleRequired: Boolean = false,
) {
    CenterPanel(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val narrowNotice = maxWidth < WideNoticeMinWidth
            val slotHeight = if (narrowNotice) NarrowNoticeSlotHeight else NoticeSlotHeight
            val verseWidth = maxWidth - 32.dp
            val roomBelowHeader = maxHeight - slotHeight - ReadingHeaderHeight - 24.dp
            val flowed = roomBelowHeader < verseHeight(ReadingFontMin)
            // 句を横に並べても見出しまでは入らない低い欄では、何首目かの見出しを省く（成績の欄にも出ている）
            val showTitle = titleRequired || !flowed || roomBelowHeader >= flowedVerseHeight(ReadingFontMin)
            val verseRoom = if (showTitle) roomBelowHeader else maxHeight - slotHeight - 24.dp
            val fontMax = if (maxWidth >= LargePanelSize && maxHeight >= LargePanelSize) LargeReadingFontMax else ReadingFontMax
            val fontSize = if (flowed) {
                fittingFlowedVerseFontSize(verseWidth, verseRoom, max = fontMax, min = ReadingFontMin)
            } else {
                fittingVerseFontSize(verseWidth, verseRoom, max = fontMax, min = ReadingFontMin)
            }
            val verseSemantics = Modifier.clearAndSetSemantics { contentDescription = description }
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = if (flowed) 8.dp else 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (showTitle) {
                            OrnamentTitle(text = title, modifier = Modifier.padding(horizontal = 16.dp))
                            Spacer(Modifier.height(if (flowed) 8.dp else 14.dp))
                        }
                        if (flowed) {
                            CompactVerseText(verse, verseTextStyle(fontSize), spanFor, verseSemantics.fillMaxWidth())
                        } else {
                            VerseText(verse, verseTextStyle(fontSize), spanFor, verseSemantics)
                        }
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(slotHeight)
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (notice != null) LockoutBanner(notice, stacked = narrowNotice)
                }
            }
        }
    }
}

/** 中央の欄の板。盤面との間を少し空ける。 */
@Composable
internal fun CenterPanel(
    modifier: Modifier = Modifier,
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    WashiPanel(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        shadowElevation = shadowElevation,
        content = content,
    )
}

/** お手つきなどで札を取れなくなったときの知らせ。 */
internal data class LockoutNotice(
    /** 「お手つき」の印。お手つきでなければ null。 */
    val stamp: String?,
    val message: String,
    val detail: String? = null,
)

/**
 * 自分が札を取れなくなっている理由を表示用にまとめる。
 * [touchedSide] はこの読みで自分が触れた札の陣（分からなければ null）。
 */
internal fun lockoutNotice(state: MatchUiState, touchedSide: Side?): LockoutNotice? {
    if (state.phase != MatchPhase.Reading || !state.playerLockedOut) return null
    val readNo = state.reading?.poem?.no
    val readSide = when {
        state.playerCards.any { it.poemNo == readNo } -> Side.PLAYER
        state.cpuCards.any { it.poemNo == readNo } -> Side.CPU
        else -> null
    }
    val isFault = when {
        // 簡易ルールでは違う札に触れるとお手つきとして数える
        state.config.ruleMode == RuleMode.SIMPLE -> true
        // 空札のときに札に触れた
        readSide == null -> true
        touchedSide == null -> null
        else -> touchedSide != readSide
    }
    return when (isFault) {
        true -> LockoutNotice(stamp = "お手つき", message = "この札はもう取れません")
        false -> LockoutNotice(
            stamp = null,
            message = "この札はもう取れません",
            detail = "同じ陣の札なのでお手つきにはなりません",
        )
        null -> LockoutNotice(stamp = null, message = "この札はもう取れません")
    }
}

/**
 * 自分が札を取れなくなったことの知らせ。[stacked] なら（狭い欄）印と文を縦に積む。
 */
@Composable
private fun LockoutBanner(notice: LockoutNotice, stacked: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = ShuPale,
        border = BorderStroke(1.dp, Shu.copy(alpha = 0.6f)),
    ) {
        if (stacked) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (notice.stamp != null) {
                    FaultStamp(notice.stamp)
                    Spacer(Modifier.height(6.dp))
                }
                LockoutMessage(notice, TextAlign.Center)
            }
        } else {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (notice.stamp != null) {
                    FaultStamp(notice.stamp)
                    Spacer(Modifier.width(14.dp))
                }
                Column { LockoutMessage(notice, TextAlign.Start) }
            }
        }
    }
}

/** 朱の印のような「お手つき」。 */
@Composable
private fun FaultStamp(text: String) {
    Text(
        text = text,
        modifier = Modifier
            .rotate(-6f)
            .border(2.dp, Shu, MaterialTheme.shapes.small)
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = MaterialTheme.typography.titleMedium,
        color = Shu,
        maxLines = 1,
    )
}

@Composable
private fun LockoutMessage(notice: LockoutNotice, align: TextAlign) {
    Text(
        text = notice.message,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = ShuDeep,
        textAlign = align,
    )
    if (notice.detail != null) {
        Text(
            text = notice.detail,
            style = MaterialTheme.typography.bodySmall,
            color = Sumi,
            textAlign = align,
        )
    }
}
