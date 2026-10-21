package io.github.abonsan.hyakunin.game.quiz

import androidx.lifecycle.ViewModel
import io.github.abonsan.hyakunin.data.Poem
import io.github.abonsan.hyakunin.data.Poems
import io.github.abonsan.hyakunin.game.kanaCount
import io.github.abonsan.hyakunin.game.kimarijiLength
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.random.Random

/** 1問。上の句を読む歌 [poem] と、並べる取り札 [choices]（[poem] を含む）。 */
data class QuizQuestion(
    val poem: Poem,
    val choices: List<Poem>,
    /** 100首すべてから見た決まり字の仮名の数（読みの仮名で数える）。 */
    val kimarijiLength: Int,
) {
    /** 上の句の仮名の数（空白を除く）。 */
    val kamiKanaCount: Int get() = kanaCount(poem.kamiYomi)
}

sealed interface QuizPhase {
    /** 上の句を読んでいる（読み終えた後も、答えるまで）。札を選べる。 */
    data object Asking : QuizPhase

    /** 答えた。[revealedAtAnswer] は答えた時点で読んでいた仮名の数。 */
    data class Answered(val chosenNo: Int, val correct: Boolean, val revealedAtAnswer: Int) : QuizPhase

    /** すべての問題に答え終えた。 */
    data object Finished : QuizPhase
}

data class QuizUiState(
    /** 出題する問題（出す順）。 */
    val questions: List<QuizQuestion>,
    /** 今の問題の番号（0始まり）。終えた後も最後の問題のまま。 */
    val index: Int,
    val phase: QuizPhase,
    /** 今の問題の上の句で、読んで見せた仮名の数（読みの仮名で数える）。 */
    val revealedKana: Int,
    /** 上の句を読み始めるたびに（次の問題・読み直し・やり直し）増える番号。再生側はこれが変わったら鳴らし始める。 */
    val readId: Long,
    val correctCount: Int,
    /** この回で間違えた歌（答えた順）。 */
    val missed: List<Poem>,
) {
    val question: QuizQuestion get() = questions[index]

    /** 今の問題が最後の問題か。 */
    val isLast: Boolean get() = index == questions.lastIndex
}

/**
 * 「札を覚える」の進行役。[poems] を1回ずつ無作為な順に出題する。上の句を読んで見せた仮名の数は、
 * 再生側（音声の位置、または文字だけなら時間）が [reveal] で知らせる。UI のスレッド（メイン）からだけ呼ぶこと。
 *
 * @param allPoems 決まり字の判定と誤答の札に使う100首
 * @param onAnswered 答えるたびに、その歌と正解したかを渡す（間違えた札の記録に使う）
 */
class QuizViewModel(
    poems: List<Poem>,
    private val random: Random = Random.Default,
    private val allPoems: List<Poem> = Poems.all,
    private val onAnswered: (Poem, Boolean) -> Unit = { _, _ -> },
) : ViewModel() {

    private val _uiState = MutableStateFlow(start(poems, readId = 0L))
    val uiState: StateFlow<QuizUiState> = _uiState.asStateFlow()

    private val state: QuizUiState get() = _uiState.value

    private fun start(poems: List<Poem>, readId: Long): QuizUiState {
        require(poems.isNotEmpty()) { "出題する札がない" }
        val questions = poems.shuffled(random).map {
            QuizQuestion(it, choicesFor(it, allPoems, random), kimarijiLength(it, allPoems))
        }
        return QuizUiState(
            questions = questions,
            index = 0,
            phase = QuizPhase.Asking,
            revealedKana = 0,
            readId = readId,
            correctCount = 0,
            missed = emptyList(),
        )
    }

    /** 上の句を [kana] 字目まで読んだ。答える前だけ、増えるときだけ受け付ける。 */
    fun reveal(kana: Int) {
        val count = kana.coerceAtMost(state.question.kamiKanaCount)
        if (state.phase != QuizPhase.Asking || count <= state.revealedKana) return
        _uiState.value = state.copy(revealedKana = count)
    }

    /** 歌番号 [poemNo] の札を選んだ。答える前だけ受け付ける。 */
    fun answer(poemNo: Int) {
        if (state.phase != QuizPhase.Asking) return
        val poem = state.question.poem
        val correct = poemNo == poem.no
        _uiState.value = state.copy(
            phase = QuizPhase.Answered(poemNo, correct, state.revealedKana),
            correctCount = state.correctCount + if (correct) 1 else 0,
            missed = if (correct) state.missed else state.missed + poem,
        )
        onAnswered(poem, correct)
    }

    /** 答えた後に、次の問題へ進む（最後の問題なら終える）。 */
    fun next() {
        if (state.phase !is QuizPhase.Answered) return
        _uiState.value = if (state.isLast) {
            state.copy(phase = QuizPhase.Finished)
        } else {
            state.copy(index = state.index + 1, phase = QuizPhase.Asking, revealedKana = 0, readId = state.readId + 1)
        }
    }

    /** 答える前に、上の句を初めから読み直す。 */
    fun replay() {
        if (state.phase != QuizPhase.Asking) return
        _uiState.value = state.copy(revealedKana = 0, readId = state.readId + 1)
    }

    /** [poems] を出題し直す（「もう一度」「間違えた札をもう一度」）。 */
    fun restart(poems: List<Poem>) {
        _uiState.value = start(poems, readId = state.readId + 1)
    }
}
