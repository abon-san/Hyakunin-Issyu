package io.github.abonsan.hyakunin.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 配札と読み順の準備。 */
class MatchEngineSetupTest {
    private fun create(empty: Int, seed: Int, mode: RuleMode = RuleMode.COMPETITIVE) =
        MatchEngine.create(
            config = GameConfig(ruleMode = mode, emptyCardCount = empty),
            random = Random(seed),
            poems = SYNTHETIC_POEMS,
        )

    @Test
    fun dealsFiveCardsPerSideAndEmptyCards() {
        for (empty in listOf(0, 1, 10, 45, 90)) {
            val e = create(empty, seed = empty)
            val st = e.state
            assertEquals(GameConfig.CARDS_PER_SIDE, st.playerCards.size)
            assertEquals(GameConfig.CARDS_PER_SIDE, st.cpuCards.size)
            assertEquals(10 + empty, st.totalReads)
            assertEquals(10 + empty, e.readOrder.size)

            // 読み札は場の10枚 + 空札で、重複しない
            assertEquals(e.readOrder.size, e.readOrder.toSet().size)
            val field = (st.playerNos + st.cpuNos).toSet()
            assertEquals(10, field.size)
            assertTrue(e.readOrder.containsAll(field))
            assertEquals(empty, e.readOrder.count { it !in field })
            assertTrue(e.readOrder.all { it in 1..100 })
            // 取り札には下の句の仮名が入る
            assertTrue(st.playerCards.all { it.shimoKana == "しも${it.poemNo}" })
            assertTrue(st.playerCards.none { it.isNew })
            assertEquals(MatchPhase.Waiting, st.phase)
        }
    }

    @Test
    fun layoutIsDeterministicForTheSameSeed() {
        val a = create(10, seed = 42)
        val b = create(10, seed = 42)
        assertEquals(a.state.playerNos, b.state.playerNos)
        assertEquals(a.state.cpuNos, b.state.cpuNos)
        assertEquals(a.readOrder, b.readOrder)

        val c = create(10, seed = 43)
        assertTrue(a.readOrder != c.readOrder || a.state.playerNos != c.state.playerNos)
    }

    @Test
    fun readOrderIsShuffled() {
        val e = create(10, seed = 1)
        val st = e.state
        val fieldFirst = st.playerNos + st.cpuNos
        // 場の札が先頭に固まっていない（空札が混ざっている）
        assertNotEquals(fieldFirst + e.readOrder.filter { it !in fieldFirst }, e.readOrder)
    }

    @Test
    fun clampsWhenThereAreTooFewPoems() {
        // 暫定データなど、100首に満たないときは置ける範囲に枚数を減らす
        val three = SYNTHETIC_POEMS.take(3)
        val e = MatchEngine.create(GameConfig(emptyCardCount = 10), Random(0), three)
        assertEquals(1, e.state.playerCards.size)
        assertEquals(1, e.state.cpuCards.size)
        assertEquals(3, e.state.totalReads)

        val none = MatchEngine.create(GameConfig(emptyCardCount = 0), Random(0), emptyList())
        assertEquals(0, none.state.totalReads)
    }

    @Test
    fun worksWithBundledPoems() {
        val e = MatchEngine.create(GameConfig(), Random(0))
        assertTrue(e.state.totalReads >= 0)
    }

    @Test
    fun timingFollowsReadingSpeed() {
        for (speed in ReadingSpeed.entries) {
            val e = MatchEngine.create(
                GameConfig(readingSpeed = speed, emptyCardCount = 0),
                Random(0),
                SYNTHETIC_POEMS,
            )
            e.startReading()
            val s = e.session!!
            assertEquals(speed.charMillis, s.kanaAt[0] - s.startMillis)
        }
    }

    @Test
    fun readTimingDefaults() {
        val t = ReadTiming.of(ReadingSpeed.NORMAL)
        assertEquals(230L, t.charMillis)
        assertEquals(1200L, t.emptyTailMillis)
        assertEquals(1500L, t.noTakerMillis)
        assertEquals(1200L, t.resultAutoMillis)
        assertEquals(500L, t.cpuLateLimitMillis)
        assertEquals(230L, t.phraseGapMillis)
        assertEquals(690L, t.kamiShimoGapMillis)
    }
}
