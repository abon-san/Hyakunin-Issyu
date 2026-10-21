package io.github.abonsan.hyakunin.ui

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.abonsan.hyakunin.audio.VoicePlayer
import io.github.abonsan.hyakunin.game.MatchPhase
import io.github.abonsan.hyakunin.game.MatchViewModel
import io.github.abonsan.hyakunin.game.quiz.QuizViewModel
import io.github.abonsan.hyakunin.ui.match.VoicePlayerHolder
import org.junit.Assert.assertEquals
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** 表示中の対戦の [MatchViewModel]。対戦していなければ null。 */
internal fun ComponentActivity.activeMatchViewModel(): MatchViewModel? {
    val owner = ViewModelProvider(this)[MatchStoreHolder::class.java].activeOwner() ?: return null
    val factory = viewModelFactory { initializer<MatchViewModel> { error("対戦の ViewModel がまだ作られていない") } }
    return ViewModelProvider.create(owner, factory)[MatchViewModel::class]
}

/** 表示中の対戦の読み上げ音声の再生器（対戦の ViewModelStore にあるもの）。対戦していなければ null。 */
internal fun ComponentActivity.activeVoicePlayer(): VoicePlayer? {
    val owner = ViewModelProvider(this)[MatchStoreHolder::class.java].activeOwner() ?: return null
    val factory = viewModelFactory { initializer<VoicePlayerHolder> { error("再生器がまだ作られていない") } }
    return ViewModelProvider.create(owner, factory)[VoicePlayerHolder::class].player
}

/** 表示中の「札を覚える」の [QuizViewModel]。練習していなければ null。 */
internal fun ComponentActivity.activeQuizViewModel(): QuizViewModel? {
    val owner = ViewModelProvider(this)[QUIZ_STORE_KEY, MatchStoreHolder::class.java].activeOwner() ?: return null
    val factory = viewModelFactory { initializer<QuizViewModel> { error("練習の ViewModel がまだ作られていない") } }
    return ViewModelProvider.create(owner, factory)[QuizViewModel::class]
}

/** メインスレッドの仮想時間を進める（対戦の ViewModel の刻みが動く）。 */
internal fun ComposeTestRule.advanceMainLooper(millis: Long) {
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    waitForIdle()
}

/**
 * 最初の札の読み上げが始まるまで進める。「すぐ読む」（または「読み始める」）を押し、
 * 読み上げが始まるまで仮想時間を少しずつ進める（待ち時間を自動で数える場合にも対応する）。
 */
internal fun ComposeTestRule.startFirstRead(viewModel: () -> MatchViewModel) {
    for (label in listOf("すぐ読む", "読み始める")) {
        val buttons = onAllNodesWithText(label).fetchSemanticsNodes()
        if (buttons.isNotEmpty()) {
            onAllNodesWithText(label)[0].performClick()
        }
    }
    var waited = 0L
    while (viewModel().uiState.value.phase != MatchPhase.Reading && waited < MAX_WAIT_MILLIS) {
        advanceMainLooper(STEP_MILLIS)
        waited += STEP_MILLIS
    }
    assertEquals(MatchPhase.Reading, viewModel().uiState.value.phase)
}

private const val STEP_MILLIS = 100L

/** 読み始めを待つ上限（札を覚える時間の最大 2分 より長く）。 */
private const val MAX_WAIT_MILLIS = 130_000L
