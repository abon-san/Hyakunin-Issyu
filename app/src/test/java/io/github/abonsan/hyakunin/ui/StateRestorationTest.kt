package io.github.abonsan.hyakunin.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.MatchActions
import io.github.abonsan.hyakunin.game.Side
import io.github.abonsan.hyakunin.ui.home.GameConfigStore
import io.github.abonsan.hyakunin.ui.match.MatchScreen
import io.github.abonsan.hyakunin.ui.match.Scenes
import io.github.abonsan.hyakunin.ui.quiz.QuizStore
import io.github.abonsan.hyakunin.ui.settings.AppSettingsStore
import io.github.abonsan.hyakunin.ui.theme.HyakuninTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 画面の状態が、Activity の作り直し（画面の回転など）で保存・復元されること。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w891dp-h411dp-land-xxhdpi")
class StateRestorationTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val tester = StateRestorationTester(composeRule)

    @Test
    fun theSideOfTheTouchedCardIsKept() {
        var state by mutableStateOf(Scenes.reading(revealed = 3))
        val actions = object : MatchActions by NoopActions {
            override fun onCardTap(side: Side, poemNo: Int) {
                state = state.copy(playerLockedOut = true)
            }
        }
        tester.setContent {
            HyakuninTheme { MatchScreen(state = state, actions = actions, onExit = {}) }
        }
        // 出札（自陣の61番）と同じ陣の札に触れた。作り直した後もお手つきでないと分かる
        composeRule.onNodeWithContentDescription(Fixtures.poem(33).shimoKana).performClick()
        composeRule.onNodeWithText("同じ陣の札なのでお手つきにはなりません").assertExists()
        tester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("同じ陣の札なのでお手つきにはなりません").assertExists()
    }

    @Test
    fun theScreenAndTheSelectedModeAreKept() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        tester.setContent {
            HyakuninTheme { HyakuninApp(GameConfigStore(context), AppSettingsStore(context), QuizStore(context)) }
        }
        composeRule.onNodeWithText("簡易モード").performClick()
        composeRule.onNodeWithText("対戦設定").assertExists()
        tester.emulateSavedInstanceStateRestore()
        composeRule.onNodeWithText("対戦設定").assertExists()
        composeRule.onNodeWithText("簡易モード").assertExists()
    }
}
