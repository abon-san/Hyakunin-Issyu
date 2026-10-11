package io.github.abonsan.hyakunin.game

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
class MatchViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private val viewModels = mutableListOf<MatchViewModel>()

    /** layoutEngine の既定の待ち（札を覚える時間 10秒 / 読みの間 2秒）。 */
    private val memorize = MemorizeTime.SEC_10.millis
    private val interval = ReadInterval.SEC_2.millis

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        viewModels.forEach { it.viewModelScope.cancel() }
        Dispatchers.resetMain()
    }

    private class Harness(val vm: MatchViewModel, val engines: List<MatchEngine>) {
        val engine: MatchEngine get() = engines.last()
        val state: MatchUiState get() = vm.uiState.value
    }

    /** 仮想時間で動く ViewModel。[factory] が作った MatchEngine を [Harness.engines] に残す。 */
    private fun harness(
        clock: () -> Long = { scheduler.currentTime },
        factory: () -> MatchEngine,
    ): Harness {
        val engines = mutableListOf<MatchEngine>()
        val vm = MatchViewModel(
            engineFactory = { factory().also { engines += it } },
            clock = clock,
            tickMillis = 20L,
        )
        viewModels += vm
        return Harness(vm, engines)
    }

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun layout(
        order: List<Int>,
        mode: RuleMode = RuleMode.COMPETITIVE,
        player: List<Int> = listOf(1, 2, 3, 4, 5),
        cpu: List<Int> = listOf(6, 7, 8, 9, 10),
        profile: CpuProfile = SLOW_CPU,
        readStartMode: ReadStartMode = ReadStartMode.AUTO,
    ) = {
        layoutEngine(
            order = order,
            mode = mode,
            player = player,
            cpu = cpu,
            profile = profile,
            readStartMode = readStartMode,
        )
    }

    @Test
    fun startsInWaitingWithTheDealtCards() {
        val h = harness(factory = layout(order = listOf(1, 6)))
        val st = h.state
        assertEquals(MatchPhase.Waiting, st.phase)
        assertNull(st.reading)
        assertNull(st.lastResult)
        assertEquals(listOf(1, 2, 3, 4, 5), st.playerNos)
        assertEquals(listOf(6, 7, 8, 9, 10), st.cpuNos)
        assertEquals(2, st.totalReads)
        assertEquals(0, st.readsDone)
        assertFalse(st.isPaused)
        assertFalse(st.playerLockedOut)
        assertEquals(0, st.pendingPlayerSends)
        assertEquals(0, st.playerTakenCount + st.cpuTakenCount + st.playerFaultCount + st.cpuFaultCount)
        assertEquals(memorize, st.waitRemainingMillis)
        assertTrue(st.isMemorizing)
    }

    @Test
    fun readingProgressesWithTime() {
        val h = harness(factory = layout(order = listOf(1, 6)))
        advance(memorize - 20L)
        assertEquals(MatchPhase.Waiting, h.state.phase)
        advance(20L)
        assertEquals(MatchPhase.Reading, h.state.phase)
        assertEquals(0, h.state.reading!!.revealedKana)
        assertEquals(1, h.state.reading!!.poem.no)

        // 1文字 100ms
        advance(300L)
        assertEquals(3, h.state.reading!!.revealedKana)
        advance(300L)
        assertEquals(6, h.state.reading!!.revealedKana)
        // 句の区切りで 100ms の間 → 7文字目は 800ms
        advance(100L)
        assertEquals(6, h.state.reading!!.revealedKana)
        advance(100L)
        assertEquals(7, h.state.reading!!.revealedKana)
        assertEquals(16, h.state.reading!!.kamiKanaCount)
        assertEquals(26, h.state.reading!!.totalKanaCount)
    }

    @Test
    fun pausingStopsTimeAndIgnoresTaps() {
        val h = harness(factory = layout(order = listOf(1, 6)))
        advance(memorize + 400L)
        assertEquals(4, h.state.reading!!.revealedKana)
        val engineTime = h.engine.nowMillis

        h.vm.setPaused(true)
        assertTrue(h.state.isPaused)
        advance(60_000L)
        assertEquals(4, h.state.reading!!.revealedKana)
        assertEquals(MatchPhase.Reading, h.state.phase)
        assertEquals(engineTime, h.engine.nowMillis)

        // 一時停止中のタップ・次へは無視する
        h.vm.onCardTap(Side.PLAYER, 1)
        h.vm.onCardTap(Side.CPU, 6)
        h.vm.onNext()
        assertEquals(MatchPhase.Reading, h.state.phase)
        assertEquals(0, h.state.playerTakenCount)
        assertEquals(0, h.state.playerFaultCount)
        assertFalse(h.state.playerLockedOut)

        h.vm.setPaused(true)
        h.vm.setPaused(false)
        assertFalse(h.state.isPaused)
        assertEquals(4, h.state.reading!!.revealedKana)
        advance(1000L)
        // 再開後は一時停止していた間を除いて進む
        assertEquals(engineTime + 1000L, h.engine.nowMillis)
        // 読み始めから 1400ms（句の区切り 2 つぶんの間を含む）で 12 文字目
        assertEquals(12, h.state.reading!!.revealedKana)
    }

    @Test
    fun pauseDuringWaitingDelaysTheStart() {
        val h = harness(factory = layout(order = listOf(1)))
        advance(memorize - 500L)
        h.vm.setPaused(true)
        advance(30_000L)
        assertEquals(MatchPhase.Waiting, h.state.phase)
        h.vm.setPaused(false)
        advance(480L)
        assertEquals(MatchPhase.Waiting, h.state.phase)
        advance(20L)
        assertEquals(MatchPhase.Reading, h.state.phase)
    }

    @Test
    fun cpuTakesTheCardWhenPlayerDoesNothing() {
        val h = harness(factory = layout(order = listOf(6, 11), profile = steadyCpu(300L)))
        advance(memorize)
        assertEquals(MatchPhase.Reading, h.state.phase)
        // 決まり字 200ms + 反応 300ms
        advance(480L)
        assertEquals(MatchPhase.Reading, h.state.phase)
        advance(20L)

        val st = h.state
        assertEquals(MatchPhase.Result, st.phase)
        assertEquals(Side.CPU, st.lastResult!!.takenBy)
        assertEquals(5, st.lastResult!!.revealedAtTake)
        assertEquals(5, st.reading!!.revealedKana)
        assertEquals(1, st.cpuTakenCount)
        assertEquals(1, st.readsDone)
        assertEquals(listOf(7, 8, 9, 10), st.cpuNos)
    }

    @Test
    fun playerTakesTheCardBeforeTheCpu() {
        val h = harness(factory = layout(order = listOf(1, 11), profile = steadyCpu(300L)))
        advance(memorize + 400L)
        h.vm.onCardTap(Side.PLAYER, 1)

        val st = h.state
        assertEquals(MatchPhase.Result, st.phase)
        assertEquals(Side.PLAYER, st.lastResult!!.takenBy)
        assertEquals(4, st.lastResult!!.revealedAtTake)
        assertEquals(1, st.playerTakenCount)
        assertEquals(listOf(2, 3, 4, 5), st.playerNos)

        // Result 中は文字も進まず、CPU も動かない
        advance(30_000L)
        assertEquals(MatchPhase.Result, h.state.phase)
        assertEquals(4, h.state.reading!!.revealedKana)
        assertEquals(0, h.state.cpuTakenCount)
    }

    @Test
    fun wrongTouchLocksOutThePlayer() {
        val h = harness(factory = layout(order = listOf(1, 11)))
        advance(memorize + 300L)
        h.vm.onCardTap(Side.CPU, 6)
        assertTrue(h.state.playerLockedOut)
        assertEquals(1, h.state.playerFaultCount)
        h.vm.onCardTap(Side.PLAYER, 1)
        assertEquals(MatchPhase.Reading, h.state.phase)

        // CPU が自陣の札を取り、お手つきの罰とあわせて2枚送られる
        advance(5_000L)
        val st = h.state
        assertEquals(MatchPhase.Result, st.phase)
        assertEquals(Side.CPU, st.lastResult!!.takenBy)
        assertFalse(st.playerLockedOut)
        assertEquals(6, st.playerCards.size)
        assertEquals(2, st.lastResult!!.transfers.size)
    }

    @Test
    fun nextMovesOnAndWaitsForSendChoices() {
        val h = harness(factory = layout(order = listOf(6, 1)))
        advance(memorize + 200L)
        h.vm.onCardTap(Side.CPU, 6)
        assertEquals(1, h.state.pendingPlayerSends)

        // 送る札を選ぶまで次へは進めない
        h.vm.onNext()
        assertEquals(MatchPhase.Result, h.state.phase)
        h.vm.onCardTap(Side.CPU, 7)
        assertEquals(1, h.state.pendingPlayerSends)
        h.vm.onCardTap(Side.PLAYER, 3)
        assertEquals(0, h.state.pendingPlayerSends)
        assertEquals(listOf(1, 2, 4, 5), h.state.playerNos)
        assertTrue(h.state.cpuCards.last().isNew)

        h.vm.onNext()
        assertEquals(MatchPhase.Waiting, h.state.phase)
        // 直前の読みを保持している
        assertEquals(6, h.state.reading!!.poem.no)
        advance(interval)
        assertEquals(MatchPhase.Reading, h.state.phase)
        assertEquals(1, h.state.reading!!.poem.no)
        assertEquals(2, h.state.reading!!.readNumber)
        assertFalse(h.state.cpuCards.last().isNew)
    }

    @Test
    fun emptyCardAdvancesByItself() {
        val h = harness(factory = layout(order = listOf(11, 12)))
        advance(memorize)
        assertEquals(MatchPhase.Reading, h.state.phase)
        // 上の句 1800ms + 1200ms
        advance(3000L)
        assertEquals(MatchPhase.Result, h.state.phase)
        assertTrue(h.state.lastResult!!.isEmptyCard)
        assertEquals("空札でした", h.state.lastResult!!.message)
        advance(1200L)
        assertEquals(MatchPhase.Waiting, h.state.phase)
        advance(interval)
        assertEquals(MatchPhase.Reading, h.state.phase)
        assertEquals(12, h.state.reading!!.poem.no)
    }

    @Test
    fun finishingTheGameAndRestarting() {
        val h = harness(factory = layout(order = listOf(1, 11), player = listOf(1)))
        advance(memorize + 100L)
        h.vm.onCardTap(Side.PLAYER, 1)
        assertEquals(MatchPhase.Result, h.state.phase)
        h.vm.onNext()
        assertEquals(MatchPhase.Finished(Side.PLAYER), h.state.phase)
        assertEquals(1, h.state.playerTakenCount)
        advance(30_000L)
        assertEquals(MatchPhase.Finished(Side.PLAYER), h.state.phase)

        h.vm.onRestart()
        assertEquals(2, h.engines.size)
        val st = h.state
        assertEquals(MatchPhase.Waiting, st.phase)
        assertEquals(listOf(1), st.playerNos)
        assertEquals(listOf(6, 7, 8, 9, 10), st.cpuNos)
        assertNull(st.reading)
        assertNull(st.lastResult)
        assertEquals(0, st.playerTakenCount)
        assertEquals(0, st.readsDone)

        // もう一度、最初から始まる。読みの間ではなく札を覚える時間から
        assertTrue(st.isMemorizing)
        assertEquals(memorize, st.waitRemainingMillis)
        advance(memorize - 20L)
        assertEquals(MatchPhase.Waiting, h.state.phase)
        advance(20L)
        assertEquals(MatchPhase.Reading, h.state.phase)
        assertEquals(1, h.state.reading!!.poem.no)
        assertEquals(0, h.state.reading!!.revealedKana)
    }

    @Test
    fun restartInTheMiddleOfAReading() {
        val h = harness(factory = layout(order = listOf(1, 6)))
        advance(memorize + 700L)
        h.vm.onCardTap(Side.CPU, 6)
        assertEquals(1, h.state.playerFaultCount)

        h.vm.onRestart()
        val st = h.state
        assertEquals(MatchPhase.Waiting, st.phase)
        assertEquals(0, st.playerFaultCount)
        assertFalse(st.playerLockedOut)
        assertNull(st.reading)
        // 古い試合のイベントは新しい試合に影響しない
        advance(memorize)
        assertEquals(MatchPhase.Reading, h.state.phase)
        assertEquals(0, h.state.reading!!.revealedKana)
    }

    @Test
    fun restartKeepsThePausedState() {
        val h = harness(factory = layout(order = listOf(1, 6)))
        h.vm.setPaused(true)
        h.vm.onRestart()
        assertTrue(h.state.isPaused)
        advance(10_000L)
        assertEquals(MatchPhase.Waiting, h.state.phase)
        h.vm.setPaused(false)
        advance(memorize)
        assertEquals(MatchPhase.Reading, h.state.phase)
    }

    @Test
    fun usesRealElapsedTimeWhenTicksAreLate() {
        var now = 0L
        val h = harness(clock = { now }, factory = layout(order = listOf(1)))
        // 刻み 20ms より遅れて呼ばれたときは、実際に経った時間だけ進む
        now = 35L
        advance(20L)
        assertEquals(35L, h.engine.nowMillis)
        // 刻みより短くても 1 刻み（20ms）は進む
        now += 5L
        advance(20L)
        assertEquals(55L, h.engine.nowMillis)
        // 長く止まった後も、一度に進むのは 100ms まで
        now += 5_000L
        advance(20L)
        assertEquals(155L, h.engine.nowMillis)
    }

    @Test
    fun publicConstructorDealsFromTheGivenPoems() {
        // 初級の CPU は反応が 1400ms 以上かかるので、最初の数文字の間は読みが続く
        val config = GameConfig(
            emptyCardCount = 10,
            cpuLevel = CpuLevel.BEGINNER,
            readingSpeed = ReadingSpeed.FAST,
            memorizeTime = MemorizeTime.SEC_10,
        )
        val vm = MatchViewModel(
            config = config,
            random = Random(7),
            poems = SYNTHETIC_POEMS,
            clock = { scheduler.currentTime },
        )
        viewModels += vm
        val st = vm.uiState.value
        assertEquals(config, st.config)
        assertEquals(5, st.playerCards.size)
        assertEquals(5, st.cpuCards.size)
        assertEquals(20, st.totalReads)
        assertEquals(MatchPhase.Waiting, st.phase)

        advance(MemorizeTime.SEC_10.millis)
        val reading = vm.uiState.value.reading!!
        assertEquals(MatchPhase.Reading, vm.uiState.value.phase)
        assertEquals(1, reading.readNumber)
        // 速度は GameConfig.readingSpeed に従う（1文字 160ms）
        advance(160L * 3)
        assertEquals(3, vm.uiState.value.reading!!.revealedKana)
    }

    @Test
    fun defaultConstructorStartsWithBundledPoems() {
        val vm = MatchViewModel(GameConfig(emptyCardCount = 0), clock = { scheduler.currentTime })
        viewModels += vm
        assertEquals(MatchPhase.Waiting, vm.uiState.value.phase)
        // 既定は札を覚える時間 30秒
        advance(MemorizeTime.SEC_30.millis - 20L)
        assertEquals(MatchPhase.Waiting, vm.uiState.value.phase)
        advance(20L)
        assertTrue(vm.uiState.value.phase is MatchPhase.Reading || vm.uiState.value.phase is MatchPhase.Finished)
    }

    @Test
    fun aWholeGameCanBePlayedThrough() {
        val config = GameConfig(
            ruleMode = RuleMode.COMPETITIVE,
            emptyCardCount = 5,
            cpuLevel = CpuLevel.MASTER,
            readingSpeed = ReadingSpeed.FAST,
            memorizeTime = MemorizeTime.SEC_10,
            readInterval = ReadInterval.SEC_2,
        )
        val vm = MatchViewModel(
            config = config,
            random = Random(3),
            poems = SYNTHETIC_POEMS,
            clock = { scheduler.currentTime },
        )
        viewModels += vm
        var guard = 0
        while (vm.uiState.value.phase !is MatchPhase.Finished) {
            check(++guard < 5_000)
            advance(250L)
            val st = vm.uiState.value
            when (st.phase) {
                MatchPhase.Result -> {
                    if (st.pendingPlayerSends > 0) {
                        vm.onCardTap(Side.PLAYER, st.playerCards.first().poemNo)
                    } else {
                        vm.onNext()
                    }
                }
                else -> Unit
            }
        }
        val finished = vm.uiState.value.phase as MatchPhase.Finished
        // 名人は一度も取り逃さず、プレイヤーは何もしないので CPU が勝つ
        assertEquals(Side.CPU, finished.winner)
    }

    // ---- 読み始めまでの待ち ----

    @Test
    fun waitRemainingCountsDownWithTime() {
        val h = harness(factory = layout(order = listOf(1, 6)))
        assertEquals(memorize, h.state.waitRemainingMillis)
        advance(2_000L)
        assertEquals(memorize - 2_000L, h.state.waitRemainingMillis)
        assertTrue(h.state.isMemorizing)
        advance(memorize - 2_000L)
        assertEquals(MatchPhase.Reading, h.state.phase)
        assertNull(h.state.waitRemainingMillis)
        assertFalse(h.state.isMemorizing)
    }

    @Test
    fun waitRemainingStopsWhilePaused() {
        val h = harness(factory = layout(order = listOf(1, 6)))
        advance(3_000L)
        val remaining = h.state.waitRemainingMillis
        assertEquals(memorize - 3_000L, remaining)

        h.vm.setPaused(true)
        advance(60_000L)
        assertEquals(remaining, h.state.waitRemainingMillis)
        assertTrue(h.state.isPaused)
        assertEquals(MatchPhase.Waiting, h.state.phase)

        h.vm.setPaused(false)
        advance(1_000L)
        assertEquals(remaining!! - 1_000L, h.state.waitRemainingMillis)
    }

    @Test
    fun waitBetweenReadsUsesTheReadInterval() {
        val h = harness(factory = layout(order = listOf(6, 1)))
        advance(memorize + 200L)
        h.vm.onCardTap(Side.CPU, 6)
        h.vm.onCardTap(Side.PLAYER, 3)
        h.vm.onNext()

        val st = h.state
        assertEquals(MatchPhase.Waiting, st.phase)
        assertEquals(interval, st.waitRemainingMillis)
        assertFalse(st.isMemorizing)
        advance(interval - 20L)
        assertEquals(MatchPhase.Waiting, h.state.phase)
        advance(20L)
        assertEquals(MatchPhase.Reading, h.state.phase)
    }

    @Test
    fun onStartReadingSkipsTheWait() {
        val h = harness(factory = layout(order = listOf(1, 6)))
        advance(1_000L)
        h.vm.onStartReading()
        val st = h.state
        assertEquals(MatchPhase.Reading, st.phase)
        assertEquals(1, st.reading!!.poem.no)
        assertEquals(0, st.reading!!.revealedKana)
        assertNull(st.waitRemainingMillis)
        assertFalse(st.isMemorizing)

        // 読み始めたら通常どおり進む
        advance(300L)
        assertEquals(3, h.state.reading!!.revealedKana)
    }

    @Test
    fun onStartReadingSkipsTheReadIntervalToo() {
        val h = harness(factory = layout(order = listOf(6, 1)))
        h.vm.onStartReading()
        h.vm.onCardTap(Side.CPU, 6)
        h.vm.onCardTap(Side.PLAYER, 3)
        h.vm.onNext()
        assertEquals(MatchPhase.Waiting, h.state.phase)

        h.vm.onStartReading()
        assertEquals(MatchPhase.Reading, h.state.phase)
        assertEquals(2, h.state.reading!!.readNumber)
    }

    @Test
    fun onStartReadingIsIgnoredWhilePausedAndOutsideWaiting() {
        val h = harness(factory = layout(order = listOf(1, 6)))
        h.vm.setPaused(true)
        h.vm.onStartReading()
        assertEquals(MatchPhase.Waiting, h.state.phase)
        assertTrue(h.state.isPaused)
        h.vm.setPaused(false)

        advance(memorize + 300L)
        assertEquals(3, h.state.reading!!.revealedKana)
        h.vm.onStartReading()
        assertEquals(MatchPhase.Reading, h.state.phase)
        assertEquals(1, h.state.reading!!.readNumber)
        assertEquals(3, h.state.reading!!.revealedKana)

        h.vm.onCardTap(Side.PLAYER, 1)
        assertEquals(MatchPhase.Result, h.state.phase)
        h.vm.onStartReading()
        assertEquals(MatchPhase.Result, h.state.phase)
    }

    @Test
    fun tapModeWaitsUntilOnStartReading() {
        val h = harness(factory = layout(order = listOf(1, 6), readStartMode = ReadStartMode.TAP))
        advance(10 * 60 * 1000L)
        var st = h.state
        assertEquals(MatchPhase.Waiting, st.phase)
        assertNull(st.waitRemainingMillis)
        assertTrue(st.isMemorizing)

        h.vm.onStartReading()
        assertEquals(MatchPhase.Reading, h.state.phase)
        advance(100L)
        h.vm.onCardTap(Side.PLAYER, 1)
        h.vm.onNext()

        // 2枚目の前も、押すまで待ち続ける
        advance(10 * 60 * 1000L)
        st = h.state
        assertEquals(MatchPhase.Waiting, st.phase)
        assertNull(st.waitRemainingMillis)
        assertFalse(st.isMemorizing)
        h.vm.onStartReading()
        assertEquals(MatchPhase.Reading, h.state.phase)
        assertEquals(2, h.state.reading!!.readNumber)
    }

    @Test
    fun restartGoesBackToMemorizingEvenAfterReads() {
        val h = harness(factory = layout(order = listOf(6, 1)))
        advance(memorize + 200L)
        h.vm.onCardTap(Side.CPU, 6)
        h.vm.onCardTap(Side.PLAYER, 3)
        h.vm.onNext()
        advance(500L)
        assertFalse(h.state.isMemorizing)
        assertEquals(interval - 500L, h.state.waitRemainingMillis)

        h.vm.onRestart()
        val st = h.state
        assertEquals(MatchPhase.Waiting, st.phase)
        assertTrue(st.isMemorizing)
        assertEquals(memorize, st.waitRemainingMillis)
        advance(interval)
        assertEquals(MatchPhase.Waiting, h.state.phase)
        assertEquals(memorize - interval, h.state.waitRemainingMillis)
    }

    @Test
    fun restartInTapModeWaitsAgain() {
        val h = harness(factory = layout(order = listOf(1, 6), readStartMode = ReadStartMode.TAP))
        h.vm.onStartReading()
        assertEquals(MatchPhase.Reading, h.state.phase)
        h.vm.onRestart()
        assertEquals(MatchPhase.Waiting, h.state.phase)
        assertTrue(h.state.isMemorizing)
        assertNull(h.state.waitRemainingMillis)
        advance(60_000L)
        assertEquals(MatchPhase.Waiting, h.state.phase)
    }
}
