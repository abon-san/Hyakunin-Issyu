package io.github.abonsan.hyakunin.ui.match

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
 * 対戦画面。表示は [state] だけから決め、操作は [actions] に渡す。
 *
 * 縦長の画面では上に敵陣、中央に読み上げ（または結果）、下に自陣を置く。
 * 横に広い画面では、左に成績の帯と読み上げの欄、右に敵陣（上）と自陣（下）を重ねて置く。
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

    val board = BoardState(
        state = state,
        lockedOut = lockedOut,
        choosingSend = choosingSend,
        onCpuCardTap = if (canTake) { card -> take(Side.CPU, card) } else null,
        onPlayerCardTap = when {
            canTake -> { card -> take(Side.PLAYER, card) }
            choosingSend -> { card -> actions.onCardTap(Side.PLAYER, card.poemNo) }
            else -> null
        },
    )
    val notice = lockoutNotice(state, touchedSide)

    BoxWithConstraints(modifier.fillMaxSize().background(Washi)) {
        // 配置はシステムバーを除いた広さで決める
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val direction = LocalLayoutDirection.current
        val safeWidth = maxWidth - insets.calculateStartPadding(direction) - insets.calculateEndPadding(direction)
        val safeHeight = maxHeight - insets.calculateTopPadding() - insets.calculateBottomPadding()
        val landscape = useLandscapeBoard(safeWidth, safeHeight)
        val center: @Composable (Modifier) -> Unit = { centerModifier ->
            CenterArea(
                state = state,
                notice = notice,
                onNext = actions::onNext,
                onStartReading = actions::onStartReading,
                fieldsAside = landscape,
                modifier = centerModifier,
            )
        }
        if (landscape) {
            LandscapeBoard(
                board = board,
                metrics = landscapeMetrics(safeWidth, safeHeight),
                insets = insets,
                height = maxHeight,
                onPause = { actions.setPaused(true) },
                center = center,
            )
        } else {
            PortraitBoard(
                board = board,
                cardWidth = fieldCardWidth(maxWidth, maxHeight),
                onPause = { actions.setPaused(true) },
                center = center,
            )
        }
        if (phase is MatchPhase.Finished) {
            FinishedOverlay(
                state = state,
                winner = phase.winner,
                onRestart = actions::onRestart,
                onExit = onExit,
                wide = landscape,
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

/** 両陣の表示と、札に触れたときの処理。 */
private class BoardState(
    val state: MatchUiState,
    val lockedOut: Boolean,
    val choosingSend: Boolean,
    val onCpuCardTap: ((FieldCard) -> Unit)?,
    val onPlayerCardTap: ((FieldCard) -> Unit)?,
)

/** 何枚目の読みで、どちらの陣の札に触れたか。 */
private data class CardTouch(val readNumber: Int, val side: Side)

private val CardTouchSaver = listSaver<CardTouch?, Any>(
    save = { touch -> if (touch == null) emptyList() else listOf(touch.readNumber, touch.side.name) },
    restore = { CardTouch(it[0] as Int, Side.valueOf(it[1] as String)) },
)

/** 縦長の画面: 上に成績と敵陣、中央に読み上げ、下に自陣。 */
@Composable
private fun PortraitBoard(
    board: BoardState,
    cardWidth: Dp,
    onPause: () -> Unit,
    center: @Composable (Modifier) -> Unit,
) {
    val state = board.state
    Column(Modifier.fillMaxSize()) {
        MatchTopBar(
            state = state,
            onPause = onPause,
            modifier = Modifier.windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            ),
        )
        FieldArea(
            cards = state.cpuCards,
            side = Side.CPU,
            cardWidth = cardWidth,
            upsideDown = state.config.flipOpponentCards,
            dimmed = board.lockedOut,
            selectable = false,
            onCardTap = board.onCpuCardTap,
        )
        center(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        )
        FieldArea(
            cards = state.playerCards,
            side = Side.PLAYER,
            cardWidth = cardWidth,
            upsideDown = false,
            dimmed = board.lockedOut,
            selectable = board.choosingSend,
            onCardTap = board.onPlayerCardTap,
            contentPadding = WindowInsets.safeDrawing
                .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal)
                .asPaddingValues(),
        )
    }
}

/**
 * 横に広い画面: 左端に成績の帯、その右に読み上げの欄、右側に敵陣（上）と自陣（下）を重ねる。
 * 帯と畳はシステムバーの下まで敷き、文字と札はその内側（[insets] の内側）に置く。
 *
 * @param metrics システムバーを除いた広さで決めた、帯・欄・場の幅と札の幅。
 * @param height 画面全体の高さ（システムバーを含む）。
 */
@Composable
private fun LandscapeBoard(
    board: BoardState,
    metrics: LandscapeMetrics,
    insets: PaddingValues,
    height: Dp,
    onPause: () -> Unit,
    center: @Composable (Modifier) -> Unit,
) {
    val state = board.state
    val direction = LocalLayoutDirection.current
    val start = insets.calculateStartPadding(direction)
    val end = insets.calculateEndPadding(direction)
    val top = insets.calculateTopPadding()
    val bottom = insets.calculateBottomPadding()
    val cardWidth = metrics.cardWidth

    Row(Modifier.fillMaxSize()) {
        ScoreSidebar(
            state = state,
            onPause = onPause,
            modifier = Modifier
                .width(metrics.sidebarWidth + start)
                .fillMaxHeight(),
            contentPadding = PaddingValues(start = start, top = top, bottom = bottom),
        )
        center(
            Modifier
                .width(metrics.panelWidth)
                .fillMaxHeight()
                .padding(top = top, bottom = bottom),
        )
        // 札の段の数に合わせて両陣の高さを決め、余った高さは半分ずつ足す
        val cpuHeight = fieldHeight(state.cpuCards.size, cardWidth) + top
        val playerHeight = fieldHeight(state.playerCards.size, cardWidth) + bottom
        val spare = (height - cpuHeight - playerHeight - FieldSeparator).coerceAtLeast(0.dp)
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            FieldArea(
                cards = state.cpuCards,
                side = Side.CPU,
                cardWidth = cardWidth,
                upsideDown = state.config.flipOpponentCards,
                dimmed = board.lockedOut,
                selectable = false,
                onCardTap = board.onCpuCardTap,
                modifier = Modifier.height(cpuHeight + spare / 2),
                contentPadding = PaddingValues(top = top, end = end),
            )
            Spacer(Modifier.height(FieldSeparator))
            FieldArea(
                cards = state.playerCards,
                side = Side.PLAYER,
                cardWidth = cardWidth,
                upsideDown = false,
                dimmed = board.lockedOut,
                selectable = board.choosingSend,
                onCardTap = board.onPlayerCardTap,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(bottom = bottom, end = end),
            )
        }
    }
}

