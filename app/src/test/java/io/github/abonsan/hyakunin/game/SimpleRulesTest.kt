package io.github.abonsan.hyakunin.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 簡易ルール: 取った枚数で勝敗を決める。札の移動はない。 */
class SimpleRulesTest {
    private val cpuTakeOffset = 3200L + 500L

    private fun simpleEngine(
        order: List<Int>,
        player: List<Int> = listOf(1, 2, 3, 4, 5),
        cpu: List<Int> = listOf(6, 7, 8, 9, 10),
        profile: CpuProfile = SLOW_CPU,
    ) = layoutEngine(order = order, mode = RuleMode.SIMPLE, player = player, cpu = cpu, profile = profile)

    @Test
    fun takingEnemyCardMovesNoCards() {
        val e = simpleEngine(order = listOf(6, 11))
        val s = e.beginRead()
        e.advanceToOffset(s, 300L)
        e.tapCard(Side.CPU, 6)

        val st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        assertEquals(listOf(1, 2, 3, 4, 5), st.playerNos)
        assertEquals(listOf(7, 8, 9, 10), st.cpuNos)
        assertEquals(0, st.pendingPlayerSends)
        assertEquals(1, st.playerTakenCount)
        val r = st.lastResult!!
        assertEquals(Side.PLAYER, r.takenBy)
        assertEquals(Side.CPU, r.takenFrom)
        assertTrue(r.transfers.isEmpty())
        assertEquals("敵陣の札を取りました", r.message)
    }

    @Test
    fun cpuTakingPlayerCardMovesNoCards() {
        val e = simpleEngine(order = listOf(1, 11))
        val s = e.beginRead()
        e.advanceToOffset(s, cpuTakeOffset)

        val st = e.state
        assertEquals(listOf(2, 3, 4, 5), st.playerNos)
        assertEquals(listOf(6, 7, 8, 9, 10), st.cpuNos)
        assertEquals(1, st.cpuTakenCount)
        assertTrue(st.lastResult!!.transfers.isEmpty())
        assertEquals("相手が自陣の札を取りました", st.lastResult!!.message)
    }

    @Test
    fun touchingWrongCardOnlyLocksOutAndCountsFault() {
        val e = simpleEngine(order = listOf(1, 11))
        val s = e.beginRead()
        e.advanceToOffset(s, 500L)
        // 同じ陣の札でもお手つきとして数える（簡易ルールにセーフはない）
        e.tapCard(Side.PLAYER, 2)
        assertTrue(e.state.playerLockedOut)
        assertEquals(1, e.state.playerFaultCount)

        e.tapCard(Side.PLAYER, 1)
        e.tapCard(Side.CPU, 6)
        assertEquals(MatchPhase.Reading, e.state.phase)
        assertEquals(1, e.state.playerFaultCount)

        e.advanceToOffset(s, cpuTakeOffset)
        val st = e.state
        val r = st.lastResult!!
        assertEquals(Side.CPU, r.takenBy)
        assertEquals(setOf(Side.PLAYER), r.faults)
        assertTrue(r.safeMisses.isEmpty())
        assertTrue(r.transfers.isEmpty())
        assertEquals(listOf(2, 3, 4, 5), st.playerNos)
        assertEquals(listOf(6, 7, 8, 9, 10), st.cpuNos)
        assertEquals("相手が自陣の札を取りました。あなたのお手つき", r.message)
    }

    @Test
    fun touchingCardOnEmptyReadingCountsFaultWithoutPenalty() {
        val e = simpleEngine(order = listOf(11, 12))
        val s = e.beginRead()
        e.tapCard(Side.CPU, 8)
        e.advanceToOffset(s, 3000L)

        val st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        assertEquals(1, st.playerFaultCount)
        assertEquals(setOf(Side.PLAYER), st.lastResult!!.faults)
        assertTrue(st.lastResult!!.transfers.isEmpty())
        assertEquals(5, st.playerCards.size)
        assertEquals(5, st.cpuCards.size)
        assertEquals("空札でした。あなたのお手つき", st.lastResult!!.message)
        // お手つきがあった空札は自動では進まない
        e.advanceBy(30_000L)
        assertEquals(MatchPhase.Result, e.state.phase)
        e.next()
        assertEquals(MatchPhase.Waiting, e.state.phase)
    }

    @Test
    fun cpuFaultOnEmptyReadingNeedsNoSend() {
        val e = simpleEngine(order = listOf(10), cpu = listOf(6, 7, 8, 9, 11), profile = FAULTY_CPU)
        val s = e.beginRead()
        e.advanceToOffset(s, 3000L)
        val st = e.state
        assertEquals(setOf(Side.CPU), st.lastResult!!.faults)
        assertEquals(0, st.pendingPlayerSends)
        assertEquals(1, st.cpuFaultCount)
        assertEquals("空札でした。相手のお手つき", st.lastResult!!.message)
    }

