package io.github.abonsan.hyakunin.game

import androidx.lifecycle.ViewModel
import io.github.abonsan.hyakunin.data.Poems
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

// 暫定スタブ。対戦ロジック実装時に置き換える（コンストラクタと公開APIは変更しない）。
class MatchViewModel(
    config: GameConfig,
    random: Random = Random.Default,
) : ViewModel(), MatchActions {

    private val _uiState = MutableStateFlow(
        MatchUiState(
            config = config,
            phase = MatchPhase.Waiting,
            playerCards = Poems.all.take(2).map { FieldCard(it.no, it.shimoKana) },
            cpuCards = Poems.all.drop(2).take(1).map { FieldCard(it.no, it.shimoKana) },
            reading = null,
            lastResult = null,
            playerLockedOut = false,
            pendingPlayerSends = 0,
            playerTakenCount = 0,
            cpuTakenCount = 0,
            playerFaultCount = 0,
            cpuFaultCount = 0,
            readsDone = 0,
            totalReads = 2 * GameConfig.CARDS_PER_SIDE + config.emptyCardCount,
            isPaused = false,
        ),
    )
    val uiState: StateFlow<MatchUiState> = _uiState.asStateFlow()

    override fun onCardTap(side: Side, poemNo: Int) = Unit

    override fun onNext() = Unit

    override fun onRestart() = Unit

    override fun setPaused(paused: Boolean) = Unit
}
