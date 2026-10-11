package io.github.abonsan.hyakunin.ui.match

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.abonsan.hyakunin.game.MatchUiState
import io.github.abonsan.hyakunin.game.ReadStartMode
import io.github.abonsan.hyakunin.ui.card.torifudaHeight
import io.github.abonsan.hyakunin.ui.components.CenteredScrollColumn
import io.github.abonsan.hyakunin.ui.components.OrnamentTitle
import io.github.abonsan.hyakunin.ui.components.PrimaryButton
import io.github.abonsan.hyakunin.ui.components.SecondaryButton
import io.github.abonsan.hyakunin.ui.theme.CardFace
import io.github.abonsan.hyakunin.ui.theme.Kin
import io.github.abonsan.hyakunin.ui.theme.KinchaInk
import io.github.abonsan.hyakunin.ui.theme.KinchaPale
import io.github.abonsan.hyakunin.ui.theme.Shu
import io.github.abonsan.hyakunin.ui.theme.Sumi
import io.github.abonsan.hyakunin.ui.theme.UsuzumiText
import io.github.abonsan.hyakunin.ui.theme.WashiDeep
import io.github.abonsan.hyakunin.ui.theme.WashiLight
import io.github.abonsan.hyakunin.ui.theme.noLineBreak

/** 次の札を読むまでの表示に使う値。[waitingInfo] で対戦の状態から作る。 */
internal data class WaitingInfo(
    /** 次に読む札が何枚目か。 */
    val nextNumber: Int,
    val totalReads: Int,
    /** まだ1枚も読んでいない（札を覚える時間）。 */
    val memorizing: Boolean,
    /** 「読み始める」を押すまで待つ。 */
    val tapToStart: Boolean,
    /** 自動で読み始めるまでの残り（ms）。分からなければ null。 */
    val remainingMillis: Long?,
    /** 待ち時間の全体（ms）。残り時間の輪の割合に使う。 */
    val totalMillis: Long,
    /** 直前の送り札で自陣に来た札の数。 */
    val movedToPlayer: Int,
    /** 直前の送り札で敵陣へ行った札の数。 */
    val movedToCpu: Int,
)

internal fun waitingInfo(state: MatchUiState): WaitingInfo {
    // 1枚も読み終えていない Waiting は、最初の札を読む前（札を覚える時間）
    val memorizing = state.isMemorizing || state.readsDone == 0
    val tap = state.config.readStartMode == ReadStartMode.TAP
    return WaitingInfo(
        nextNumber = (state.readsDone + 1).coerceAtMost(state.totalReads),
        totalReads = state.totalReads,
        memorizing = memorizing,
        tapToStart = tap,
        remainingMillis = if (tap) null else state.waitRemainingMillis,
        totalMillis = if (memorizing) state.config.memorizeTime.millis else state.config.readInterval.millis,
        movedToPlayer = state.playerCards.count { it.isNew },
        movedToCpu = state.cpuCards.count { it.isNew },
    )
}

/** 自動で読み始めるまでの残り時間（例:「1分05秒」「25秒」）。秒の端数は切り上げる。 */
internal fun remainingText(millis: Long): String {
    val seconds = (millis.coerceAtLeast(0L) + 999L) / 1000L
    return if (seconds >= 60L) "${seconds / 60}分${(seconds % 60).toString().padStart(2, '0')}秒" else "${seconds}秒"
}

/**
 * 送り札で動いた札（金の縁）の案内。動いた札が無ければ null。枚数の部分は行をまたいで分けない。
 * 相手から送られてきた札は、2枚以上のときだけ枚数を添える。
 */
internal fun movedCardsNote(toPlayer: Int, toCpu: Int): String? = when {
    toPlayer > 0 && toCpu > 0 ->
        "金の縁は送り札で動いた札です（" + noLineBreak("自陣へ${toPlayer}枚") + "・" + noLineBreak("敵陣へ${toCpu}枚") + "）"
    toPlayer > 1 -> "金の縁は相手から送られてきた札です" + noLineBreak("（${toPlayer}枚）")
    toPlayer > 0 -> "金の縁は相手から送られてきた札です"
    toCpu > 0 -> "金の縁は相手へ送った札です" + noLineBreak("（${toCpu}枚）")
    else -> null
}

/** 最初の札を読む前の、陣の場所と取り方の案内。 */
internal fun boardGuide(fieldsAside: Boolean): String = if (fieldsAside) {
    "右下が自陣（あなた）、右上が敵陣（相手）です。\n読まれた歌の札に、相手より先に触れると取れます。"
} else {
    "下が自陣（あなた）、上が敵陣（相手）です。\n読まれた歌の札に、相手より先に触れると取れます。"
}

/**
 * 次の札を読むまでの表示。場の札を確かめる時間なので、札には何も重ねない。
 * 自動なら残り時間の輪と「すぐ読む」、タップで読み始めるなら「読み始める」を大きく出す。
 * 送り札で札が動いたときは、金の縁の札がそれだと添える。
 *
 * @param fieldsAside 場が右側にある配置（横に広い画面）。案内の言い方を合わせる。
 */
