package io.github.abonsan.hyakunin.ui.match

import androidx.compose.ui.unit.dp
import io.github.abonsan.hyakunin.data.Poems
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.MatchPhase
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.game.Side
import io.github.abonsan.hyakunin.game.Transfer
import io.github.abonsan.hyakunin.game.kanaCount
import io.github.abonsan.hyakunin.ui.Fixtures
import io.github.abonsan.hyakunin.ui.card.TORIFUDA_ASPECT_RATIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 対戦画面の表示用の計算（Compose を使わない部分）。 */
class MatchUiLogicTest {
    @Test
    fun verseNumbersKanaThroughBothVerses() {
        val verse = verseOf(Poems.byNo(1))
        assertEquals(
            listOf(VerseLine("あきのたの", 0), VerseLine("かりおのいおの", 5), VerseLine("とまをあらみ", 12)),
            verse.kami,
        )
        assertEquals(listOf(VerseLine("わがころもでは", 18), VerseLine("つゆにぬれつつ", 25)), verse.shimo)
    }

    @Test
    fun everyPoemHasThreeUpperAndTwoLowerPhrasesThatFitTheLayout() {
        for (poem in Poems.all) {
            val verse = verseOf(poem)
            assertEquals("${poem.no}", 3, verse.kami.size)
            assertEquals("${poem.no}", 2, verse.shimo.size)
            val lines = verse.kami + verse.shimo
            assertTrue("${poem.no}", lines.all { it.text.length <= MAX_PHRASE_KANA })
            assertEquals("${poem.no}", kanaCount(poem.kamiYomi) + kanaCount(poem.shimoYomi), lines.sumOf { it.text.length })
        }
    }

    @Test
    fun kimarijiIsTheHeadOfTheUpperVerse() {
        assertEquals("あきの", kimarijiOf(Poems.byNo(1), 3))
        assertEquals("む", kimarijiOf(Poems.byNo(87), 1))
        assertEquals("あさぼらけあ", kimarijiOf(Poems.byNo(31), 6))
    }

    @Test
    fun kanaToneMarksKimarijiAndOverRead() {
        // 決まり字3文字、7文字目で取った
        val tones = (0 until 9).map { kanaTone(it, kimarijiLength = 3, revealedAtTake = 7) }
        assertEquals(List(3) { KanaTone.Kimariji } + List(4) { KanaTone.OverRead } + List(2) { KanaTone.Unread }, tones)
        // 決まり字が出る前に取った: 赤は無い
        assertEquals(
            listOf(KanaTone.Kimariji, KanaTone.Kimariji, KanaTone.Unread),
            (0 until 3).map { kanaTone(it, kimarijiLength = 2, revealedAtTake = 1) },
        )
        // 取られなかった札は決まり字だけ緑で、残りは墨
        assertEquals(
            listOf(KanaTone.Kimariji, KanaTone.Plain, KanaTone.Plain),
            (0 until 3).map { kanaTone(it, kimarijiLength = 1, revealedAtTake = null) },
        )
    }

    @Test
    fun takeSummaryDescribesWhenTheCardWasTaken() {
        fun summary(kimariji: Int, taken: Int?) = takeSummary(
            Fixtures.result(1, kimariji, taken, takenBy = Side.PLAYER, takenFrom = Side.PLAYER, message = ""),
        )
        assertEquals("決まり字は 3 字・7 字目で取りました", summary(3, 7))
        assertEquals("決まり字は 3 字・3 字目で取りました", summary(3, 3))
        assertEquals("決まり字が出る前に取りました（2 字目）", summary(3, 2))
        assertEquals("決まり字は 3 字", summary(3, null))
    }

    @Test
    fun resultMessageIsSplitIntoHeadlineAndDetail() {
        assertEquals(
            ResultMessageParts("敵陣の札を取りました", "自陣から1枚送ってください"),
            splitResultMessage("敵陣の札を取りました。自陣から1枚送ってください"),
        )
        assertEquals(
            ResultMessageParts("誰も取れませんでした", "お手つき。相手のお手つき。お互いのお手つきなので札は動きません"),
            splitResultMessage("誰も取れませんでした。お手つき。相手のお手つき。お互いのお手つきなので札は動きません"),
        )
        assertEquals(ResultMessageParts("空札でした", null), splitResultMessage("空札でした"))
        assertEquals(ResultMessageParts("空札でした", null), splitResultMessage("空札でした。"))
    }

    @Test
    fun resultNotesListFaultsAndTransfers() {
        val result = Fixtures.result(
            no = 87,
            kimarijiLength = 1,
            revealedAtTake = 4,
            takenBy = Side.CPU,
            takenFrom = Side.PLAYER,
            faults = setOf(Side.PLAYER),
            transfers = listOf(
                Transfer(Side.CPU, Side.PLAYER, 12, Transfer.Reason.SEND_AFTER_TAKE),
                Transfer(Side.CPU, Side.PLAYER, 70, Transfer.Reason.FAULT_PENALTY),
            ),
            message = "",
        )
        assertEquals(
            listOf(
                ResultNote("お手つき", "あなた", isFault = true),
                ResultNote("送り札", "相手 → あなた（1枚）"),
                ResultNote("罰の送り札", "相手 → あなた（1枚）"),
            ),
            resultNotes(result, RuleMode.COMPETITIVE),
        )
    }

