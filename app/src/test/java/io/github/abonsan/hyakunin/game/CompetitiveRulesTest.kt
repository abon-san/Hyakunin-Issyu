package io.github.abonsan.hyakunin.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 競技かるたルール。CPU は既定で [SLOW_CPU]（下の句の後 500ms に取る）なので、
 * プレイヤーが先に触れなければ読みの 3700ms 後に CPU が出札を取る。乱数は [ZeroRandom]（常に先頭の候補を選ぶ）。
 */
class CompetitiveRulesTest {
    /** 下の句を表示し終えた 500ms 後。SLOW_CPU が取る時刻。 */
    private val cpuTakeOffset = 3200L + 500L

    @Test
    fun playerTakesCardOnOwnSide() {
        val e = layoutEngine(order = listOf(1, 11))
        val s = e.beginRead()
        e.advanceToOffset(s, 250L)
        e.tapCard(Side.PLAYER, 1)

        val st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        assertEquals(listOf(2, 3, 4, 5), st.playerNos)
        assertEquals(listOf(6, 7, 8, 9, 10), st.cpuNos)
        assertEquals(1, st.playerTakenCount)
        assertEquals(0, st.cpuTakenCount)
        assertEquals(0, st.pendingPlayerSends)
        assertEquals(1, st.readsDone)
        assertFalse(st.playerLockedOut)
        val r = st.lastResult!!
        assertEquals(Side.PLAYER, r.takenBy)
        assertEquals(Side.PLAYER, r.takenFrom)
        assertEquals(2, r.revealedAtTake)
        assertEquals(2, r.kimarijiLength)
        assertFalse(r.isEmptyCard)
        assertTrue(r.faults.isEmpty())
        assertTrue(r.transfers.isEmpty())
        assertEquals("自陣の札を取りました", r.message)
    }

    @Test
    fun canTakeBeforeKimarijiIsRevealed() {
        val e = layoutEngine(order = listOf(1))
        val s = e.beginRead()
        // 決まり字（2文字）が出る前、1文字だけ表示された時点でも、正しい札なら取れる
        e.advanceToOffset(s, 100L)
        e.tapCard(Side.PLAYER, 1)
        assertEquals(Side.PLAYER, e.state.lastResult!!.takenBy)
        assertEquals(1, e.state.lastResult!!.revealedAtTake)
    }

    @Test
    fun playerTakesEnemyCardAndChoosesCardToSend() {
        val e = layoutEngine(order = listOf(6, 11))
        val s = e.beginRead()
        e.advanceToOffset(s, 300L)
        e.tapCard(Side.CPU, 6)

        var st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        assertEquals(listOf(7, 8, 9, 10), st.cpuNos)
        assertEquals(listOf(1, 2, 3, 4, 5), st.playerNos)
        assertEquals(1, st.pendingPlayerSends)
        assertEquals(Side.PLAYER, st.lastResult!!.takenBy)
        assertEquals(Side.CPU, st.lastResult!!.takenFrom)
        assertTrue(st.lastResult!!.transfers.isEmpty())
        assertEquals("敵陣の札を取りました。自陣から1枚送ってください", st.lastResult!!.message)

        // 送る札を選ぶまで次へ進めない。敵陣の札のタップは無視される
        e.next()
        assertEquals(MatchPhase.Result, e.state.phase)
        e.tapCard(Side.CPU, 7)
        assertEquals(listOf(7, 8, 9, 10), e.state.cpuNos)
        assertEquals(1, e.state.pendingPlayerSends)
        // 自陣にない札は無視される
        e.tapCard(Side.PLAYER, 99)
        assertEquals(1, e.state.pendingPlayerSends)

        e.tapCard(Side.PLAYER, 3)
        st = e.state
        assertEquals(0, st.pendingPlayerSends)
        assertEquals(listOf(1, 2, 4, 5), st.playerNos)
        assertEquals(listOf(7, 8, 9, 10, 3), st.cpuNos)
        assertTrue(st.cpuCards.last().isNew)
        assertFalse(st.cpuCards.first().isNew)
        assertEquals(
            listOf(Transfer(Side.PLAYER, Side.CPU, 3, Transfer.Reason.SEND_AFTER_TAKE)),
            st.lastResult!!.transfers,
        )

        // 送った後は札を選べない
        e.tapCard(Side.PLAYER, 4)
        assertEquals(listOf(1, 2, 4, 5), e.state.playerNos)

        // 次の読みの開始で強調表示が戻る
        e.next()
        assertEquals(MatchPhase.Waiting, e.state.phase)
        assertTrue(e.state.cpuCards.last().isNew)
        e.beginRead()
        assertFalse(e.state.cpuCards.last().isNew)
    }

