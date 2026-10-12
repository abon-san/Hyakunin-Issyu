package io.github.abonsan.hyakunin.ui.match

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.LeadInVerse
import io.github.abonsan.hyakunin.game.MatchActions
import io.github.abonsan.hyakunin.game.ReadStartMode
import io.github.abonsan.hyakunin.game.Side
import io.github.abonsan.hyakunin.ui.Fixtures
import io.github.abonsan.hyakunin.ui.NoopActions
import io.github.abonsan.hyakunin.ui.theme.HyakuninTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 偽の状態で対戦画面の各場面を撮る（一般的な大きさのスマホの縦画面）。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class MatchScreenshotTest : MatchScenesScreenshotTest(prefix = "match", fieldsAside = false) {

    @Test
    fun waitingForTapBeforeTheFirstRead() {
        show(Scenes.tapToStartFirst())
        composeRule.onNodeWithText("札の位置を覚えたら押してください").assertExists()
        capture("waiting_tap_first")
    }

    @Test
    fun leadInDuringTheMa() {
        // 序歌の下の句を2回読み終えて間合いに入った: 句をすべて控えめな色で見せる
        show(Scenes.jokaLeadIn(revealed = 14, pass = 2, inMa = true))
        composeRule.onNodeWithContentDescription("なにはづにさくやこのはなふゆごもりいまをはるべとさくやこのはな").assertExists()
        capture("lead_in_ma")
    }

    @Test
    fun leadInOfThePreviousShimoDuringTheMa() {
        show(Scenes.previousShimoLeadIn(revealed = 15, inMa = true))
        composeRule.onNodeWithContentDescription("けふここのへににほひぬるかな").assertExists()
        capture("lead_in_previous_ma")
    }

    @Test
    fun theJokaRepeatsTheShimoFromTheTop() {
        // 序歌の下の句の2回目は、下の句を初めから見せ直す
        show(Scenes.jokaLeadIn(pass = 2, revealed = 3))
        composeRule.onNodeWithContentDescription("なにはづにさくやこのはなふゆごもりいまを").assertExists()
    }

    @Test
    fun leadInHidesTheStartButtonWhenStartingByTap() {
        show(Scenes.jokaLeadIn(verse = LeadInVerse.KAMI, revealed = 9, readStartMode = ReadStartMode.TAP))
        composeRule.onNodeWithText("序歌").assertExists()
        composeRule.onNodeWithContentDescription("なにはづにさくやこ").assertExists()
        composeRule.onNodeWithText("読み始める").assertDoesNotExist()
        composeRule.onNodeWithText("札を確かめる時間").assertDoesNotExist()
        capture("lead_in_tap")
    }

    @Test
    fun waitingAfterOneCardWasSent() {
        show(Scenes.intervalAfterOneCardSent())
        // 送られてきた札が1枚なら枚数は添えない
        composeRule.onNodeWithText("金の縁は相手から送られてきた札です").assertExists()
        capture("waiting_interval_one")
    }

    @Test
    fun resultAfterFaultListsBothTransfers() {
        show(Scenes.resultAfterFault())
        // 送り札と罰の送り札
        composeRule.onAllNodesWithText("相手 → あなた（1枚）").assertCountEquals(2)
    }

    @Test
    fun emptyCard() {
        show(Scenes.emptyCard())
        composeRule.onNodeWithText("空札でした").assertExists()
        composeRule.onNodeWithText(takeSummary(TakeMarks(kimariji = 3, taken = null))).assertExists()
        capture("result_empty")
    }

    @Test
    fun nobodyTookTheCard() {
        show(Scenes.nobodyTook())
        composeRule.onNodeWithText("あなた・相手（罰の送り札は相殺）").assertExists()
        capture("result_nobody")
    }

    @Test
    fun takenBeforeTheKimarijiInSimpleRule() {
        show(Scenes.takenEarly())
        composeRule.onNodeWithText(takeSummary(TakeMarks(kimariji = 2, taken = 1))).assertExists()
        capture("result_early")
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
        capture("lockout_safe")
    }

    @Test
    fun finishedInADraw() {
        show(Scenes.draw())
        composeRule.onNodeWithText("引き分けです").assertExists()
        composeRule.onNodeWithText("取った札　あなた 5 枚 ・ 相手 5 枚").assertExists()
        capture("finished_draw")
    }
}