@Composable
internal fun WaitingPanel(
    info: WaitingInfo,
    onStartReading: () -> Unit,
    fieldsAside: Boolean,
    modifier: Modifier = Modifier,
) {
    CenterPanel(modifier) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxHeight < 380.dp || maxWidth < 280.dp
            // 低い欄（分割画面など）では、残り時間の輪の横に見出しとボタンを置き、ボタンまで欄に収める
            val short = maxHeight < ShortWaitingHeight && maxWidth >= ShortWaitingMinWidth
            // 低い欄では残り時間の輪を小さくして、案内まで欄に収める
            val ring = (maxHeight - 200.dp).coerceIn(72.dp, if (compact) 92.dp else 128.dp)
            CenteredScrollColumn(Modifier.fillMaxSize(), fadeColor = WashiLight) {
                Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                OrnamentTitle(
                    text = readCounterText(info.nextNumber, info.totalReads),
                    modifier = Modifier.padding(horizontal = if (compact) 16.dp else 32.dp),
                )
                Spacer(Modifier.height(if (compact) 8.dp else 14.dp))
                if (short) {
                    Row(
                        Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (!info.tapToStart) {
                            CountdownRing(info.remainingMillis, info.totalMillis, diameter = 72.dp)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            WaitingTitle(info, fontSize = 20.sp)
                            Spacer(Modifier.height(8.dp))
                            StartButton(info, onStartReading, compact = true)
                        }
                    }
                } else {
                    WaitingTitle(info, fontSize = if (compact) 21.sp else 24.sp)
                    Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
                    if (info.tapToStart) {
                        Text(
                            text = if (info.memorizing) "札の位置を覚えたら押してください" else "準備ができたら押してください",
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = UsuzumiText,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
                    } else {
                        CountdownRing(
                            remainingMillis = info.remainingMillis,
                            totalMillis = info.totalMillis,
                            diameter = ring,
                        )
                        Spacer(Modifier.height(if (compact) 10.dp else 16.dp))
                    }
                    StartButton(info, onStartReading, compact = compact)
                }
                val note = if (info.memorizing) null else movedCardsNote(info.movedToPlayer, info.movedToCpu)
                when {
                    info.memorizing -> {
                        Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
                        Text(
                            text = boardGuide(fieldsAside),
                            modifier = Modifier.padding(horizontal = 16.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = UsuzumiText,
                            textAlign = TextAlign.Center,
                        )
                    }
                    note != null -> {
                        Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
                        MovedCardsNote(note, Modifier.padding(horizontal = 12.dp))
                    }
                }
                Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
            }
        }
    }
}

/** これより低い欄では、残り時間の輪の横に見出しとボタンを並べる。 */
private val ShortWaitingHeight = 250.dp

/** 輪の横に見出しとボタンを並べられる欄の幅。 */
private val ShortWaitingMinWidth = 280.dp

/** 待っている理由の見出し（札を覚える時間・札を確かめる時間・次の札まで）。 */
@Composable
private fun WaitingTitle(info: WaitingInfo, fontSize: TextUnit) {
    Text(
        text = when {
            info.memorizing -> "札を覚える時間"
            info.tapToStart -> "札を確かめる時間"
            else -> "次の札まで"
        },
        style = MaterialTheme.typography.headlineSmall,
        fontSize = fontSize,
        color = Sumi,
        letterSpacing = 0.12.em,
        maxLines = 1,
    )
}

/** タップで読み始めるなら目立つ「読み始める」、自動なら控えめな「すぐ読む」。 */
@Composable
private fun StartButton(info: WaitingInfo, onStartReading: () -> Unit, compact: Boolean) {
    if (info.tapToStart) {
        PrimaryButton(
            text = "読み始める",
            onClick = onStartReading,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .widthIn(min = if (compact) 180.dp else 220.dp),
            minHeight = if (compact) 52.dp else 60.dp,
        )
    } else {
        SecondaryButton(
            text = "すぐ読む",
            onClick = onStartReading,
            minHeight = 44.dp,
        )
    }
}

/** 残り時間を、減っていく朱の輪と数字で示す。 */
@Composable
private fun CountdownRing(remainingMillis: Long?, totalMillis: Long, diameter: Dp) {
    val fraction = if (remainingMillis != null && totalMillis > 0L) {
        (remainingMillis.toFloat() / totalMillis).coerceIn(0f, 1f)
    } else {
        1f
    }
    val label = remainingMillis?.let(::remainingText)
    Box(
        Modifier
            .size(diameter)
            .clearAndSetSemantics { contentDescription = label?.let { "残り$it" } ?: "まもなく読み始めます" }
            .drawBehind {
                val stroke = 4.dp.toPx()
                val topLeft = Offset(stroke / 2, stroke / 2)
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(WashiDeep, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
                // 上から時計回りに、残りの割合だけ描く
                drawArc(
                    color = Shu,
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (label != null) {
            CountdownLabel(label, digitSize = if (diameter >= 120.dp) 34.sp else 26.sp)
        } else {
            Text("まもなく", style = MaterialTheme.typography.titleMedium, color = UsuzumiText)
        }
    }
}

/** 「1分05秒」の数字を大きく、「分」「秒」を小さく書く。 */
@Composable
private fun CountdownLabel(text: String, digitSize: TextUnit) {
    val annotated = buildAnnotatedString {
        text.forEach { char ->
            if (char.isDigit()) {
                append(char)
            } else {
                withStyle(SpanStyle(fontSize = digitSize * 0.42f, fontWeight = FontWeight.Normal)) { append(char) }
            }
        }
    }
    Text(
        text = annotated,
        style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"),
        fontSize = digitSize,
        fontWeight = FontWeight.Bold,
        color = Sumi,
        maxLines = 1,
    )
}

/** 送り札で動いた札の案内。金の縁の小さな札を印として添える。 */
@Composable
private fun MovedCardsNote(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(KinchaPale, MaterialTheme.shapes.small)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        // 取り札を縮めた印（同じ縦横比で直角）。縁は札と同じ比なら約 0.8dp なので 1dp にする
        Box(
            Modifier
                .size(width = MovedCardMarkWidth, height = torifudaHeight(MovedCardMarkWidth))
                .background(CardFace)
                .border(1.dp, Kin),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            color = KinchaInk,
        )
    }
}

/** 送り札の案内に添える小さな札の幅。 */
private val MovedCardMarkWidth = 12.dp
