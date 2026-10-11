package io.github.abonsan.hyakunin.ui.match

import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.CpuLevel
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.MatchPhase
import io.github.abonsan.hyakunin.game.MatchViewModel
import io.github.abonsan.hyakunin.ui.captureScreenshot
import io.github.abonsan.hyakunin.ui.captureWholeScreen
import io.github.abonsan.hyakunin.ui.theme.HyakuninTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Duration
import kotlin.random.Random

/**
 * 本物の [MatchViewModel]（100首の実データ）で [MatchRoute] を表示する。
 * ViewModel の時計を Robolectric の仮想時間にし、メインスレッドの時間を進めて読み上げを進める。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class MatchRouteScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val store = ViewModelStore()
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore get() = store
    }

    @After
    fun tearDown() = store.clear()

    /** [MatchRoute] が `viewModel { ... }` で取り出す ViewModel を先に入れておく。 */
    private fun prepareViewModel(config: GameConfig, seed: Int): MatchViewModel {
        val vm = MatchViewModel(config, Random(seed), clock = { SystemClock.uptimeMillis() })
        val factory = viewModelFactory { initializer { vm } }
        return ViewModelProvider.create(owner, factory)[MatchViewModel::class]
    }

    private fun showRoute(config: GameConfig) {
        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                HyakuninTheme {
                    MatchRoute(config = config, onExit = {})
                }
            }
        }
    }

    /** メインスレッドの仮想時間を進める（ViewModel の刻みが動く）。 */
    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
        composeRule.waitForIdle()
    }

    @Test
    fun backAsksBeforeLeavingAndPausesMeanwhile() {
        val config = GameConfig(emptyCardCount = 10, cpuLevel = CpuLevel.BEGINNER)
        val vm = prepareViewModel(config, seed = 2026)
        var exited = false
        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                HyakuninTheme {
                    MatchRoute(config = config, onExit = { exited = true })
                }
            }
        }
        // 札を覚える時間を飛ばしてから読みを進める
        composeRule.runOnUiThread { vm.onStartReading() }
        advance(1_000L)
        val revealed = vm.uiState.value.reading!!.revealedKana

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("対戦をやめますか？").assertExists()
        assertTrue(vm.uiState.value.isPaused)
        // 確認している間は読みが進まない
        advance(3_000L)
        assertEquals(revealed, vm.uiState.value.reading!!.revealedKana)
        composeRule.captureWholeScreen("route_exit_confirm")

        composeRule.onNodeWithText("続ける").performClick()
        assertFalse(vm.uiState.value.isPaused)
        assertFalse(exited)

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("やめる").performClick()
        assertTrue(exited)
    }

    @Test
    fun pausesWhileTheAppIsInTheBackground() {
        val config = GameConfig(emptyCardCount = 10, cpuLevel = CpuLevel.BEGINNER)
        val vm = prepareViewModel(config, seed = 2026)
        showRoute(config)
        advance(1_000L)

        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertTrue(vm.uiState.value.isPaused)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        assertFalse(vm.uiState.value.isPaused)

        // 自分で一時停止したときは、前に戻っても止めたまま
        composeRule.onNodeWithContentDescription("一時停止").performClick()
        composeRule.onNodeWithText("一時停止中").assertExists()
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        assertTrue(vm.uiState.value.isPaused)
        composeRule.onNodeWithText("再開").performClick()
        assertFalse(vm.uiState.value.isPaused)
    }

    @Test
    fun readingWithTheRealViewModel() {
        val config = GameConfig(emptyCardCount = 10, cpuLevel = CpuLevel.BEGINNER)
        val vm = prepareViewModel(config, seed = 2026)
        showRoute(config)
        composeRule.onNodeWithText("まもなく読み始めます").assertExists()
        composeRule.captureScreenshot("route_waiting")

        // 札を覚える時間を「すぐ読む」で飛ばすと、仮名を1文字ずつ表示する
        composeRule.runOnUiThread { vm.onStartReading() }
        advance(2_000L)
        val state = vm.uiState.value
        assertEquals(MatchPhase.Reading, state.phase)
        val reading = state.reading!!
        assertEquals(1, reading.readNumber)
        assertTrue("revealed=${reading.revealedKana}", reading.revealedKana >= 6)
        assertEquals(20, state.totalReads)
        composeRule.onNodeWithText("第 1 首 ／ 全 20 首").assertExists()
        composeRule.captureScreenshot("route_reading")
    }
}
