package io.github.abonsan.hyakunin.ui.match

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 偽の [MatchUiState] で対戦画面の各場面を撮る（一般的な大きさのスマホ）。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class MatchScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(state: MatchUiState, actions: MatchActions = NoopActions) {
        composeRule.setContent {
            HyakuninTheme {
                MatchScreen(state = state, actions = actions, onExit = {})
            }
        }
    }

    @Test
    fun waitingForTheFirstRead() {
        show(Scenes.waiting())
        composeRule.onNodeWithText("まもなく読み始めます").assertExists()
        composeRule.captureScreenshot("match_waiting")
    }

    @Test
    fun reading() {
        show(Scenes.reading())
        composeRule.onNodeWithText("第 4 首 ／ 全 20 首").assertExists()
        composeRule.captureScreenshot("match_reading")
    }

    @Test
    fun readingTheLowerVerse() {
        show(Scenes.readingShimo())
        composeRule.captureScreenshot("match_reading_shimo")
    }

    @Test
    fun resultWithKimarijiAndOverRead() {
        val actions = RecordingActions()
        show(Scenes.result(), actions)
        composeRule.onNodeWithText("決まり字を超えて読まれた部分").assertExists()
        composeRule.onNodeWithText("決まり字は 3 字・7 字目で取りました").assertExists()
        composeRule.captureScreenshot("match_result")
        composeRule.onNodeWithText("次へ").performClick()
        assertEquals(1, actions.nextCount)
    }

    @Test
    fun resultAfterFaultAndCpuTake() {
        show(Scenes.resultAfterFault())
        composeRule.onNodeWithText("お手つき！相手から2枚送られます").assertExists()
        // 送り札と罰の送り札
        composeRule.onAllNodesWithText("相手 → あなた（1枚）").assertCountEquals(2)
        composeRule.captureScreenshot("match_result_fault")
    }

    @Test
    fun emptyCard() {
        show(Scenes.emptyCard())
        composeRule.onNodeWithText("空札でした").assertExists()
        composeRule.onNodeWithText("決まり字は 3 字").assertExists()
        composeRule.captureScreenshot("match_result_empty")
    }

    @Test
    fun nobodyTookTheCard() {
        show(Scenes.nobodyTook())
        composeRule.onNodeWithText("あなた・相手（罰の送り札は相殺）").assertExists()
        composeRule.captureScreenshot("match_result_nobody")
    }

    @Test
    fun choosingCardsToSend() {
        val actions = RecordingActions()
        show(Scenes.choosingSend(), actions)
        composeRule.onNodeWithText("送る札を自陣から選んでください（あと 1 枚）").assertExists()
        composeRule.captureScreenshot("match_send_select")

        // 自陣の札を選ぶと送る札として渡す。敵陣の札は選べない
        composeRule.onNodeWithContentDescription(Fixtures.poem(33).shimoKana).performClick()
        composeRule.onNodeWithContentDescription(Fixtures.poem(46).shimoKana).performClick()
        assertEquals(listOf(Side.PLAYER to 33), actions.taps)
    }

    @Test
    fun takenBeforeTheKimarijiInSimpleRule() {
        show(Scenes.takenEarly())
        composeRule.onNodeWithText("決まり字が出る前に取りました（1 字目）").assertExists()
        composeRule.captureScreenshot("match_result_early")
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
        composeRule.captureScreenshot("match_lockout")
    }

    @Test
    fun lockedOutWithoutFault() {
        var state by mutableStateOf(Scenes.reading(revealed = 3))
        val actions = object : MatchActions by NoopActions {
            override fun onCardTap(side: Side, poemNo: Int) {
                state = state.copy(playerLockedOut = true)
            }
        }
        composeRule.setContent {
            HyakuninTheme {
                MatchScreen(state = state, actions = actions, onExit = {})
            }
        }
        // 出札と同じ自陣の別の札に触れる
        composeRule.onNodeWithContentDescription(Fixtures.poem(33).shimoKana).performClick()
        composeRule.onNodeWithText("同じ陣の札なのでお手つきにはなりません").assertExists()
        composeRule.captureScreenshot("match_lockout_safe")
    }

    @Test
    fun nineCardsAgainstOne() {
        show(Scenes.nineAgainstOne())
        composeRule.captureScreenshot("match_board_9_1")
    }

    @Test
    fun finished() {
        val actions = RecordingActions()
        show(Scenes.finished(), actions)
        composeRule.onNodeWithText("あなたの勝ちです").assertExists()
        composeRule.captureScreenshot("match_finished")
        composeRule.onNodeWithText("もう一度").performClick()
        assertEquals(1, actions.restartCount)
    }

    @Test
    fun finishedInADraw() {
        show(Scenes.draw())
        composeRule.onNodeWithText("引き分けです").assertExists()
        composeRule.onNodeWithText("取った札　あなた 5 枚 ・ 相手 5 枚").assertExists()
        composeRule.captureScreenshot("match_finished_draw")
    }

    @Test
    fun paused() {
        show(Scenes.reading(revealed = 5).copy(isPaused = true))
        composeRule.onNodeWithText("一時停止中").assertExists()
        composeRule.captureWholeScreen("match_paused")
    }
}