    @Test
    fun resultNotesForBothFaultsAndSafeMisses() {
        val both = Fixtures.result(
            61, 2, null, takenBy = null, takenFrom = null, faults = setOf(Side.CPU, Side.PLAYER), message = "",
        )
        assertEquals(
            listOf(ResultNote("お手つき", "あなた・相手（罰の送り札は相殺）", isFault = true)),
            resultNotes(both, RuleMode.COMPETITIVE),
        )
        // 簡易ルールでは札が動かないので相殺とは書かない
        assertEquals(
            listOf(ResultNote("お手つき", "あなた・相手", isFault = true)),
            resultNotes(both, RuleMode.SIMPLE),
        )
        val safe = Fixtures.result(
            61, 2, 5, takenBy = Side.CPU, takenFrom = Side.PLAYER, safeMisses = setOf(Side.PLAYER), message = "",
        )
        assertEquals(
            listOf(ResultNote("空振り", "あなたは同じ陣の別の札に触れました（お手つきなし）")),
            resultNotes(safe, RuleMode.COMPETITIVE),
        )
    }

    @Test
    fun lockoutNoticeTellsFaultFromSafeMiss() {
        // 自陣の61番を読んでいる
        val locked = Scenes.reading().copy(playerLockedOut = true)
        assertEquals("お手つき", lockoutNotice(locked, touchedSide = Side.CPU)?.stamp)
        val safe = lockoutNotice(locked, touchedSide = Side.PLAYER)!!
        assertNull(safe.stamp)
        assertEquals("同じ陣の札なのでお手つきにはなりません", safe.detail)
        // どちらに触れたか分からないときは、取れないことだけを伝える
        assertEquals(LockoutNotice(stamp = null, message = "この札はもう取れません"), lockoutNotice(locked, null))
        // 簡易ルールでは同じ陣でもお手つき
        val simple = locked.copy(config = GameConfig(ruleMode = RuleMode.SIMPLE))
        assertEquals("お手つき", lockoutNotice(simple, touchedSide = Side.PLAYER)?.stamp)
        // 空札（場にない35番）に触れた
        val empty = locked.copy(reading = Fixtures.reading(no = 35, readNumber = 4, totalReads = 20, revealed = 3))
        assertEquals("お手つき", lockoutNotice(empty, touchedSide = Side.PLAYER)?.stamp)
        // ロックアウトしていない・読み上げ中でないときは出さない
        assertNull(lockoutNotice(Scenes.reading(), Side.CPU))
        assertNull(lockoutNotice(locked.copy(phase = MatchPhase.Result), Side.CPU))
    }

    @Test
    fun readCountIncludesTheCardBeingRead() {
        assertEquals(4, readCount(Scenes.reading()))
        assertEquals(0, readCount(Scenes.waiting()))
        assertEquals(5, readCount(Scenes.result()))
    }

    @Test
    fun fiveCardsAndGapsFitTheWidth() {
        // 411dp 幅: (411 - 24 - 24) / 5
        assertEquals(72.6f, fieldCardWidth(411.dp, 891.dp).value, 0.01f)
        // 低い画面では高さで抑える（札の高さが画面の 12%）
        val short = fieldCardWidth(360.dp, 640.dp)
        assertEquals(640f * 0.12f, (short / TORIFUDA_ASPECT_RATIO).value, 0.01f)
        // 広い画面でも上限で止める
        assertEquals(84f, fieldCardWidth(800.dp, 1280.dp).value, 0.01f)
    }

    @Test
    fun verseFontSizeFitsTheLongestPhraseAndAllLines() {
        val wide = fittingVerseFontSize(width = 380.dp, height = 600.dp, max = 34.dp, min = 17.dp)
        assertEquals(34f, wide.value, 0.01f)
        val narrow = fittingVerseFontSize(width = 200.dp, height = 600.dp, max = 34.dp, min = 17.dp)
        assertTrue(narrow.value * MAX_PHRASE_KANA * 1.04f <= 200f)
        val low = fittingVerseFontSize(width = 380.dp, height = 150.dp, max = 34.dp, min = 17.dp)
        assertTrue(low.value * 1.42f * 5 <= 150f)
        assertEquals(17f, fittingVerseFontSize(width = 50.dp, height = 50.dp, max = 34.dp, min = 17.dp).value, 0.01f)
    }

    @Test
    fun finishReasonExplainsTheOutcome() {
        assertEquals("自陣の札が先になくなりました", finishReason(Scenes.finished(), Side.PLAYER))
        val cpuWon = Scenes.finished(Side.CPU).copy(playerCards = Fixtures.cards(23, 46), cpuCards = emptyList())
        assertEquals("相手の陣の札が先になくなりました", finishReason(cpuWon, Side.CPU))
        val exhausted = Scenes.finished(Side.PLAYER).copy(playerCards = Fixtures.cards(23), cpuCards = Fixtures.cards(46, 99))
        assertEquals("すべての札を読み終えました", finishReason(exhausted, Side.PLAYER))
        assertEquals("取った札　あなた 5 枚 ・ 相手 5 枚", finishReason(Scenes.draw(), null))
    }
}
