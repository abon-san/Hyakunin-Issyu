package io.github.abonsan.hyakunin.ui.match

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.em
import io.github.abonsan.hyakunin.game.MatchPhase
import io.github.abonsan.hyakunin.game.MatchUiState
import io.github.abonsan.hyakunin.game.ReadingState
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.game.Side
import io.github.abonsan.hyakunin.ui.components.OrnamentTitle
import io.github.abonsan.hyakunin.ui.components.WashiPanel
import io.github.abonsan.hyakunin.ui.theme.Shu
import io.github.abonsan.hyakunin.ui.theme.ShuDeep
import io.github.abonsan.hyakunin.ui.theme.ShuPale
import io.github.abonsan.hyakunin.ui.theme.Sumi
import io.github.abonsan.hyakunin.ui.theme.UsuzumiText

private val Revealed = SpanStyle(color = Sumi)
private val Hidden = SpanStyle(color = Color.Transparent)

/** 読み上げ中の文字の大きさの上限と下限。 */
private val ReadingFontMax = 34.dp
private val ReadingFontMin = 17.dp

/** お手つきの知らせを出す欄の高さ。知らせが出ても読みの位置が動かないよう、常に空けておく。 */
private val NoticeSlotHeight = 64.dp

/** 「第 n 首 ／ 全 m 首」とその下の間の高さ。 */
private val ReadingHeaderHeight = 40.dp

internal fun readCounterText(readNumber: Int, totalReads: Int) = "第 $readNumber 首 ／ 全 $totalReads 首"

/** 次の札を読むまでの表示。 */
@Composable
internal fun WaitingPanel(
    nextNumber: Int,
    totalReads: Int,
    isFirst: Boolean,
    modifier: Modifier = Modifier,
) {
    CenterPanel(modifier) {
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OrnamentTitle(
                text = readCounterText(nextNumber, totalReads),
                modifier = Modifier.padding(horizontal = 32.dp),
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = if (isFirst) "まもなく読み始めます" else "次の札を読みます",
                style = MaterialTheme.typography.headlineSmall,
                color = UsuzumiText,
                letterSpacing = 0.1.em,
            )
            if (isFirst) {
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "下が自陣（あなた）、上が敵陣（相手）です。\n読まれた歌の札に、相手より先に触れると取れます。",
                    modifier = Modifier.padding(horizontal = 24.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = UsuzumiText,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/**
 * 読み上げ中の表示。読みを [ReadingState.revealedKana] 文字まで見せる。
 * まだ読んでいない文字は透明で描き、文字が増えても配置が動かないようにする。
 * 下にはお手つきの知らせの欄を空けておき、残りの高さに収まる範囲で文字をなるべく大きくする。
 */
@Composable
internal fun ReadingPanel(
    reading: ReadingState,
    notice: LockoutNotice?,
    modifier: Modifier = Modifier,
) {
    val verse = rememberVerse(reading.poem)
    val revealed = reading.revealedKana
    CenterPanel(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val fontSize = fittingVerseFontSize(
                width = maxWidth - 32.dp,
                height = maxHeight - NoticeSlotHeight - ReadingHeaderHeight - 24.dp,
                max = ReadingFontMax,
                min = ReadingFontMin,
            )
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        OrnamentTitle(
                            text = readCounterText(reading.readNumber, reading.totalReads),
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                        Spacer(Modifier.height(14.dp))
                        VerseText(
                            verse = verse,
                            style = verseTextStyle(fontSize),
                            spanFor = { if (it < revealed) Revealed else Hidden },
                            // 読み上げ済みの部分だけを読み上げ機能に渡す
                            modifier = Modifier.clearAndSetSemantics {
                                contentDescription = revealedText(reading)
                            },
                        )
                    }
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(NoticeSlotHeight)
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    if (notice != null) LockoutBanner(notice)
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

private fun revealedText(reading: ReadingState): String =
    (reading.poem.kamiYomi + reading.poem.shimoYomi)
        .filterNot { it.isWhitespace() }
        .take(reading.revealedKana)

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

@Composable
private fun LockoutBanner(notice: LockoutNotice, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.medium,
        color = ShuPale,
        border = BorderStroke(1.dp, Shu.copy(alpha = 0.6f)),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (notice.stamp != null) {
                // 朱の印のような「お手つき」
                Text(
                    text = notice.stamp,
                    modifier = Modifier
                        .rotate(-6f)
                        .border(2.dp, Shu, MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = Shu,
                )
                Spacer(Modifier.width(14.dp))
            }
            Column {
                Text(
                    text = notice.message,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = ShuDeep,
                )
                if (notice.detail != null) {
                    Text(
                        text = notice.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = Sumi,
                    )
                }
            }
        }
    }
}
