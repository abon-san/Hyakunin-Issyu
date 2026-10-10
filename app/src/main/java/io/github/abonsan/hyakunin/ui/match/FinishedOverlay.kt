package io.github.abonsan.hyakunin.ui.match

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.abonsan.hyakunin.game.MatchUiState
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.game.Side
import io.github.abonsan.hyakunin.ui.components.PrimaryButton
import io.github.abonsan.hyakunin.ui.components.SecondaryButton
import io.github.abonsan.hyakunin.ui.theme.Kincha
import io.github.abonsan.hyakunin.ui.theme.Kon
import io.github.abonsan.hyakunin.ui.theme.Shu
import io.github.abonsan.hyakunin.ui.theme.Sumi
import io.github.abonsan.hyakunin.ui.theme.UsuzumiText
import io.github.abonsan.hyakunin.ui.theme.WashiLight

/** 試合終了の表示。盤面の上に重ねる。[winner] が null なら引き分け。 */
@Composable
internal fun FinishedOverlay(
    state: MatchUiState,
    winner: Side?,
    onRestart: () -> Unit,
    onExit: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Sumi.copy(alpha = 0.5f))
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .padding(20.dp)
                .widthIn(max = 420.dp),
            shape = MaterialTheme.shapes.large,
            color = WashiLight,
            border = BorderStroke(1.dp, Kincha),
            shadowElevation = 12.dp,
        ) {
            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "対戦終了",
                    style = MaterialTheme.typography.labelLarge,
                    color = UsuzumiText,
                    letterSpacing = 0.3.em,
                )
                Spacer(Modifier.height(14.dp))
                OutcomeStamp(winner)
                Spacer(Modifier.height(16.dp))
                Text(
                    text = when (winner) {
                        Side.PLAYER -> "あなたの勝ちです"
                        Side.CPU -> "あなたの負けです"
                        null -> "引き分けです"
                    },
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleMedium,
                    color = Sumi,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = finishReason(state, winner),
                    style = MaterialTheme.typography.bodySmall,
                    color = UsuzumiText,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(18.dp))
                StatsTable(state)
                Spacer(Modifier.height(22.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SecondaryButton("ホームへ", onExit, Modifier.weight(1f))
                    PrimaryButton("もう一度", onRestart, Modifier.weight(1f))
                }
            }
        }
    }
}

/** 朱肉の印のような勝敗の文字。 */
@Composable
private fun OutcomeStamp(winner: Side?) {
    val (label, color) = when (winner) {
        Side.PLAYER -> "勝ち" to Shu
        Side.CPU -> "負け" to Kon
        null -> "引き分け" to Sumi
    }
    Box(
        Modifier
            .rotate(-5f)
            .border(3.dp, color, MaterialTheme.shapes.medium)
            .padding(3.dp)
            .border(1.dp, color, MaterialTheme.shapes.small)
            .padding(horizontal = 22.dp, vertical = 4.dp),
    ) {
        Text(
            text = label,
            fontSize = 46.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.12.em,
            color = color,
            style = MaterialTheme.typography.displaySmall,
        )
    }
}

internal fun finishReason(state: MatchUiState, winner: Side?): String {
    if (state.config.ruleMode == RuleMode.SIMPLE) {
        return "取った札　あなた ${state.playerTakenCount} 枚 ・ 相手 ${state.cpuTakenCount} 枚"
    }
    return when {
        winner == Side.PLAYER && state.playerCards.isEmpty() -> "自陣の札が先になくなりました"
        winner == Side.CPU && state.cpuCards.isEmpty() -> "相手の陣の札が先になくなりました"
        else -> "すべての札を読み終えました"
    }
}

@Composable
private fun StatsTable(state: MatchUiState) {
    val competitive = state.config.ruleMode == RuleMode.COMPETITIVE
    Column(Modifier.fillMaxWidth()) {
        StatsRow("", "あなた", "相手", header = true)
        HorizontalDivider(color = Kincha.copy(alpha = 0.5f))
        StatsRow("取った札", "${state.playerTakenCount} 枚", "${state.cpuTakenCount} 枚")
        HorizontalDivider(color = Kincha.copy(alpha = 0.25f))
        StatsRow("お手つき", "${state.playerFaultCount} 回", "${state.cpuFaultCount} 回")
        if (competitive) {
            HorizontalDivider(color = Kincha.copy(alpha = 0.25f))
            StatsRow("残りの札", "${state.playerCards.size} 枚", "${state.cpuCards.size} 枚")
        }
    }
}

@Composable
private fun StatsRow(label: String, player: String, cpu: String, header: Boolean = false) {
    val valueStyle = if (header) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyLarge
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1.1f),
            style = MaterialTheme.typography.bodyMedium,
            color = UsuzumiText,
        )
        Text(
            text = player,
            modifier = Modifier.weight(1f),
            style = valueStyle,
            color = if (header) Kon else Sumi,
            textAlign = TextAlign.Center,
        )
        Text(
            text = cpu,
            modifier = Modifier.weight(1f),
            style = valueStyle,
            color = Sumi,
            textAlign = TextAlign.Center,
        )
    }
}
