package io.github.abonsan.hyakunin.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 読み上げの進行（待ち・仮名の表示・空札の終わり・取り手なし）。 */
class MatchEngineReadingTest {
    @Test
    fun waitsThenStartsReading() {
        val e = layoutEngine(order = listOf(1))
        assertEquals(MatchPhase.Waiting, e.state.phase)
        assertNull(e.state.reading)
        assertEquals(1, e.state.totalReads)
        assertEquals(0, e.state.readsDone)

        e.advanceTo(1499L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        assertNull(e.state.reading)

        e.advanceTo(1500L)
        val st = e.state
        assertEquals(MatchPhase.Reading, st.phase)
        val reading = st.reading!!
        assertEquals(1, reading.poem.no)
        assertEquals(1, reading.readNumber)
        assertEquals(1, reading.totalReads)
        assertEquals(0, reading.revealedKana)
        assertEquals(16, reading.kamiKanaCount)
        assertEquals(26, reading.totalKanaCount)
    }

    @Test
    fun kanaAreRevealedOneByOneWithGaps() {
        val e = layoutEngine(order = listOf(1))
        val s = e.beginRead()
        val offsets = s.kanaAt.map { it - s.startMillis }
        val expected = listOf(
            // 上の句 1 句目（6文字）
            100L, 200L, 300L, 400L, 500L, 600L,
            // 句の区切りで 100ms の間 → 2 句目（5文字）
            800L, 900L, 1000L, 1100L, 1200L,
            // 3 句目（5文字）
            1400L, 1500L, 1600L, 1700L, 1800L,
            // 上の句と下の句の間は 300ms の間 → 下の句（5文字 + 区切り + 5文字）
            2200L, 2300L, 2400L, 2500L, 2600L,
            2800L, 2900L, 3000L, 3100L, 3200L,
        )
        assertEquals(expected, offsets)
        assertEquals(1800L, s.kamiEnd - s.startMillis)
        assertEquals(3200L, s.shimoEnd - s.startMillis)

        fun revealedAt(offset: Long): Int {
            e.advanceToOffset(s, offset)
            return e.state.reading!!.revealedKana
        }
        assertEquals(0, revealedAt(99L))
        assertEquals(1, revealedAt(100L))
        assertEquals(6, revealedAt(600L))
        assertEquals(6, revealedAt(799L))
        assertEquals(7, revealedAt(800L))
        assertEquals(16, revealedAt(1800L))
        assertEquals(16, revealedAt(2199L))
        assertEquals(17, revealedAt(2200L))
        assertEquals(25, revealedAt(3100L))
        assertEquals(26, revealedAt(3200L))
    }

    @Test
    fun timelineScalesWithCharMillis() {
        val e = layoutEngine(order = listOf(1), charMillis = 230L)
        val s = e.beginRead()
        assertEquals(230L, s.kanaAt[0] - s.startMillis)
        // 7文字目: 6文字 + 区切り 1 つぶん
        assertEquals(230L * 8, s.kanaAt[6] - s.startMillis)
        // 17文字目: 16文字 + 区切り 2 つぶん + 上下の句の間 3 つぶん
        assertEquals(230L * (17 + 2 + 3), s.kanaAt[16] - s.startMillis)
    }

    @Test
    fun fieldCardStaysReadingUntilSomeoneTakesIt() {
        // CPU が早々に友札へ触れて取れなくなる。プレイヤーが取るまで Reading のまま
        val e = layoutEngine(order = listOf(1), profile = FAULTY_CPU)
        val s = e.beginRead()
        e.advanceToOffset(s, 100L)
        assertTrue(s.cpuLocked)
        e.advanceToOffset(s, 60_000L)
        val st = e.state
        assertEquals(MatchPhase.Reading, st.phase)
        assertFalse(st.playerLockedOut)
        assertEquals(26, st.reading!!.revealedKana)

        e.tapCard(Side.PLAYER, 1)
        val after = e.state
        assertEquals(MatchPhase.Result, after.phase)
        assertEquals(Side.PLAYER, after.lastResult!!.takenBy)
        assertEquals(26, after.lastResult!!.revealedAtTake)
    }

    @Test
    fun emptyCardEndsAfterUpperPhrasePlusTail() {
        val e = layoutEngine(order = listOf(11, 12))
        val s = e.beginRead()
        assertTrue(s.isEmpty)
        e.advanceToOffset(s, 1800L + 1199L)
        assertEquals(MatchPhase.Reading, e.state.phase)
        // 空札は上の句までしか表示しない
        assertEquals(16, e.state.reading!!.revealedKana)
        e.advanceToOffset(s, 1800L + 1200L)

        val st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        assertEquals(1, st.readsDone)
        // 上の句までを表示し終えた状態で止まる
        assertEquals(16, st.reading!!.revealedKana)
        val r = st.lastResult!!
        assertTrue(r.isEmptyCard)
        assertNull(r.takenBy)
        assertNull(r.takenFrom)
        assertNull(r.revealedAtTake)
        assertEquals(2, r.kimarijiLength)
        assertTrue(r.faults.isEmpty())
        assertEquals("空札でした", r.message)
        // 場の札は変わらない
        assertEquals(listOf(1, 2, 3, 4, 5), st.playerNos)
        assertEquals(listOf(6, 7, 8, 9, 10), st.cpuNos)
    }

    @Test
    fun emptyCardWithoutFaultAdvancesAutomatically() {
        val e = layoutEngine(order = listOf(11, 12))
        val s = e.beginRead()
        e.advanceToOffset(s, 3000L)
        assertEquals(MatchPhase.Result, e.state.phase)

        e.advanceBy(1199L)
        assertEquals(MatchPhase.Result, e.state.phase)
        e.advanceBy(1L)
        assertEquals(MatchPhase.Waiting, e.state.phase)

        e.advanceBy(1499L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        e.advanceBy(1L)
        assertEquals(MatchPhase.Reading, e.state.phase)
        assertEquals(12, e.state.reading!!.poem.no)
        assertEquals(2, e.state.reading!!.readNumber)
    }

    @Test
    fun emptyCardIgnoresCpuWithoutFaults() {
        val e = layoutEngine(order = listOf(11), profile = steadyCpu(0L))
        val s = e.beginRead()
        assertNull(s.cpuPlan)
        e.advanceToOffset(s, 3000L)
        assertEquals(0, e.state.cpuTakenCount)
        assertEquals(0, e.state.cpuFaultCount)
    }

    @Test
    fun bothLockedOutRemovesCardAfterUpperAndLowerPhrase() {
        // プレイヤーは別の陣の札に触れてお手つき、CPU は同じ陣の友札に触れてセーフ
        val e = layoutEngine(order = listOf(1), profile = FAULTY_CPU)
        val s = e.beginRead()
        e.advanceToOffset(s, 500L)
        e.tapCard(Side.CPU, 6)
        assertTrue(e.state.playerLockedOut)

        // 下の句を表示し終えた（3200）1500ms 後
        e.advanceToOffset(s, 3200L + 1499L)
        assertEquals(MatchPhase.Reading, e.state.phase)
        e.advanceToOffset(s, 3200L + 1500L)

        val st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        assertEquals(26, st.reading!!.revealedKana)
        val r = st.lastResult!!
        assertNull(r.takenBy)
        assertNull(r.takenFrom)
        assertNull(r.revealedAtTake)
        assertFalse(r.isEmptyCard)
        assertEquals(setOf(Side.PLAYER), r.faults)
        assertEquals(setOf(Side.CPU), r.safeMisses)
        // 出札は場から取り除かれ、お手つきの罰として CPU から1枚送られる
        assertEquals(listOf(2, 3, 4, 5, 6), st.playerNos)
        assertEquals(listOf(7, 8, 9, 10), st.cpuNos)
        assertEquals(
            listOf(Transfer(Side.CPU, Side.PLAYER, 6, Transfer.Reason.FAULT_PENALTY)),
            r.transfers,
        )
        assertEquals(0, st.playerTakenCount)
        assertEquals(0, st.cpuTakenCount)
        assertEquals(
            "誰も取れませんでした。お手つき！相手から1枚送られます。相手は同じ陣の別の札に触れました",
            r.message,
        )
    }

    @Test
    fun resultKeepsReadingAndFreezesRevealedKana() {
        val e = layoutEngine(order = listOf(1, 2))
        val s = e.beginRead()
        e.advanceToOffset(s, 450L)
        e.tapCard(Side.PLAYER, 1)

        val st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        assertEquals(4, st.reading!!.revealedKana)
        assertEquals(4, st.lastResult!!.revealedAtTake)
        e.advanceBy(10_000L)
        assertEquals(4, e.state.reading!!.revealedKana)
        assertEquals(1, e.state.reading!!.poem.no)

        // 次の読みの前にも直前の読みを保持する
        e.next()
        assertEquals(MatchPhase.Waiting, e.state.phase)
        assertEquals(1, e.state.reading!!.poem.no)
        e.beginRead()
        assertEquals(2, e.state.reading!!.poem.no)
        assertEquals(0, e.state.reading!!.revealedKana)
    }

    @Test
    fun kimarijiLengthReflectsCardsReadSoFar() {
        // 合成の100首: 先頭の仮名が同じ歌が10首ずつあるので、最初の決まり字は2
        val e = layoutEngine(order = listOf(1, 11))
        assertEquals(2, e.beginRead().kimarijiLength)
        e.tapCard(Side.PLAYER, 1)
        assertEquals(2, e.state.lastResult!!.kimarijiLength)

        // 「あき」で始まる2首が紛らわしい。1 が読まれると、残った 2 は「あ」だけで決まる
        val poems = listOf(testPoem(1, "あきのたの"), testPoem(2, "あきかぜの"), testPoem(3, "はるすぎて"))
        val e2 = layoutEngine(order = listOf(1, 2, 3), player = listOf(1, 2), cpu = listOf(3), poems = poems)
        assertEquals(3, e2.beginRead().kimarijiLength)
        e2.tapCard(Side.PLAYER, 1)
        assertEquals(3, e2.state.lastResult!!.kimarijiLength)
        e2.next()
        assertEquals(1, e2.beginRead().kimarijiLength)
        e2.tapCard(Side.PLAYER, 2)
        assertEquals(1, e2.state.lastResult!!.kimarijiLength)
    }

    @Test
    fun tapsOutsideReadingAreIgnored() {
        val e = layoutEngine(order = listOf(1))
        e.tapCard(Side.PLAYER, 1)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        assertEquals(0, e.state.playerTakenCount)
        assertEquals(0, e.state.playerFaultCount)

        val s = e.beginRead()
        // 場にない札・存在しない陣の組み合わせは無視する
        e.tapCard(Side.PLAYER, 99)
        e.tapCard(Side.CPU, 1)
        assertEquals(MatchPhase.Reading, e.state.phase)
        assertFalse(e.state.playerLockedOut)
        assertEquals(0, e.state.playerFaultCount)
        assertEquals(1, s.poem.no)
    }

    @Test
    fun nextIsIgnoredWhileReadingAndWaiting() {
        val e = layoutEngine(order = listOf(1, 2))
        e.next()
        assertEquals(MatchPhase.Waiting, e.state.phase)
        e.beginRead()
        e.next()
        assertEquals(MatchPhase.Reading, e.state.phase)
    }
}
