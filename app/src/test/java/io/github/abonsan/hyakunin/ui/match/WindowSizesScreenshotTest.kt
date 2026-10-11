package io.github.abonsan.hyakunin.ui.match

import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.MatchActions
import io.github.abonsan.hyakunin.game.MatchUiState
import io.github.abonsan.hyakunin.game.Side
import io.github.abonsan.hyakunin.ui.Fixtures
import io.github.abonsan.hyakunin.ui.NoopActions
import io.github.abonsan.hyakunin.ui.captureScreenshot
import io.github.abonsan.hyakunin.ui.theme.HyakuninTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.roundToInt

/**
 * 分割画面・タブレット・システムバーのある横画面など、いろいろな広さで対戦画面の主な場面を撮り、
 * 文字や札が欠けたり重なったりしないかを確かめる。画像の名前は「[prefix]_場面」。
 */
abstract class WindowSizeScreenshotTest(private val prefix: String) {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private var state by mutableStateOf(Scenes.memorizing())

    /** 触れると ViewModel と同じく、すぐにロックアウトしてお手つきを数える。 */
    private val actions = object : MatchActions by NoopActions {
        override fun onCardTap(side: Side, poemNo: Int) {
            state = state.copy(playerLockedOut = true, playerFaultCount = state.playerFaultCount + 1)
        }
    }

    /** 表示を始めた直後に呼ぶ（システムバーのある画面にするなど）。 */
    protected open fun afterContentSet() = Unit

    private fun show(scene: MatchUiState) {
        state = scene
        composeRule.setContent {
            HyakuninTheme {
                MatchScreen(state = state, actions = actions, onExit = {})
            }
        }
        afterContentSet()
    }

    private fun capture(scene: String) = composeRule.captureScreenshot("${prefix}_$scene")

    @Test
    fun waitingWhileMemorizing() {
        show(Scenes.memorizing())
        composeRule.onNodeWithText("すぐ読む").assertExists()
        capture("waiting_memorize")
    }

    @Test
    fun readingAndLockedOut() {
        show(Scenes.reading(revealed = 3))
        capture("reading")
        // 出札（自陣の61番）と違う陣の札に触れてお手つき
        composeRule.onNodeWithContentDescription(Fixtures.poem(70).shimoKana).performClick()
        state = state.copy(reading = Scenes.reading(revealed = 7).reading)
        composeRule.onNodeWithText("お手つき").assertExists()
        capture("lockout")
    }

    @Test
    fun result() {
        show(Scenes.resultAfterFault())
        composeRule.onNodeWithText("次へ").assertExists()
        capture("result_fault")
    }

    @Test
    fun choosingCardsToSend() {
        show(Scenes.choosingSend())
        composeRule.onNodeWithText(sendPrompt(1)).assertExists()
        capture("send_select")
    }

    @Test
    fun nineCardsAgainstOne() {
        show(Scenes.nineAgainstOne())
        capture("board_9_1")
    }

    @Test
    fun finished() {
        show(Scenes.finished())
        composeRule.onNodeWithText("あなたの勝ちです").assertExists()
        capture("finished")
    }
}

/** スマホの横画面を左右に分けた分割画面の片側。幅が高さより広いが、縦に並べる配置になる。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w445dp-h411dp-land-xxhdpi")
class SplitLandscapeMatchScreenshotTest : WindowSizeScreenshotTest(prefix = "split_land_match")

/** スマホの縦画面を上下に分けた分割画面の片側。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h440dp-port-xxhdpi")
class SplitPortraitMatchScreenshotTest : WindowSizeScreenshotTest(prefix = "split_port_match")

/** タブレットの横画面。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp-land-mdpi")
class TabletLandscapeMatchScreenshotTest : WindowSizeScreenshotTest(prefix = "tablet_match")

/**
 * 小さめのスマホの横画面に、上のステータスバー（24dp）と右の3ボタンのナビゲーションバー（48dp）がある場合。
 * 成績の帯と畳はバーの下まで敷き、文字と札はバーに重ならず、札は押しやすい大きさのまま。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w640dp-h360dp-land-xhdpi")
class SystemBarsLandscapeMatchScreenshotTest : WindowSizeScreenshotTest(prefix = "land_bars_match") {
    override fun afterContentSet() {
        composeRule.runOnUiThread {
            val content = composeRule.activity.findViewById<View>(android.R.id.content)
            val density = content.resources.displayMetrics.density
            fun px(dp: Int) = (dp * density).roundToInt()
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, px(24), 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, px(48), 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(content, insets)
        }
        composeRule.waitForIdle()
    }
}
