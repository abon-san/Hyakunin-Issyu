package io.github.abonsan.hyakunin.ui.match

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.abonsan.hyakunin.game.MatchActions
import io.github.abonsan.hyakunin.game.MatchUiState
import io.github.abonsan.hyakunin.game.Side
import io.github.abonsan.hyakunin.ui.Fixtures
import io.github.abonsan.hyakunin.ui.NoopActions
import io.github.abonsan.hyakunin.ui.RecordingActions
import io.github.abonsan.hyakunin.ui.captureScreenshot
import io.github.abonsan.hyakunin.ui.captureWholeScreen
import io.github.abonsan.hyakunin.ui.theme.HyakuninTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * 偽の [MatchUiState] で対戦画面の主な場面を撮る。画面の大きさと向きはサブクラスの @Config で決める。
 * 画像の名前は「[prefix]_場面」。
 *
 * @param fieldsAside 場が右側に並ぶ配置（横に広い画面）か。案内の文の確認に使う。
 */
abstract class MatchScenesScreenshotTest(
    private val prefix: String,
    private val fieldsAside: Boolean,
) {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    protected fun show(state: MatchUiState, actions: MatchActions = NoopActions) {
        composeRule.setContent {
            HyakuninTheme {
                MatchScreen(state = state, actions = actions, onExit = {})
            }
        }
    }

    protected fun capture(scene: String) = composeRule.captureScreenshot("${prefix}_$scene")

    @Test
    fun waitingWhileMemorizing() {
        val actions = RecordingActions()
        show(Scenes.memorizing(), actions)
        composeRule.onNodeWithText("札を覚える時間").assertExists()
        composeRule.onNodeWithContentDescription("残り25秒").assertExists()
        composeRule.onNodeWithText(boardGuide(fieldsAside)).assertExists()
        capture("waiting_memorize")
        composeRule.onNodeWithText("すぐ読む").performClick()
        assertEquals(1, actions.startReadingCount)
    }

    @Test
    fun waitingBetweenReadsAfterCardsWereSent() {
        show(Scenes.interval())
        composeRule.onNodeWithText("次の札まで").assertExists()
        composeRule.onNodeWithContentDescription("残り3秒").assertExists()
        composeRule.onNodeWithText(movedCardsNote(toPlayer = 2, toCpu = 0)!!).assertExists()
        capture("waiting_interval")
    }

    @Test
    fun waitingForTapToStart() {
        val actions = RecordingActions()
        show(Scenes.tapToStart(), actions)
        composeRule.onNodeWithText("札を確かめる時間").assertExists()
        composeRule.onNodeWithText("準備ができたら押してください").assertExists()
        composeRule.onNodeWithText(movedCardsNote(toPlayer = 0, toCpu = 1)!!).assertExists()
        capture("waiting_tap")
        composeRule.onNodeWithText("読み始める").performClick()
        assertEquals(1, actions.startReadingCount)
    }

    @Test
    fun reading() {
        show(Scenes.reading())
        composeRule.onNodeWithText("第 4 首 ／ 全 20 首").assertExists()
        capture("reading")
    }

    @Test
    fun readingTheLowerVerse() {
        show(Scenes.readingShimo())
        capture("reading_shimo")
    }

    @Test
    fun resultWithKimarijiAndOverRead() {
        val actions = RecordingActions()
        show(Scenes.result(), actions)
        composeRule.onNodeWithText(OVER_READ_LABEL).assertExists()
        composeRule.onNodeWithText("決まり字は 3 字・7 字目で取りました").assertExists()
        capture("result")
        composeRule.onNodeWithText("次へ").performClick()
        assertEquals(1, actions.nextCount)
    }

    @Test
    fun resultAfterFaultAndCpuTake() {
        show(Scenes.resultAfterFault())
        composeRule.onNodeWithText("お手つき！相手から2枚送られます").assertExists()
        capture("result_fault")
    }

    @Test
    fun choosingCardsToSend() {
        val actions = RecordingActions()
        show(Scenes.choosingSend(), actions)
        composeRule.onNodeWithText(sendPrompt(1)).assertExists()
        composeRule.onNodeWithText(sendGuide(fieldsAside)).assertExists()
        capture("send_select")

        // 自陣の札を選ぶと送る札として渡す。敵陣の札は選べない
        composeRule.onNodeWithContentDescription(Fixtures.poem(33).shimoKana).performClick()
        composeRule.onNodeWithContentDescription(Fixtures.poem(46).shimoKana).performClick()
        assertEquals(listOf(Side.PLAYER to 33), actions.taps)
    }

    @Test
    fun nineCardsAgainstOne() {
        val actions = RecordingActions()
        show(Scenes.nineAgainstOne(), actions)
        capture("board_9_1")
        // 9枚の陣の札にも触れられる
        composeRule.onNodeWithContentDescription(Fixtures.poem(87).shimoKana).performClick()
        assertEquals(listOf(Side.CPU to 87), actions.taps)
    }

    @Test
    fun lockedOutAfterFault() {
        var state by mutableStateOf(Scenes.reading(revealed = 3))
        // 触れると ViewModel と同じく、すぐにロックアウトしてお手つきを数える
        val actions = object : MatchActions by NoopActions {
            override fun onCardTap(side: Side, poemNo: Int) {
                state = state.copy(playerLockedOut = true, playerFaultCount = state.playerFaultCount + 1)
            }
        }
        composeRule.setContent {
            HyakuninTheme {
                MatchScreen(state = state, actions = actions, onExit = {})
            }
        }
        // 出札（自陣の61番）と違う陣の札に触れる
        composeRule.onNodeWithContentDescription(Fixtures.poem(70).shimoKana).performClick()
        state = state.copy(reading = Scenes.reading(revealed = 7).reading)
        composeRule.onNodeWithText("お手つき").assertExists()
        capture("lockout")
    }

    @Test
    fun finished() {
        val actions = RecordingActions()
        show(Scenes.finished(), actions)
        composeRule.onNodeWithText("あなたの勝ちです").assertExists()
        capture("finished")
        composeRule.onNodeWithText("もう一度").performClick()
        assertEquals(1, actions.restartCount)
    }

    @Test
    fun paused() {
        show(Scenes.reading(revealed = 5).copy(isPaused = true))
        composeRule.onNodeWithText("一時停止中").assertExists()
        composeRule.captureWholeScreen("${prefix}_paused")
    }
}