    @Test
    fun cpuTakesPlayerCardAndSendsOneToPlayer() {
        val e = layoutEngine(order = listOf(1, 11))
        val s = e.beginRead()
        e.advanceToOffset(s, cpuTakeOffset)

        val st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        assertEquals(1, st.cpuTakenCount)
        assertEquals(0, st.pendingPlayerSends)
        // CPU の陣からランダムに選んだ札（ZeroRandom では先頭）が即座に移る
        assertEquals(listOf(2, 3, 4, 5, 6), st.playerNos)
        assertEquals(listOf(7, 8, 9, 10), st.cpuNos)
        assertTrue(st.playerCards.last().isNew)
        val r = st.lastResult!!
        assertEquals(Side.CPU, r.takenBy)
        assertEquals(Side.PLAYER, r.takenFrom)
        assertEquals(26, r.revealedAtTake)
        assertEquals(
            listOf(Transfer(Side.CPU, Side.PLAYER, 6, Transfer.Reason.SEND_AFTER_TAKE)),
            r.transfers,
        )
        assertEquals("相手が自陣の札を取りました。相手から1枚送られます", r.message)
    }

    @Test
    fun cpuPicksCardToSendAtRandom() {
        // 送られる札は CPU の陣の札から乱数で選ばれ、乱数によって変わる
        val sent = (0 until 50).map { seed ->
            val e = layoutEngine(order = listOf(1), random = Random(seed))
            val s = e.beginRead()
            e.advanceToOffset(s, cpuTakeOffset)
            val st = e.state
            assertEquals(5, st.playerCards.size)
            assertEquals(4, st.cpuCards.size)
            val moved = st.lastResult!!.transfers.single().poemNo
            assertTrue(moved in 6..10)
            assertTrue(moved !in st.cpuNos)
            moved
        }
        assertTrue(sent.toSet().size > 1)
    }

    @Test
    fun cpuTakesCardOnItsOwnSideWithoutTransfers() {
        val e = layoutEngine(order = listOf(6))
        val s = e.beginRead()
        e.advanceToOffset(s, cpuTakeOffset)

        val st = e.state
        assertEquals(listOf(7, 8, 9, 10), st.cpuNos)
        assertEquals(listOf(1, 2, 3, 4, 5), st.playerNos)
        assertTrue(st.lastResult!!.transfers.isEmpty())
        assertEquals(Side.CPU, st.lastResult!!.takenFrom)
        assertEquals("相手が敵陣の札を取りました", st.lastResult!!.message)
    }

    @Test
    fun touchingOtherSidesCardIsFaultAndPenaltyIsApplied() {
        val e = layoutEngine(order = listOf(1, 11))
        val s = e.beginRead()
        e.advanceToOffset(s, 500L)
        // 出札は自陣の 1。敵陣の 6 に触れるとお手つき
        e.tapCard(Side.CPU, 6)
        var st = e.state
        assertEquals(MatchPhase.Reading, st.phase)
        assertTrue(st.playerLockedOut)
        assertEquals(1, st.playerFaultCount)
        assertEquals(0, st.cpuFaultCount)

        // ロックアウト後は正しい札でも取れない。触れ直してもお手つきは増えない
        e.tapCard(Side.PLAYER, 1)
        e.tapCard(Side.CPU, 7)
        assertEquals(MatchPhase.Reading, e.state.phase)
        assertEquals(1, e.state.playerFaultCount)

        e.advanceToOffset(s, cpuTakeOffset)
        st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        assertFalse(st.playerLockedOut)
        // CPU が自陣の札を取った分と、お手つきの罰の分で2枚送られる
        assertEquals(listOf(2, 3, 4, 5, 6, 7), st.playerNos)
        assertEquals(listOf(8, 9, 10), st.cpuNos)
        val r = st.lastResult!!
        assertEquals(setOf(Side.PLAYER), r.faults)
        assertTrue(r.safeMisses.isEmpty())
        assertEquals(
            listOf(
                Transfer(Side.CPU, Side.PLAYER, 6, Transfer.Reason.SEND_AFTER_TAKE),
                Transfer(Side.CPU, Side.PLAYER, 7, Transfer.Reason.FAULT_PENALTY),
            ),
            r.transfers,
        )
        assertEquals("相手が自陣の札を取りました。お手つき！相手から2枚送られます", r.message)
    }

