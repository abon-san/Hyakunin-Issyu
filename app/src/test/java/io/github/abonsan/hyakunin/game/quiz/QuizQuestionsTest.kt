package io.github.abonsan.hyakunin.game.quiz

import io.github.abonsan.hyakunin.data.Poems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 「札を覚える」の出題範囲と、並べる札の選び方。 */
class QuizQuestionsTest {
    private fun nos(config: QuizConfig, missed: Set<Int> = emptySet()) = quizPoems(config, missed).map { it.no }

    @Test
    fun theWholeRangeIsAllHundredPoems() {
        assertEquals((1..100).toList(), nos(QuizConfig()))
    }

    @Test
    fun eachNumberRangeHasTwentyPoems() {
        for (range in PoemNumberRange.entries - PoemNumberRange.ALL) {
            assertEquals(range.numbers.toList(), nos(QuizConfig(numberRange = range)))
        }
    }

    @Test
    fun theKimarijiGroupsSplitTheHundredPoems() {
        // むすめふさほせ
        assertEquals(listOf(18, 22, 57, 70, 77, 81, 87), nos(QuizConfig(kimarijiGroup = KimarijiGroup.ONE)))
        assertEquals(42, nos(QuizConfig(kimarijiGroup = KimarijiGroup.TWO)).size)
        assertEquals(37, nos(QuizConfig(kimarijiGroup = KimarijiGroup.THREE)).size)
        assertEquals(14, nos(QuizConfig(kimarijiGroup = KimarijiGroup.FOUR_OR_MORE)).size)
    }

    @Test
    fun theConditionsAreCombined() {
        // 1〜20 の一字決まりは「すみのえの」だけ
        assertEquals(listOf(18), nos(QuizConfig(numberRange = PoemNumberRange.FROM_1, kimarijiGroup = KimarijiGroup.ONE)))
        // 間違えた札だけ: 記録のうち、範囲に入るもの
        val missed = setOf(3, 18, 33, 87)
        assertEquals(listOf(3, 18, 33, 87), nos(QuizConfig(missedOnly = true), missed))
        assertEquals(listOf(3, 18), nos(QuizConfig(numberRange = PoemNumberRange.FROM_1, missedOnly = true), missed))
        assertEquals(listOf(18, 87), nos(QuizConfig(kimarijiGroup = KimarijiGroup.ONE, missedOnly = true), missed))
        // 記録がなければ出す札もない
        assertEquals(emptyList<Int>(), nos(QuizConfig(missedOnly = true)))
    }

    @Test
    fun theChoicesAreFourDifferentCardsIncludingTheAnswer() {
        val random = Random(1)
        for (poem in Poems.all) {
            val choices = choicesFor(poem, Poems.all, random)
            assertEquals(QUIZ_CHOICE_COUNT, choices.size)
            assertEquals(QUIZ_CHOICE_COUNT, choices.map { it.no }.toSet().size)
            assertTrue(poem in choices)
        }
    }

    @Test
    fun theDecoysAreThePoemsThatStartAlike() {
        // 「あさぼらけ ありあけの」には「あさぼらけ うじの」と「あさじうの」（出だしが同じ）を必ず混ぜる
        repeat(20) { seed ->
            val choices = choicesFor(Poems.byNo(31), Poems.all, Random(seed)).map { it.no }
            assertTrue("$choices", 64 in choices && 39 in choices)
        }
        // 「おおけなく」と「おおえやま」「おうことの」（読みの出だしは「おお」が同じ）
        repeat(20) { seed ->
            val choices = choicesFor(Poems.byNo(95), Poems.all, Random(seed)).map { it.no }
            assertTrue("$choices", choices.containsAll(listOf(44, 60)))
        }
    }

    @Test
    fun theOrderOfTheChoicesVaries() {
        // 練習と同じく、1つの乱数で続けて選ぶ
        val random = Random(5)
        val positions = (0 until 40).map { choicesFor(Poems.byNo(31), Poems.all, random).indexOfFirst { it.no == 31 } }
        assertEquals(setOf(0, 1, 2, 3), positions.toSet())
    }

    @Test
    fun theKanaAreRevealedAsTheyStart() {
        val starts = intArrayOf(100, 300, 500, 700)
        assertEquals(0, revealedKanaAt(0L, starts))
        assertEquals(0, revealedKanaAt(99L, starts))
        assertEquals(1, revealedKanaAt(100L, starts))
        assertEquals(3, revealedKanaAt(650L, starts))
        assertEquals(4, revealedKanaAt(5_000L, starts))
    }
}
