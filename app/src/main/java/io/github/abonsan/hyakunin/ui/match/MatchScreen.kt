package io.github.abonsan.hyakunin.ui.match

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.abonsan.hyakunin.R
import io.github.abonsan.hyakunin.game.FieldCard
import io.github.abonsan.hyakunin.game.MatchActions
import io.github.abonsan.hyakunin.game.MatchPhase
import io.github.abonsan.hyakunin.game.MatchUiState
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.game.Side
import io.github.abonsan.hyakunin.ui.components.WafuDialog
import io.github.abonsan.hyakunin.ui.theme.Kincha
import io.github.abonsan.hyakunin.ui.theme.Kon
import io.github.abonsan.hyakunin.ui.theme.ShuDeep
import io.github.abonsan.hyakunin.ui.theme.Sumi
import io.github.abonsan.hyakunin.ui.theme.UsuzumiText
import io.github.abonsan.hyakunin.ui.theme.Washi
import io.github.abonsan.hyakunin.ui.theme.WashiDeep

/**
 * 対戦画面。上に敵陣、中央に読み上げ（または結果）、下に自陣を置く。
 * 表示は [state] だけから決め、操作は [actions] に渡す。
 *
 * @param pauseDialogVisible 一時停止のダイアログを出すか。別のダイアログを出している間は false にする。
 */
@Composable
fun MatchScreen(
    state: MatchUiState,
    actions: MatchActions,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
    pauseDialogVisible: Boolean = state.isPaused,
) {
    // この読みで自分が触れた札の陣（お手つきか、同じ陣の別の札かを見分ける）
    var touch by rememberSaveable(stateSaver = CardTouchSaver) { mutableStateOf<CardTouch?>(null) }
    val phase = state.phase
    val readNumber = state.reading?.readNumber
    val touchedSide = touch?.takeIf { it.readNumber == readNumber }?.side

    val canTake = phase == MatchPhase.Reading && !state.playerLockedOut && !state.isPaused
    val choosingSend = phase == MatchPhase.Result && state.pendingPlayerSends > 0 && !state.isPaused
    val lockedOut = phase == MatchPhase.Reading && state.playerLockedOut

    fun take(side: Side, card: FieldCard) {
        touch = CardTouch(readNumber ?: 0, side)
        actions.onCardTap(side, card.poemNo)
    }

    BoxWithConstraints(modifier.fillMaxSize().background(Washi)) {
        val cardWidth = fieldCardWidth(maxWidth, maxHeight)
        Column(Modifier.fillMaxSize()) {
            MatchTopBar(
                state = state,
                onPause = { actions.setPaused(true) },
                modifier = Modifier.windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
            )
            FieldArea(
                cards = state.cpuCards,
                side = Side.CPU,
                cardWidth = cardWidth,
                upsideDown = state.config.flipOpponentCards,
                dimmed = lockedOut,
                selectable = false,
                onCardTap = if (canTake) { card -> take(Side.CPU, card) } else null,
            )
            CenterArea(
                state = state,
                notice = lockoutNotice(state, touchedSide),
                onNext = actions::onNext,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            )
            FieldArea(
                cards = state.playerCards,
                side = Side.PLAYER,
                cardWidth = cardWidth,
                upsideDown = false,
                dimmed = lockedOut,
                selectable = choosingSend,
                onCardTap = when {
                    canTake -> { card -> take(Side.PLAYER, card) }
                    choosingSend -> { card -> actions.onCardTap(Side.PLAYER, card.poemNo) }
                    else -> null
                },
                contentInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
            )
        }
        if (phase is MatchPhase.Finished) {
            FinishedOverlay(
                state = state,
                winner = phase.winner,
                onRestart = actions::onRestart,
                onExit = onExit,
            )
        }
    }

    if (pauseDialogVisible && phase !is MatchPhase.Finished) {
        WafuDialog(
            title = "一時停止中",
            message = "対戦を止めています。再開すると続きから読みます。",
            confirmLabel = "再開",
            onConfirm = { actions.setPaused(false) },
            dismissLabel = "やめる",
            onDismiss = onExit,
            onDismissRequest = { actions.setPaused(false) },
        )
    }
}

/** 何枚目の読みで、どちらの陣の札に触れたか。 */
private data class CardTouch(val readNumber: Int, val side: Side)

private val CardTouchSaver = listSaver<CardTouch?, Any>(
    save = { touch -> if (touch == null) emptyList() else listOf(touch.readNumber, touch.side.name) },
    restore = { CardTouch(it[0] as Int, Side.valueOf(it[1] as String)) },
)

