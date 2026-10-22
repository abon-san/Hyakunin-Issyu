package io.github.abonsan.hyakunin.game.quiz

import io.github.abonsan.hyakunin.data.Poem
import io.github.abonsan.hyakunin.data.Poems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QuizViewModelTest {
    private val answers = mutableListOf<Pair<Int, Boolean>>()

    private fun quiz(vararg nos: Int) = QuizViewModel(
        poems = nos.map(Poems::byNo),
        random = Random(7),
        onAnswered = { poem, correct -> answers += poem.no to correct },
    )

    private val QuizViewModel.state: QuizUiState get() = uiState.value

    /** 今の問題の誤答の札の歌番号（どれか1枚）。 */
    private fun QuizUiState.wrongChoice(): Int = question.choices.first { it.no != question.poem.no }.no

    @Test
    fun eachPoemIsAskedOnceInARandomOrder() {
        val vm = quiz(1, 2, 3, 4, 5)
        val asked = mutableListOf<Poem>()
        repeat(5) {
            asked += vm.state.question.poem
            assertEquals(QuizPhase.Asking, vm.state.phase)
            assertEquals(QUIZ_CHOICE_COUNT, vm.state.question.choices.size)
            vm.answer(vm.state.question.poem.no)
            vm.next()
        }
        assertEquals((1..5).toList(), asked.map { it.no }.sorted())
        assertEquals(QuizPhase.Finished, vm.state.phase)
        assertEquals(5, vm.state.correctCount)
    }

    @Test
    fun theKimarijiIsCountedAmongAllHundredPoems() {
        assertEquals(1, quiz(18).state.question.kimarijiLength)
        assertEquals(6, quiz(31).state.question.kimarijiLength)
    }

    @Test
    fun theReadingOnlyMovesForwardAndStopsAtTheEndOfTheKami() {
        val vm = quiz(18)
        vm.reveal(3)
        vm.reveal(2)
        assertEquals(3, vm.state.revealedKana)
        vm.reveal(100)
        assertEquals(vm.state.question.kamiKanaCount, vm.state.revealedKana)
    }

    @Test
    fun aWrongAnswerIsRecordedWithHowMuchWasRead() {
        val vm = quiz(18, 87)
        val first = vm.state.question.poem
        vm.reveal(4)
        val wrong = vm.state.wrongChoice()
        vm.answer(wrong)
        assertEquals(QuizPhase.Answered(wrong, correct = false, revealedAtAnswer = 4), vm.state.phase)
        assertEquals(0, vm.state.correctCount)
        assertEquals(listOf(first), vm.state.missed)
        assertEquals(listOf(first.no to false), answers)
        // 答えた後は、ほかの札を選んでも・読み進めても変わらない
        vm.answer(first.no)
        vm.reveal(10)
        vm.replay()
        assertEquals(QuizPhase.Answered(wrong, correct = false, revealedAtAnswer = 4), vm.state.phase)
        assertEquals(4, vm.state.revealedKana)
        assertEquals(1, answers.size)
    }

    @Test
    fun nextStartsANewReadingFromTheTop() {
        val vm = quiz(18, 87)
        val readId = vm.state.readId
        // 答える前は次へ進めない
        vm.next()
        assertEquals(0, vm.state.index)
        vm.reveal(5)
        vm.answer(vm.state.question.poem.no)
        assertFalse(vm.state.isLast)
        vm.next()
        assertEquals(1, vm.state.index)
        assertEquals(QuizPhase.Asking, vm.state.phase)
        assertEquals(0, vm.state.revealedKana)
        assertTrue(vm.state.readId > readId)
        assertTrue(vm.state.isLast)
    }

    @Test
    fun replayReadsTheKamiAgainFromTheTop() {
        val vm = quiz(18)
        val readId = vm.state.readId
        vm.reveal(6)
        vm.replay()
        assertEquals(0, vm.state.revealedKana)
        assertEquals(readId + 1, vm.state.readId)
        assertEquals(QuizPhase.Asking, vm.state.phase)
    }

    @Test
    fun restartAsksTheGivenPoemsAgain() {
        val vm = quiz(18, 87, 57)
        repeat(3) {
            val state = vm.state
            // 「すみのえの」だけ間違える
            vm.answer(if (state.question.poem.no == 18) state.wrongChoice() else state.question.poem.no)
            vm.next()
        }
        assertEquals(QuizPhase.Finished, vm.state.phase)
        assertEquals(2, vm.state.correctCount)
        assertEquals(listOf(18), vm.state.missed.map { it.no })
        val readId = vm.state.readId

        vm.restart(vm.state.missed)
        assertEquals(listOf(18), vm.state.questions.map { it.poem.no })
        assertEquals(QuizPhase.Asking, vm.state.phase)
        assertEquals(0, vm.state.correctCount)
        assertEquals(emptyList<Poem>(), vm.state.missed)
        assertTrue(vm.state.readId > readId)
        vm.answer(18)
        // 記録には、間違えた答えと、やり直して正解した答えの両方を渡す
        assertEquals(4, answers.size)
        assertEquals(listOf(18 to false), answers.take(3).filter { !it.second })
        assertEquals(18 to true, answers.last())
    }

    @Test
    fun readingThroughTheShimoRevealsTheWholePoem() {
        val vm = QuizViewModel(listOf(Poems.byNo(18)), readsShimo = true, random = Random(7))
        val question = vm.state.question
        assertTrue(vm.state.readsShimo)
        assertEquals(question.kamiKanaCount + question.shimoKanaCount, vm.state.readKanaCount)
        // 上の句を越えて、下の句の終わりまで出せる
        vm.reveal(question.kamiKanaCount + 3)
        assertEquals(question.kamiKanaCount + 3, vm.state.revealedKana)
        vm.reveal(100)
        assertEquals(vm.state.readKanaCount, vm.state.revealedKana)
        vm.answer(18)
        assertEquals(QuizPhase.Answered(18, correct = true, revealedAtAnswer = vm.state.readKanaCount), vm.state.phase)
        // やり直しても下の句まで読む
        vm.restart(listOf(Poems.byNo(87)))
        assertTrue(vm.state.readsShimo)

        // 上の句だけなら、上の句の終わりで止まる
        val kamiOnly = quiz(18)
        assertEquals(kamiOnly.state.question.kamiKanaCount, kamiOnly.state.readKanaCount)
        kamiOnly.reveal(100)
        assertEquals(kamiOnly.state.question.kamiKanaCount, kamiOnly.state.revealedKana)
    }
}
