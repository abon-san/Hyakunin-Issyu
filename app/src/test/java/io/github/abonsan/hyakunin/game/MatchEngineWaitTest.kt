package io.github.abonsan.hyakunin.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 読み始めまでの待ち（札を覚える時間・読みの間・タップで読み始める）。 */
class MatchEngineWaitTest {
    /** 1首目を読み始め、プレイヤーが取って Result を出し、次へ進んで 2枚目の前の Waiting にする。 */
    private fun MatchEngine.finishFirstReadAndGoNext() {
        beginRead()
        tapCard(Side.PLAYER, 1)
        check(state.phase == MatchPhase.Result)
        next()
        check(state.phase == MatchPhase.Waiting)
    }

    @Test
    fun defaultConfigUsesThirtySecondsThenFourSeconds() {
        val config = GameConfig()
        assertEquals(ReadStartMode.AUTO, config.readStartMode)
        assertEquals(30_000L, config.memorizeTime.millis)
        assertEquals(4_000L, config.readInterval.millis)

        val e = MatchEngine.withLayout(
            config = GameConfig(emptyCardCount = 0),
            random = ZeroRandom,
            poems = SYNTHETIC_POEMS,
            playerNos = listOf(1, 2, 3, 4, 5),
            cpuNos = listOf(6, 7, 8, 9, 10),
            readOrder = listOf(1, 2),
            timing = ReadTiming(100L),
            profile = SLOW_CPU,
        )
        e.advanceTo(29_999L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        e.advanceTo(30_000L)
        assertEquals(MatchPhase.Reading, e.state.phase)

        e.tapCard(Side.PLAYER, 1)
        e.next()
        e.advanceBy(3_999L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        e.advanceBy(1L)
        assertEquals(MatchPhase.Reading, e.state.phase)
    }

    // ---- AUTO ----

    @Test
    fun autoFirstWaitFollowsMemorizeTime() {
        for (time in MemorizeTime.entries) {
            val e = layoutEngine(order = listOf(1), memorizeTime = time)
            e.advanceTo(time.millis - 1)
            assertEquals("$time", MatchPhase.Waiting, e.state.phase)
            e.advanceTo(time.millis)
            assertEquals("$time", MatchPhase.Reading, e.state.phase)
            assertEquals(time.millis, e.session!!.startMillis)
        }
    }

    @Test
    fun autoWaitBetweenReadsFollowsReadInterval() {
        for (interval in ReadInterval.entries) {
            val e = layoutEngine(order = listOf(1, 2), readInterval = interval)
            e.finishFirstReadAndGoNext()
            val waitStart = e.nowMillis
            e.advanceTo(waitStart + interval.millis - 1)
            assertEquals("$interval", MatchPhase.Waiting, e.state.phase)
            e.advanceTo(waitStart + interval.millis)
            assertEquals("$interval", MatchPhase.Reading, e.state.phase)
            assertEquals(2, e.state.reading!!.readNumber)
            assertEquals(waitStart + interval.millis, e.session!!.startMillis)
        }
    }

    @Test
    fun memorizeTimeAndReadIntervalAreIndependent() {
        val e = layoutEngine(
            order = listOf(1, 2),
            memorizeTime = MemorizeTime.SEC_30,
            readInterval = ReadInterval.SEC_10,
        )
        // 読みの間（10秒）では始まらず、札を覚える時間（30秒）を待つ
        e.advanceTo(10_000L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        e.advanceTo(29_999L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        e.advanceTo(30_000L)
        assertEquals(MatchPhase.Reading, e.state.phase)

        // 2枚目の前は読みの間（10秒）だけ待つ。札を覚える時間（30秒）は使わない
        e.tapCard(Side.PLAYER, 1)
        e.next()
        val waitStart = e.nowMillis
        e.advanceTo(waitStart + 9_999L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        e.advanceTo(waitStart + 10_000L)
        assertEquals(MatchPhase.Reading, e.state.phase)
    }

    @Test
    fun waitRemainingCountsDownAndIsMemorizingInTheFirstWait() {
        val e = layoutEngine(order = listOf(1, 2), memorizeTime = MemorizeTime.SEC_10)
        var st = e.state
        assertEquals(MatchPhase.Waiting, st.phase)
        assertEquals(10_000L, st.waitRemainingMillis)
        assertTrue(st.isMemorizing)

        e.advanceTo(2_500L)
        st = e.state
        assertEquals(7_500L, st.waitRemainingMillis)
        assertTrue(st.isMemorizing)

        e.advanceTo(9_999L)
        assertEquals(1L, e.state.waitRemainingMillis)

        e.advanceTo(10_000L)
        st = e.state
        assertEquals(MatchPhase.Reading, st.phase)
        assertNull(st.waitRemainingMillis)
        assertFalse(st.isMemorizing)
    }

    @Test
    fun waitRemainingCountsDownBetweenReadsWithoutMemorizing() {
        val e = layoutEngine(order = listOf(1, 2), readInterval = ReadInterval.SEC_4)
        e.finishFirstReadAndGoNext()
        var st = e.state
        assertEquals(4_000L, st.waitRemainingMillis)
        assertFalse(st.isMemorizing)

        e.advanceBy(1_500L)
        st = e.state
        assertEquals(2_500L, st.waitRemainingMillis)
        assertFalse(st.isMemorizing)
    }

    @Test
    fun waitFieldsAreEmptyOutsideWaiting() {
        val e = layoutEngine(order = listOf(1, 2))
        e.beginRead()
        var st = e.state
        assertNull(st.waitRemainingMillis)
        assertFalse(st.isMemorizing)

        e.tapCard(Side.PLAYER, 1)
        st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        assertNull(st.waitRemainingMillis)
        assertFalse(st.isMemorizing)
    }

    @Test
    fun readIntervalAppliesAfterAnEmptyCardResultCloses() {
        val e = layoutEngine(order = listOf(11, 12), readInterval = ReadInterval.SEC_6)
        val s = e.beginRead()
        e.advanceToOffset(s, 3000L)
        assertEquals(MatchPhase.Result, e.state.phase)
        e.advanceBy(1200L)
        val st = e.state
        assertEquals(MatchPhase.Waiting, st.phase)
        assertEquals(6_000L, st.waitRemainingMillis)
        assertFalse(st.isMemorizing)

        e.advanceBy(5_999L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        e.advanceBy(1L)
        assertEquals(MatchPhase.Reading, e.state.phase)
        assertEquals(12, e.state.reading!!.poem.no)
    }

    @Test
    fun readIntervalAppliesAfterNextOnAResult() {
        val e = layoutEngine(order = listOf(6, 1), readInterval = ReadInterval.SEC_10)
        e.beginRead()
        e.tapCard(Side.CPU, 6)
        e.tapCard(Side.PLAYER, 5)
        e.next()
        assertEquals(MatchPhase.Waiting, e.state.phase)
        assertEquals(10_000L, e.state.waitRemainingMillis)
        e.advanceBy(9_999L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        e.advanceBy(1L)
        assertEquals(MatchPhase.Reading, e.state.phase)
    }

    // ---- TAP ----

    @Test
    fun tapModeWaitsForeverBeforeTheFirstRead() {
        val e = layoutEngine(order = listOf(1), readStartMode = ReadStartMode.TAP)
        e.advanceBy(60 * 60 * 1000L)
        val st = e.state
        assertEquals(MatchPhase.Waiting, st.phase)
        assertNull(st.reading)
        assertNull(st.waitRemainingMillis)
        assertTrue(st.isMemorizing)

        e.startReading()
        assertEquals(MatchPhase.Reading, e.state.phase)
        assertEquals(1, e.state.reading!!.poem.no)
        assertEquals(0, e.state.reading!!.revealedKana)
        assertFalse(e.state.isMemorizing)
    }

    @Test
    fun tapModeWaitsForeverBetweenReads() {
        val e = layoutEngine(order = listOf(1, 2), readStartMode = ReadStartMode.TAP)
        e.finishFirstReadAndGoNext()
        e.advanceBy(60 * 60 * 1000L)
        val st = e.state
        assertEquals(MatchPhase.Waiting, st.phase)
        assertNull(st.waitRemainingMillis)
        assertFalse(st.isMemorizing)
        assertEquals(1, st.reading!!.readNumber)

        e.startReading()
        assertEquals(MatchPhase.Reading, e.state.phase)
        assertEquals(2, e.state.reading!!.readNumber)
    }

    @Test
    fun tapModeWaitsAfterAnEmptyCardResultCloses() {
        val e = layoutEngine(order = listOf(11, 12), readStartMode = ReadStartMode.TAP)
        val s = e.beginRead()
        e.advanceToOffset(s, 3000L)
        assertEquals(MatchPhase.Result, e.state.phase)
        e.advanceBy(1200L)
        assertEquals(MatchPhase.Waiting, e.state.phase)

        e.advanceBy(10 * 60 * 1000L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        assertNull(e.state.waitRemainingMillis)
        e.startReading()
        assertEquals(MatchPhase.Reading, e.state.phase)
        assertEquals(12, e.state.reading!!.poem.no)
    }

    // ---- すぐ読む ----

    @Test
    fun startReadingSkipsTheRemainingWait() {
        val e = layoutEngine(order = listOf(1, 2), memorizeTime = MemorizeTime.SEC_10)
        e.advanceTo(3_000L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        e.startReading()

        val st = e.state
        assertEquals(MatchPhase.Reading, st.phase)
        assertEquals(3_000L, e.session!!.startMillis)
        assertEquals(1, st.reading!!.readNumber)
        assertNull(st.waitRemainingMillis)
        assertFalse(st.isMemorizing)

        // 元の予定時刻（10秒）に重ねて読み始めたりしない
        e.advanceTo(10_000L)
        assertEquals(1, e.state.reading!!.readNumber)
    }

    @Test
    fun startReadingSkipsTheReadIntervalToo() {
        val e = layoutEngine(order = listOf(1, 2), readInterval = ReadInterval.SEC_10)
        e.finishFirstReadAndGoNext()
        e.advanceBy(500L)
        e.startReading()
        assertEquals(MatchPhase.Reading, e.state.phase)
        assertEquals(2, e.state.reading!!.readNumber)
    }

    @Test
    fun startReadingIsIgnoredOutsideWaiting() {
        val e = layoutEngine(order = listOf(1, 2), player = listOf(1))
        val s = e.beginRead()
        e.advanceToOffset(s, 300L)
        val revealed = e.state.reading!!.revealedKana
        e.startReading()
        assertEquals(MatchPhase.Reading, e.state.phase)
        assertEquals(1, e.state.reading!!.readNumber)
        assertEquals(revealed, e.state.reading!!.revealedKana)

        e.tapCard(Side.PLAYER, 1)
        assertEquals(MatchPhase.Result, e.state.phase)
        e.startReading()
        assertEquals(MatchPhase.Result, e.state.phase)

        e.next()
        assertTrue(e.state.phase is MatchPhase.Finished)
        e.startReading()
        assertTrue(e.state.phase is MatchPhase.Finished)
    }

    @Test
    fun startReadingIsIgnoredWhileChoosingSendCards() {
        val e = layoutEngine(order = listOf(6, 1))
        e.beginRead()
        e.tapCard(Side.CPU, 6)
        assertEquals(1, e.state.pendingPlayerSends)
        e.startReading()
        assertEquals(MatchPhase.Result, e.state.phase)
        assertEquals(1, e.state.pendingPlayerSends)
    }
}
