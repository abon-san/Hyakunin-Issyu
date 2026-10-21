package io.github.abonsan.hyakunin.ui.quiz

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.abonsan.hyakunin.data.Poem
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.game.quiz.QuizPhase
import io.github.abonsan.hyakunin.game.quiz.QuizViewModel
import io.github.abonsan.hyakunin.game.voice.VoiceBank
import io.github.abonsan.hyakunin.ui.components.WafuDialog

/**
 * 「札を覚える」の入口。ViewModel と上の句の読み手（[QuizReader]）は、呼び出し側が練習ごとに用意した ViewModelStore に作る。
 * アプリが裏に回った間と、やめるかを確かめている間は、上の句の読みを止める。
 *
 * @param poems 出題する札（1枚以上）
 * @param onAnswered 答えるたびに、その歌と正解したかを渡す（間違えた札の記録）
 * @param voiceBank 読み上げ音声。null なら文字だけで読む
 * @param volume 読み上げの音量（設定の「音量」）
 */
@Composable
fun QuizRoute(
    poems: List<Poem>,
    verseStyle: VerseStyle,
    onAnswered: (Poem, Boolean) -> Unit,
    onExit: () -> Unit,
    voiceBank: VoiceBank? = null,
    volume: Float = 1f,
) {
    val viewModel = viewModel { QuizViewModel(poems, onAnswered = onAnswered) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val reader = rememberQuizReader()
    var confirmingExit by rememberSaveable { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { reader.setInBackground(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { reader.setInBackground(false) }
    LaunchedEffect(reader, confirmingExit) { reader.setConfirming(confirmingExit) }
    QuizReading(state, viewModel, reader, voiceBank, volume)

    val back = {
        if (state.phase == QuizPhase.Finished) onExit() else confirmingExit = true
    }
    BackHandler(onBack = back)

    val exit = {
        reader.stop()
        onExit()
    }
    QuizScreen(
        state = state,
        verseStyle = verseStyle,
        onChoose = { poemNo ->
            viewModel.answer(poemNo)
            reader.stop()
        },
        onReplay = {
            reader.stop()
            viewModel.replay()
        },
        onNext = viewModel::next,
        onRestart = { viewModel.restart(state.questions.map { it.poem }) },
        onRestartMissed = { viewModel.restart(state.missed) },
        onBack = back,
        onExit = exit,
    )

    if (confirmingExit) {
        val keepPracticing = { confirmingExit = false }
        WafuDialog(
            title = "練習をやめますか？",
            message = "ここまでに間違えた札は、記録に残ります。",
            confirmLabel = "やめる",
            onConfirm = exit,
            dismissLabel = "続ける",
            onDismiss = keepPracticing,
            onDismissRequest = keepPracticing,
        )
    }
}