@Composable
private fun CenterArea(
    state: MatchUiState,
    notice: LockoutNotice?,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val content = when (state.phase) {
        MatchPhase.Waiting -> CenterContent.Waiting
        MatchPhase.Reading -> if (state.reading != null) CenterContent.Reading else CenterContent.Waiting
        MatchPhase.Result, is MatchPhase.Finished ->
            if (state.lastResult != null) CenterContent.Result else CenterContent.Waiting
    }
    Crossfade(targetState = content, modifier = modifier, animationSpec = tween(220), label = "center") {
        val reading = state.reading
        val result = state.lastResult
        when {
            it == CenterContent.Reading && reading != null -> ReadingPanel(reading, notice)
            it == CenterContent.Result && result != null -> ResultPanel(
                result = result,
                reading = reading,
                ruleMode = state.config.ruleMode,
                pendingSends = if (state.phase == MatchPhase.Result) state.pendingPlayerSends else 0,
                onNext = if (state.phase == MatchPhase.Result) onNext else null,
            )
            else -> WaitingPanel(
                nextNumber = (state.readsDone + 1).coerceAtMost(state.totalReads),
                totalReads = state.totalReads,
                isFirst = state.readsDone == 0,
            )
        }
    }
}

private enum class CenterContent { Waiting, Reading, Result }

/**
 * 上部の成績。左から一時停止、自分、相手、読みの数。
 * 競技かるたは陣に残っている札、簡易は取った札の枚数を大きく出す。
 */
@Composable
private fun MatchTopBar(
    state: MatchUiState,
    onPause: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val competitive = state.config.ruleMode == RuleMode.COMPETITIVE
    val caption = if (competitive) "残り" else "取り札"
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPause, enabled = state.phase !is MatchPhase.Finished) {
                Icon(
                    painter = painterResource(R.drawable.ic_pause),
                    contentDescription = "一時停止",
                    tint = Sumi,
                )
            }
            ScoreBlock(
                name = "あなた",
                mark = Kon,
                caption = caption,
                value = if (competitive) state.playerCards.size else state.playerTakenCount,
                faults = state.playerFaultCount,
                modifier = Modifier.weight(1f),
            )
            TopBarDivider()
            ScoreBlock(
                name = "相手（${state.config.cpuLevel.label}）",
                mark = ShuDeep,
                caption = caption,
                value = if (competitive) state.cpuCards.size else state.cpuTakenCount,
                faults = state.cpuFaultCount,
                modifier = Modifier.weight(1f),
            )
            TopBarDivider()
            ReadCountBlock(readCount = readCount(state), totalReads = state.totalReads)
        }
        ReadProgressLine(
            progress = if (state.totalReads > 0) state.readsDone / state.totalReads.toFloat() else 0f,
        )
    }
}

/** 何枚目まで読んだか。読み上げ中はその札も数える。 */
internal fun readCount(state: MatchUiState): Int = if (state.phase == MatchPhase.Reading) {
    maxOf(state.readsDone, state.reading?.readNumber ?: 0)
} else {
    state.readsDone
}

@Composable
private fun TopBarDivider() {
    Box(
        Modifier
            .padding(vertical = 4.dp)
            .fillMaxHeight()
            .width(1.dp)
            .background(Kincha.copy(alpha = 0.35f)),
    )
}

@Composable
private fun ScoreBlock(
    name: String,
    mark: Color,
    caption: String,
    value: Int,
    faults: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .padding(horizontal = 10.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(6.dp)
                    .background(mark, CircleShape),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = UsuzumiText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row {
            Text(
                text = caption,
                modifier = Modifier.alignByBaseline(),
                style = MaterialTheme.typography.labelMedium,
                color = UsuzumiText,
            )
            Text(
                text = " $value",
                modifier = Modifier.alignByBaseline(),
                style = MaterialTheme.typography.titleLarge,
                color = Sumi,
            )
            Text(
                text = " 枚",
                modifier = Modifier.alignByBaseline(),
                style = MaterialTheme.typography.labelMedium,
                color = Sumi,
            )
        }
        Text(
            text = "お手つき $faults",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (faults > 0) FontWeight.Bold else null,
            color = if (faults > 0) ShuDeep else UsuzumiText,
            maxLines = 1,
        )
    }
}

@Composable
private fun ReadCountBlock(readCount: Int, totalReads: Int) {
    Column(
        Modifier
            .padding(start = 10.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.End,
    ) {
        Text("読み", style = MaterialTheme.typography.labelMedium, color = UsuzumiText)
        Row {
            Text(
                text = "$readCount",
                modifier = Modifier.alignByBaseline(),
                style = MaterialTheme.typography.titleLarge,
                color = Sumi,
            )
            Text(
                text = " / $totalReads",
                modifier = Modifier.alignByBaseline(),
                style = MaterialTheme.typography.labelMedium,
                color = UsuzumiText,
            )
        }
    }
}

/** 読み終えた札の割合を示す細い線。 */
@Composable
private fun ReadProgressLine(progress: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(WashiDeep),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(Kincha),
        )
    }
}