    @Test
    fun bothLockedOutRemovesTheCardWithoutAScore() {
        val e = simpleEngine(order = listOf(1, 11), profile = FAULTY_CPU)
        val s = e.beginRead()
        e.advanceToOffset(s, 500L)
        assertEquals(1, e.state.cpuFaultCount)
        e.tapCard(Side.CPU, 6)
        e.advanceToOffset(s, 3200L + 1500L)

        val st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        val r = st.lastResult!!
        assertNull(r.takenBy)
        assertEquals(setOf(Side.PLAYER, Side.CPU), r.faults)
        assertTrue(r.transfers.isEmpty())
        assertEquals(listOf(2, 3, 4, 5), st.playerNos)
        assertEquals(listOf(6, 7, 8, 9, 10), st.cpuNos)
        assertEquals(0, st.playerTakenCount)
        assertEquals(0, st.cpuTakenCount)
    }

    @Test
    fun emptyReadingWithoutFaultAdvancesAutomatically() {
        val e = simpleEngine(order = listOf(11, 12))
        val s = e.beginRead()
        e.advanceToOffset(s, 3000L)
        assertEquals(MatchPhase.Result, e.state.phase)
        e.advanceBy(1200L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
    }

    @Test
    fun gameEndsWhenAllFieldCardsAreRead_PlayerWins() {
        val e = simpleEngine(order = listOf(1, 6, 11), player = listOf(1), cpu = listOf(6))
        e.beginRead()
        e.tapCard(Side.PLAYER, 1)
        // 自陣の札が0枚になっても、簡易ルールは場の札がすべて読まれるまで続く
        assertEquals(MatchPhase.Result, e.state.phase)
        e.next()
        assertEquals(MatchPhase.Waiting, e.state.phase)

        e.beginRead()
        e.tapCard(Side.CPU, 6)
        assertEquals(MatchPhase.Result, e.state.phase)
        assertEquals(2, e.state.playerTakenCount)
        e.next()
        // 空札 11 は読まれずに終了
        assertEquals(MatchPhase.Finished(Side.PLAYER), e.state.phase)
        assertEquals(2, e.state.readsDone)
    }

    @Test
    fun gameEndsWhenAllFieldCardsAreRead_CpuWins() {
        val e = simpleEngine(order = listOf(1, 6, 11), player = listOf(1), cpu = listOf(6))
        e.beginRead()
        e.advanceBy(cpuTakeOffset)
        assertEquals(1, e.state.cpuTakenCount)
        e.next()
        e.beginRead()
        e.advanceBy(cpuTakeOffset)
        assertEquals(2, e.state.cpuTakenCount)
        e.next()
        assertEquals(MatchPhase.Finished(Side.CPU), e.state.phase)
    }

    @Test
    fun gameEndsInDrawWhenTakenCountsAreEqual() {
        val e = simpleEngine(order = listOf(1, 6), player = listOf(1), cpu = listOf(6))
        e.beginRead()
        e.tapCard(Side.PLAYER, 1)
        e.next()
        e.beginRead()
        e.advanceBy(cpuTakeOffset)
        assertEquals(MatchPhase.Result, e.state.phase)
        assertEquals(1, e.state.playerTakenCount)
        assertEquals(1, e.state.cpuTakenCount)
        e.next()
        assertEquals(MatchPhase.Finished(null), e.state.phase)
    }

    @Test
    fun cardNobodyTookStillCountsAsRead() {
        // 誰も取れなかった札も読まれた札として場から取り除かれ、残りの札がすべて読まれれば終了する
        val e = simpleEngine(order = listOf(1, 6), player = listOf(1), cpu = listOf(6), profile = FAULTY_CPU)
        e.beginRead()
        e.advanceBy(500L)
        // CPU は友札の 6 に触れてお手つき。プレイヤーも別の陣の 6 に触れてお手つき
        assertEquals(1, e.state.cpuFaultCount)
        e.tapCard(Side.CPU, 6)
        e.advanceBy(60_000L)
        assertEquals(MatchPhase.Result, e.state.phase)
        assertNull(e.state.lastResult!!.takenBy)
        assertTrue(e.state.playerCards.isEmpty())
        e.next()
        assertEquals(MatchPhase.Waiting, e.state.phase)

        // 次の 6 はプレイヤーが取る。場の札がなくなったので 1対0 でプレイヤーの勝ち
        e.beginRead()
        e.tapCard(Side.CPU, 6)
        assertEquals(1, e.state.playerTakenCount)
        e.next()
        assertEquals(MatchPhase.Finished(Side.PLAYER), e.state.phase)
    }

    @Test
    fun lockedOutPlayerCannotTakeAndCpuTakesInstead() {
        val e = simpleEngine(order = listOf(6, 11))
        val s = e.beginRead()
        e.tapCard(Side.PLAYER, 3)
        e.tapCard(Side.CPU, 6)
        assertNull(e.state.lastResult)
        e.advanceToOffset(s, cpuTakeOffset)
        assertEquals(Side.CPU, e.state.lastResult!!.takenBy)
        assertEquals(0, e.state.playerTakenCount)
    }
}