    @Test
    fun touchingAnotherCardOnTheSameSideIsSafe() {
        val e = layoutEngine(order = listOf(1, 11))
        val s = e.beginRead()
        e.advanceToOffset(s, 500L)
        // 出札は自陣の 1。同じ自陣の 2 に触れてもお手つきにならない（その読みではもう取れない）
        e.tapCard(Side.PLAYER, 2)
        var st = e.state
        assertTrue(st.playerLockedOut)
        assertEquals(0, st.playerFaultCount)

        e.tapCard(Side.PLAYER, 1)
        assertEquals(MatchPhase.Reading, e.state.phase)

        e.advanceToOffset(s, cpuTakeOffset)
        st = e.state
        val r = st.lastResult!!
        assertEquals(Side.CPU, r.takenBy)
        assertTrue(r.faults.isEmpty())
        assertEquals(setOf(Side.PLAYER), r.safeMisses)
        // 罰はなく、CPU が自陣の札を取った分の1枚だけ送られる
        assertEquals(listOf(2, 3, 4, 5, 6), st.playerNos)
        assertEquals(0, st.playerFaultCount)
        assertEquals(
            "相手が自陣の札を取りました。相手から1枚送られます。お手つきにはなりませんが、この読みでは取れません",
            r.message,
        )
    }

    @Test
    fun safeMissOnEnemySideCard() {
        val e = layoutEngine(order = listOf(6))
        val s = e.beginRead()
        e.tapCard(Side.CPU, 7)
        assertTrue(e.state.playerLockedOut)
        assertEquals(0, e.state.playerFaultCount)
        e.advanceToOffset(s, cpuTakeOffset)
        val r = e.state.lastResult!!
        assertEquals(setOf(Side.PLAYER), r.safeMisses)
        assertTrue(r.faults.isEmpty())
        assertTrue(r.transfers.isEmpty())
        assertEquals(listOf(1, 2, 3, 4, 5), e.state.playerNos)
    }

