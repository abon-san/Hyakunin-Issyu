package io.github.abonsan.hyakunin.ui.match

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.MatchUiState
import io.github.abonsan.hyakunin.ui.NoopActions
import io.github.abonsan.hyakunin.ui.captureScreenshot
import io.github.abonsan.hyakunin.ui.home.HomeScreen
import io.github.abonsan.hyakunin.ui.theme.HyakuninTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 小さめのスマホ（360×640dp）でも文字が欠けたり重なったりしないかを撮って確かめる。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class SmallScreenScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun show(state: MatchUiState) {
        composeRule.setContent {
            HyakuninTheme {
                MatchScreen(state = state, actions = NoopActions, onExit = {})
            }
        }
    }

    @Test
    fun home() {
        composeRule.setContent {
            HyakuninTheme {
                HomeScreen(config = GameConfig(), onConfigChange = {}, onStart = {})
            }
        }
        composeRule.captureScreenshot("small_home")
    }

    @Test
    fun nineCardsAgainstOne() {
        show(Scenes.nineAgainstOne())
        composeRule.captureScreenshot("small_board_9_1")
    }

    @Test
    fun readingInSimpleRule() {
        show(Scenes.readingShimo())
        composeRule.captureScreenshot("small_reading_shimo")
    }

    @Test
    fun resultAfterFault() {
        show(Scenes.resultAfterFault())
        composeRule.captureScreenshot("small_result_fault")
    }

    @Test
    fun choosingCardsToSend() {
        show(Scenes.choosingSend())
        composeRule.onNodeWithText("送る札を自陣から選んでください（あと 1 枚）").assertExists()
        composeRule.captureScreenshot("small_send_select")
    }

    @Test
    fun finished() {
        show(Scenes.finished())
        composeRule.captureScreenshot("small_finished")
    }
}
