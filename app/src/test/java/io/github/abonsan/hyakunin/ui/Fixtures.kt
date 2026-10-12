package io.github.abonsan.hyakunin.ui

import io.github.abonsan.hyakunin.data.Poem
import io.github.abonsan.hyakunin.data.Poems
import io.github.abonsan.hyakunin.game.FieldCard
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.LeadInState
import io.github.abonsan.hyakunin.game.MatchActions
import io.github.abonsan.hyakunin.game.MatchPhase
import io.github.abonsan.hyakunin.game.MatchUiState
import io.github.abonsan.hyakunin.game.ReadResult
import io.github.abonsan.hyakunin.game.ReadingState
import io.github.abonsan.hyakunin.game.Side
import io.github.abonsan.hyakunin.game.Transfer
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.game.kanaCount

/** スクリーンショットとテスト用の偽の対戦状態。歌はアプリの100首から取る。 */
internal object Fixtures {
    fun poem(no: Int): Poem = Poems.byNo(no)

    fun card(no: Int, isNew: Boolean = false) = FieldCard(no, poem(no).shimoKana, isNew)

    fun cards(vararg nos: Int): List<FieldCard> = nos.map { card(it) }

    fun reading(no: Int, readNumber: Int, totalReads: Int, revealed: Int): ReadingState {
        val poem = poem(no)
        val kami = kanaCount(poem.kamiYomi)
        val total = kami + kanaCount(poem.shimoYomi)
        return ReadingState(
            poem = poem,
            readNumber = readNumber,
            totalReads = totalReads,
            revealedKana = revealed.coerceAtMost(total),
            kamiKanaCount = kami,
            totalKanaCount = total,
        )
    }

    fun result(
        no: Int,
        kimarijiLength: Int,
        revealedAtTake: Int?,
        takenBy: Side?,
        takenFrom: Side?,
        message: String,
        isEmptyCard: Boolean = false,
        faults: Set<Side> = emptySet(),
        safeMisses: Set<Side> = emptySet(),
        transfers: List<Transfer> = emptyList(),
    ) = ReadResult(
        poem = poem(no),
        isEmptyCard = isEmptyCard,
        kimarijiLength = kimarijiLength,
        revealedAtTake = revealedAtTake,
        takenBy = takenBy,
        takenFrom = takenFrom,
        faults = faults,
        safeMisses = safeMisses,
        transfers = transfers,
        message = message,
    )

    /**
     * 対戦の状態。[config] の既定は、これまでのスクリーンショットと見比べられるよう、
     * 句の表示を読み（[VerseStyle.READING]）にする（アプリの既定は歴史的仮名遣い）。
     */
    fun state(
        config: GameConfig = GameConfig(verseStyle = VerseStyle.READING),
        phase: MatchPhase = MatchPhase.Reading,
        player: List<FieldCard> = cards(1, 17, 33, 61, 87),
        cpu: List<FieldCard> = cards(12, 23, 46, 70, 99),
        reading: ReadingState? = null,
        lastResult: ReadResult? = null,
        playerLockedOut: Boolean = false,
        pendingPlayerSends: Int = 0,
        playerTaken: Int = 0,
        cpuTaken: Int = 0,
        playerFaults: Int = 0,
        cpuFaults: Int = 0,
        readsDone: Int = 0,
        isPaused: Boolean = false,
        waitRemainingMillis: Long? = null,
        isMemorizing: Boolean = false,
        leadIn: LeadInState? = null,
    ) = MatchUiState(
        config = config,
        phase = phase,
        playerCards = player,
        cpuCards = cpu,
        reading = reading,
        lastResult = lastResult,
        playerLockedOut = playerLockedOut,
        pendingPlayerSends = pendingPlayerSends,
        playerTakenCount = playerTaken,
        cpuTakenCount = cpuTaken,
        playerFaultCount = playerFaults,
        cpuFaultCount = cpuFaults,
        readsDone = readsDone,
        totalReads = 2 * GameConfig.CARDS_PER_SIDE + config.emptyCardCount,
        isPaused = isPaused,
        waitRemainingMillis = waitRemainingMillis,
        isMemorizing = isMemorizing,
        leadIn = leadIn,
    )
}

/** 何もしない操作。 */
internal object NoopActions : MatchActions {
    override fun onCardTap(side: Side, poemNo: Int) = Unit

    override fun onNext() = Unit

    override fun onRestart() = Unit

    override fun setPaused(paused: Boolean) = Unit
}

/** 呼ばれた操作を記録する。 */
internal class RecordingActions : MatchActions {
    val taps = mutableListOf<Pair<Side, Int>>()
    var nextCount = 0
    var startReadingCount = 0
    var restartCount = 0
    val pauses = mutableListOf<Boolean>()

    override fun onCardTap(side: Side, poemNo: Int) {
        taps += side to poemNo
    }

    override fun onNext() {
        nextCount++
    }

    override fun onStartReading() {
        startReadingCount++
    }

    override fun onRestart() {
        restartCount++
    }

    override fun setPaused(paused: Boolean) {
        pauses += paused
    }
}