@Composable
private fun CenterArea(
    state: MatchUiState,
    notice: LockoutNotice?,
    onNext: () -> Unit,
    onStartReading: () -> Unit,
    fieldsAside: Boolean,
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
            it == CenterContent.Reading && reading != null -> ReadingPanel(reading, state.config.verseStyle, notice)
            it == CenterContent.Result && result != null -> ResultPanel(
                result = result,
                reading = reading,
                ruleMode = state.config.ruleMode,
                verseStyle = state.config.verseStyle,
                pendingSends = if (state.phase == MatchPhase.Result) state.pendingPlayerSends else 0,
                onNext = if (state.phase == MatchPhase.Result) onNext else null,
                fieldsAside = fieldsAside,
            )
            else -> WaitingPanel(
                info = waitingInfo(state),
                onStartReading = onStartReading,
                fieldsAside = fieldsAside,
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
    val caption = scoreCaption(state)
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(start = 4.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PauseButton(state, onPause)
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
        ReadProgressLine(progress = readProgress(state))
    }
}

/** 成績の数の見出し（競技かるたは残りの札、簡易は取った札）。 */
private fun scoreCaption(state: MatchUiState): String =
    if (state.config.ruleMode == RuleMode.COMPETITIVE) "残り" else "取り札"

private fun readProgress(state: MatchUiState): Float =
    if (state.totalReads > 0) state.readsDone / state.totalReads.toFloat() else 0f

/** 何枚目まで読んだか。読み上げ中はその札も数える。 */
internal fun readCount(state: MatchUiState): Int = if (state.phase == MatchPhase.Reading) {
    maxOf(state.readsDone, state.reading?.readNumber ?: 0)
} else {
    state.readsDone
}

@Composable
private fun PauseButton(state: MatchUiState, onPause: () -> Unit) {
    IconButton(onClick = onPause, enabled = state.phase !is MatchPhase.Finished) {
        Icon(
            painter = painterResource(R.drawable.ic_pause),
            contentDescription = "一時停止",
            tint = Sumi,
        )
    }
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
            ScoreMark(mark)
            Spacer(Modifier.width(5.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = UsuzumiText,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ScoreValue(caption, value)
        FaultCount(faults)
    }
}

@Composable
private fun ScoreMark(color: Color) {
    Box(
        Modifier
            .size(6.dp)
            .background(color, CircleShape),
    )
}

/** 「残り 5 枚」。数を大きく書く。 */
@Composable
private fun ScoreValue(caption: String, value: Int) {
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
}

@Composable
private fun FaultCount(faults: Int) {
    Text(
        text = "お手つき $faults",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (faults > 0) FontWeight.Bold else null,
        color = if (faults > 0) ShuDeep else UsuzumiText,
        maxLines = 1,
    )
}

@Composable
private fun ReadCountBlock(readCount: Int, totalReads: Int, modifier: Modifier = Modifier) {
    Column(
        modifier
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

/**
 * 横に広い画面の成績の帯。上から一時停止、相手（敵陣の横）、読みの数、自分（自陣の横）。
 * 右の縁は読み終えた札の割合を示す線にする。
 */
@Composable
private fun ScoreSidebar(
    state: MatchUiState,
    onPause: () -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val competitive = state.config.ruleMode == RuleMode.COMPETITIVE
    val caption = scoreCaption(state)
    Row(modifier) {
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(contentPadding)
                .padding(vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            PauseButton(state, onPause)
            SideScore(
                name = "相手",
                detail = state.config.cpuLevel.label,
                mark = ShuDeep,
                caption = caption,
                value = if (competitive) state.cpuCards.size else state.cpuTakenCount,
                faults = state.cpuFaultCount,
            )
            SideReadCount(readCount = readCount(state), totalReads = state.totalReads)
            SideScore(
                name = "あなた",
                detail = null,
                mark = Kon,
                caption = caption,
                value = if (competitive) state.playerCards.size else state.playerTakenCount,
                faults = state.playerFaultCount,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        VerticalProgressLine(readProgress(state))
    }
}

@Composable
private fun SideScore(
    name: String,
    detail: String?,
    mark: Color,
    caption: String,
    value: Int,
    faults: Int,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .padding(horizontal = 6.dp)
            .semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ScoreMark(mark)
            Spacer(Modifier.width(5.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = UsuzumiText,
                maxLines = 1,
            )
        }
        if (detail != null) {
            Text(
                text = "（$detail）",
                style = MaterialTheme.typography.labelSmall,
                color = UsuzumiText,
                maxLines = 1,
            )
        }
        // 狭い帯なので、見出し（残り・取り札）は数の上に置く（2桁の数でも折り返さない）
        Spacer(Modifier.height(2.dp))
        Text(caption, style = MaterialTheme.typography.labelMedium, color = UsuzumiText, maxLines = 1)
        Row {
            Text(
                text = "$value",
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
        FaultCount(faults)
    }
}

@Composable
private fun SideReadCount(readCount: Int, totalReads: Int) {
    Column(
        Modifier.semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .width(28.dp)
                .height(1.dp)
                .background(Kincha.copy(alpha = 0.5f)),
        )
        Spacer(Modifier.height(6.dp))
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
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .width(28.dp)
                .height(1.dp)
                .background(Kincha.copy(alpha = 0.5f)),
        )
    }
}

/** 読み終えた札の割合を、上から下へ伸びる細い線で示す。 */
@Composable
private fun VerticalProgressLine(progress: Float) {
    Box(
        Modifier
            .fillMaxHeight()
            .width(2.dp)
            .background(WashiDeep),
    ) {
        Box(
            Modifier
                .fillMaxHeight(progress.coerceIn(0f, 1f))
                .fillMaxWidth()
                .background(Kincha),
        )
    }
}
