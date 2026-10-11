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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** ViewModel 経由の前置き・音声: 一時停止、エンジンの時計、やり直し。 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceViewModelTest {
    private lateinit var scheduler: TestCoroutineScheduler
    private val viewModels = mutableListOf<MatchViewModel>()

    private val memorize = MemorizeTime.SEC_10.millis

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

    private fun viewModel(
        order: List<Int> = listOf(1, 2),
        readingStyle: ReadingStyle = ReadingStyle.COMPETITIVE,
    ): MatchViewModel {
        val vm = MatchViewModel(
            engineFactory = {
                layoutEngine(order = order, timing = voiceTiming(), readingStyle = readingStyle)
            },
            clock = { scheduler.currentTime },
            tickMillis = 20L,
        )
        viewModels += vm
        return vm
    }

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    @Test
    fun engineTimeAdvancesWithTheClock() {
        val vm = viewModel()
        assertEquals(0L, vm.uiState.value.engineTimeMillis)
        advance(1_000L)
        assertEquals(1_000L, vm.uiState.value.engineTimeMillis)
        assertNull(vm.uiState.value.audio)
        assertNull(vm.uiState.value.leadIn)
        advance(memorize - 1_000L)
        val st = vm.uiState.value
        assertEquals(memorize, st.engineTimeMillis)
        // 札を覚える時間が終わると序歌の前置き。前置きの間も Waiting
        assertEquals(MatchPhase.Waiting, st.phase)
        assertEquals(LeadInKind.JOKA, st.leadIn!!.kind)
        assertEquals(memorize, st.audio!!.startedAtMillis)
        assertEquals(0L, st.audio!!.positionAt(st.engineTimeMillis))
    }

    @Test
    fun pausingTheLeadInKeepsTheCueAndFreezesTheClock() {
        val vm = viewModel()
        // 序歌の下の句 1 回目（2400 から）の 100ms 目
        advance(memorize + 2_500L)
        val before = vm.uiState.value
        val cue = before.audio!!
        assertEquals("voice/joka_shimo.ogg", cue.asset)
        assertEquals(LeadInVerse.SHIMO, before.leadIn!!.verse)
        assertEquals(1, before.leadIn!!.revealedKana)

        vm.setPaused(true)
        advance(60_000L)
        val paused = vm.uiState.value
        assertTrue(paused.isPaused)
        // エンジンの時計も、前置きの進みも、同じ cue のまま止まっている
        assertEquals(before.engineTimeMillis, paused.engineTimeMillis)
        assertEquals(before.leadIn, paused.leadIn)
        assertEquals(cue, paused.audio)
        assertEquals(MatchPhase.Waiting, paused.phase)

        // 一時停止中の操作は無視される
        vm.onCardTap(Side.PLAYER, 1)
        vm.onCardTap(Side.CPU, 6)
        vm.onNext()
        vm.onStartReading()
        val stillPaused = vm.uiState.value
        assertEquals(0, stillPaused.playerFaultCount)
        assertEquals(before.leadIn, stillPaused.leadIn)

        // 再開すると、止まっていた間を除いて進む。同じ cue のまま、位置だけが進む
        vm.setPaused(false)
        advance(1_000L)
        val resumed = vm.uiState.value
        assertFalse(resumed.isPaused)
        assertEquals(before.engineTimeMillis + 1_000L, resumed.engineTimeMillis)
        assertEquals(cue, resumed.audio)
        assertEquals(1_100L, resumed.audio!!.positionAt(resumed.engineTimeMillis))
    }

    @Test
    fun tapsDuringTheLeadInDoNothingThroughTheViewModel() {
        val vm = viewModel()
        advance(memorize + 500L)
        assertNotNull(vm.uiState.value.leadIn)
        vm.onCardTap(Side.PLAYER, 1)
        vm.onCardTap(Side.CPU, 6)
        vm.onNext()
        vm.onStartReading()
        val st = vm.uiState.value
        assertNotNull(st.leadIn)
        assertEquals(MatchPhase.Waiting, st.phase)
        assertEquals(0, st.playerFaultCount)
        assertEquals(0, st.playerTakenCount)
        assertFalse(st.playerLockedOut)
    }

    @Test
    fun theLeadInEndsAndTheReadingStartsThroughTheViewModel() {
        val vm = viewModel()
        advance(memorize + FIRST_LEAD_IN_MILLIS - 20L)
        assertNotNull(vm.uiState.value.leadIn)
        advance(20L)
        val st = vm.uiState.value
        assertEquals(MatchPhase.Reading, st.phase)
        assertNull(st.leadIn)
        assertEquals("voice/p001_kami.ogg", st.audio!!.asset)
        advance(300L)
        assertEquals(2, vm.uiState.value.reading!!.revealedKana)
        // 取ると Result。音声は鳴り続け、次へで止まる
        vm.onCardTap(Side.PLAYER, 1)
        assertEquals(MatchPhase.Result, vm.uiState.value.phase)
        assertNotNull(vm.uiState.value.audio)
        advance(1_000L)
        assertNotNull(vm.uiState.value.audio)
        vm.onNext()
        assertNull(vm.uiState.value.audio)
    }

    @Test
    fun restartingStopsTheAudioAndKeepsCueIdsIncreasing() {
        val vm = viewModel()
        advance(memorize + 3_000L)
        val old = vm.uiState.value.audio!!
        assertEquals(600L, old.positionAt(vm.uiState.value.engineTimeMillis))

        vm.onRestart()
        val restarted = vm.uiState.value
        assertEquals(MatchPhase.Waiting, restarted.phase)
        assertNull(restarted.audio)
        assertNull(restarted.leadIn)
        assertEquals(0L, restarted.engineTimeMillis)

        // もう一度、最初から序歌。cueId は前より大きい
        advance(memorize)
        val fresh = vm.uiState.value.audio!!
        assertEquals("voice/joka_kami.ogg", fresh.asset)
        assertEquals(memorize, fresh.startedAtMillis)
        assertTrue("old=${old.cueId} fresh=${fresh.cueId}", fresh.cueId > old.cueId)
        assertEquals(LeadInKind.JOKA, vm.uiState.value.leadIn!!.kind)
    }

    @Test
    fun simpleStyleThroughTheViewModelHasNoLeadIn() {
        val vm = viewModel(readingStyle = ReadingStyle.SIMPLE)
        advance(memorize)
        val st = vm.uiState.value
        assertEquals(MatchPhase.Reading, st.phase)
        assertNull(st.leadIn)
        assertEquals("voice/p001_kami.ogg", st.audio!!.asset)
    }
}