    @Test
    fun touchingCardOnEmptyReadingIsFault() {
        val e = layoutEngine(order = listOf(11, 12))
        val s = e.beginRead()
        e.advanceToOffset(s, 500L)
        // 空札なので、どの札に触れてもお手つき（自陣の札でも）
        e.tapCard(Side.PLAYER, 2)
        assertEquals(1, e.state.playerFaultCount)
        assertTrue(e.state.playerLockedOut)

        e.advanceToOffset(s, 1800L + 1200L)
        val st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        val r = st.lastResult!!
        assertTrue(r.isEmptyCard)
        assertEquals(setOf(Side.PLAYER), r.faults)
        assertEquals(listOf(Transfer(Side.CPU, Side.PLAYER, 6, Transfer.Reason.FAULT_PENALTY)), r.transfers)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), st.playerNos)
        assertEquals(listOf(7, 8, 9, 10), st.cpuNos)
        assertEquals("空札でした。お手つき！相手から1枚送られます", r.message)

        // お手つきがあったときは自動では進まない
        e.advanceBy(60_000L)
        assertEquals(MatchPhase.Result, e.state.phase)
        e.next()
        assertEquals(MatchPhase.Waiting, e.state.phase)
    }

    @Test
    fun faultsOnBothSidesCancelEachOtherOnEmptyReading() {
        // 空札 10 の友札（先頭が同じ）に CPU が必ず触れる
        val e = layoutEngine(
            order = listOf(10),
            cpu = listOf(6, 7, 8, 9, 11),
            profile = FAULTY_CPU,
        )
        val s = e.beginRead()
        e.advanceToOffset(s, 500L)
        assertEquals(1, e.state.cpuFaultCount)
        e.tapCard(Side.CPU, 6)
        e.advanceToOffset(s, 3000L)

        val st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        val r = st.lastResult!!
        assertEquals(setOf(Side.PLAYER, Side.CPU), r.faults)
        assertTrue(r.transfers.isEmpty())
        assertEquals(0, st.pendingPlayerSends)
        assertEquals(5, st.playerCards.size)
        assertEquals(5, st.cpuCards.size)
        assertEquals(
            "空札でした。あなたのお手つき。相手のお手つき。お互いのお手つきなので札は動きません",
            r.message,
        )
    }

    @Test
    fun cpuOnlyFaultOnEmptyReadingMakesPlayerSend() {
        val e = layoutEngine(order = listOf(10, 12), cpu = listOf(6, 7, 8, 9, 11), profile = FAULTY_CPU)
        val s = e.beginRead()
        e.advanceToOffset(s, 3000L)

        var st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        assertEquals(setOf(Side.CPU), st.lastResult!!.faults)
        assertEquals(1, st.pendingPlayerSends)
        assertTrue(st.lastResult!!.transfers.isEmpty())
        assertEquals("空札でした。相手のお手つき。自陣から1枚送ってください", st.lastResult!!.message)

        // お手つきがあったので自動では進まず、送る札を選ぶまで次へも進めない
        e.advanceBy(10_000L)
        e.next()
        assertEquals(MatchPhase.Result, e.state.phase)

        e.tapCard(Side.PLAYER, 4)
        st = e.state
        assertEquals(listOf(1, 2, 3, 5), st.playerNos)
        assertEquals(listOf(6, 7, 8, 9, 11, 4), st.cpuNos)
        assertEquals(
            listOf(Transfer(Side.PLAYER, Side.CPU, 4, Transfer.Reason.FAULT_PENALTY)),
            st.lastResult!!.transfers,
        )
        e.next()
        assertEquals(MatchPhase.Waiting, e.state.phase)
    }

    @Test
    fun takingEnemyCardAfterCpuFaultRequiresTwoSends() {
        // CPU は友札（自陣の 1）に触れてお手つき。プレイヤーが敵陣の 6 を取ると、2枚送る
        val e = layoutEngine(order = listOf(6, 11), profile = FAULTY_CPU)
        val s = e.beginRead()
        e.advanceToOffset(s, 500L)
        assertEquals(1, e.state.cpuFaultCount)
        e.tapCard(Side.CPU, 6)

        var st = e.state
        assertEquals(2, st.pendingPlayerSends)
        assertEquals(setOf(Side.CPU), st.lastResult!!.faults)
        assertEquals(
            "敵陣の札を取りました。相手のお手つき。自陣から2枚送ってください",
            st.lastResult!!.message,
        )
        e.tapCard(Side.PLAYER, 2)
        assertEquals(1, e.state.pendingPlayerSends)
        e.next()
        assertEquals(MatchPhase.Result, e.state.phase)
        e.tapCard(Side.PLAYER, 4)
        st = e.state
        assertEquals(0, st.pendingPlayerSends)
        assertEquals(
            listOf(
                Transfer(Side.PLAYER, Side.CPU, 2, Transfer.Reason.SEND_AFTER_TAKE),
                Transfer(Side.PLAYER, Side.CPU, 4, Transfer.Reason.FAULT_PENALTY),
            ),
            st.lastResult!!.transfers,
        )
        assertEquals(listOf(1, 3, 5), st.playerNos)
        assertEquals(listOf(7, 8, 9, 10, 2, 4), st.cpuNos)
    }

    @Test
    fun faultsOnBothSidesCancelWhenNobodyTakesFieldCard() {
        // 出札は敵陣の 6。CPU は自陣の友札 1（違う陣）に、プレイヤーも自陣の 1 に触れてお手つき
        val e = layoutEngine(order = listOf(6), profile = FAULTY_CPU)
        val s = e.beginRead()
        e.advanceToOffset(s, 500L)
        e.tapCard(Side.PLAYER, 1)
        e.advanceToOffset(s, 3200L + 1500L)

        val st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        val r = st.lastResult!!
        assertNull(r.takenBy)
        assertEquals(setOf(Side.PLAYER, Side.CPU), r.faults)
        assertTrue(r.transfers.isEmpty())
        assertEquals(0, st.pendingPlayerSends)
        // 出札だけが場から取り除かれる
        assertEquals(listOf(1, 2, 3, 4, 5), st.playerNos)
        assertEquals(listOf(7, 8, 9, 10), st.cpuNos)
    }

    @Test
    fun takingTheLastCardWins() {
        val e = layoutEngine(order = listOf(1, 11), player = listOf(1))
        val s = e.beginRead()
        e.advanceToOffset(s, 300L)
        e.tapCard(Side.PLAYER, 1)

        // まず結果を見せ、next で終了する
        var st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        assertTrue(st.playerCards.isEmpty())
        assertEquals("自陣の札を取りました", st.lastResult!!.message)
        e.advanceBy(10_000L)
        assertEquals(MatchPhase.Result, e.state.phase)
        e.next()
        st = e.state
        assertEquals(MatchPhase.Finished(Side.PLAYER), st.phase)
        assertEquals(1, st.playerTakenCount)
        // 終了後は何も起きない
        e.tapCard(Side.PLAYER, 1)
        e.advanceBy(10_000L)
        assertEquals(MatchPhase.Finished(Side.PLAYER), e.state.phase)
    }

    @Test
    fun cpuTakingItsLastCardWins() {
        val e = layoutEngine(order = listOf(6, 11), cpu = listOf(6))
        val s = e.beginRead()
        e.advanceToOffset(s, cpuTakeOffset)
        assertEquals(MatchPhase.Result, e.state.phase)
        assertTrue(e.state.cpuCards.isEmpty())
        e.next()
        assertEquals(MatchPhase.Finished(Side.CPU), e.state.phase)
    }

    @Test
    fun cpuSendsItsLastCardWhenItTakesPlayersCard() {
        // 自陣の札を取った CPU は1枚送る。持っている1枚を送って自陣が0枚になり、CPU の勝ち
        val e = layoutEngine(order = listOf(1, 11), cpu = listOf(6))
        val s = e.beginRead()
        e.advanceToOffset(s, cpuTakeOffset)

        val st = e.state
        assertTrue(st.cpuCards.isEmpty())
        assertEquals(listOf(2, 3, 4, 5, 6), st.playerNos)
        assertEquals(1, st.lastResult!!.transfers.size)
        e.next()
        assertEquals(MatchPhase.Finished(Side.CPU), e.state.phase)
    }

    @Test
    fun sendsAreLimitedToCardsInHand() {
        // プレイヤーがお手つきし、CPU が自陣の札を取ると、本来2枚送るが CPU は1枚しか持っていない
        val e = layoutEngine(order = listOf(1, 11), cpu = listOf(6))
        val s = e.beginRead()
        e.tapCard(Side.CPU, 6)
        e.advanceToOffset(s, cpuTakeOffset)

        val st = e.state
        assertEquals(listOf(Transfer(Side.CPU, Side.PLAYER, 6, Transfer.Reason.SEND_AFTER_TAKE)), st.lastResult!!.transfers)
        assertEquals(listOf(2, 3, 4, 5, 6), st.playerNos)
        assertTrue(st.cpuCards.isEmpty())
        assertEquals("相手が自陣の札を取りました。お手つき！相手から1枚送られます", st.lastResult!!.message)
        e.next()
        assertEquals(MatchPhase.Finished(Side.CPU), e.state.phase)
    }

    @Test
    fun playerSendsOnlyWhatItHas() {
        // プレイヤーの札は1枚だけ。CPU のお手つきがあっても送るのは1枚まで。送って0枚になれば勝ち
        val e = layoutEngine(order = listOf(6, 11), player = listOf(1), profile = FAULTY_CPU)
        val s = e.beginRead()
        e.advanceToOffset(s, 500L)
        e.tapCard(Side.CPU, 6)

        var st = e.state
        assertEquals(1, st.pendingPlayerSends)
        assertEquals("敵陣の札を取りました。相手のお手つき。自陣から1枚送ってください", st.lastResult!!.message)
        // 送る札を選ぶまでは勝敗が付かない
        e.next()
        assertEquals(MatchPhase.Result, e.state.phase)

        e.tapCard(Side.PLAYER, 1)
        st = e.state
        assertTrue(st.playerCards.isEmpty())
        assertEquals(0, st.pendingPlayerSends)
        e.next()
        assertEquals(MatchPhase.Finished(Side.PLAYER), e.state.phase)
    }

    @Test
    fun takingEnemyLastCardDoesNotEndGameBecauseOfTheSentCard() {
        // 敵陣の最後の1枚を取っても、1枚送ることで敵陣は0枚でなくなる
        val e = layoutEngine(order = listOf(6, 11), cpu = listOf(6))
        val s = e.beginRead()
        e.advanceToOffset(s, 200L)
        e.tapCard(Side.CPU, 6)
        assertTrue(e.state.cpuCards.isEmpty())
        assertEquals(1, e.state.pendingPlayerSends)
        e.tapCard(Side.PLAYER, 2)

        assertEquals(listOf(2), e.state.cpuNos)
        assertEquals(listOf(1, 3, 4, 5), e.state.playerNos)
        e.next()
        assertEquals(MatchPhase.Waiting, e.state.phase)
    }

    @Test
    fun readsRunningOutDecidesByRemainingCards() {
        // 読み札が尽きても決着が付かないとき、残りが少ない方の勝ち
        val e = layoutEngine(order = listOf(11), player = listOf(1, 2, 3), cpu = listOf(6, 7))
        val s = e.beginRead()
        e.advanceToOffset(s, 3000L)
        assertEquals(MatchPhase.Result, e.state.phase)
        // 決着が付いているので自動では進まない
        e.advanceBy(10_000L)
        assertEquals(MatchPhase.Result, e.state.phase)
        e.next()
        assertEquals(MatchPhase.Finished(Side.CPU), e.state.phase)
    }

    @Test
    fun readsRunningOutWithEqualCardsIsDraw() {
        val e = layoutEngine(order = listOf(11, 12), player = listOf(1, 2), cpu = listOf(6, 7))
        e.beginRead()
        e.advanceBy(3000L)
        assertEquals(MatchPhase.Result, e.state.phase)
        // 1回目は決着が付かないので自動で進む
        e.advanceBy(1200L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        e.beginRead()
        e.advanceBy(3000L)
        assertEquals(MatchPhase.Result, e.state.phase)
        assertEquals(2, e.state.readsDone)
        e.next()
        assertEquals(MatchPhase.Finished(null), e.state.phase)
    }

    @Test
    fun readsRunningOutWithFewerPlayerCardsWinsForPlayer() {
        val e = layoutEngine(order = listOf(11), player = listOf(1), cpu = listOf(6, 7))
        e.beginRead()
        e.advanceBy(3000L)
        e.next()
        assertEquals(MatchPhase.Finished(Side.PLAYER), e.state.phase)
    }

    @Test
    fun lastResultKeepsTakeInfoInFinishedPhase() {
        val e = layoutEngine(order = listOf(1), player = listOf(1))
        e.beginRead()
        e.tapCard(Side.PLAYER, 1)
        e.next()
        val st = e.state
        assertEquals(MatchPhase.Finished(Side.PLAYER), st.phase)
        assertEquals(Side.PLAYER, st.lastResult!!.takenBy)
        assertEquals(1, st.reading!!.poem.no)
        assertEquals(1, st.readsDone)
    }
}
