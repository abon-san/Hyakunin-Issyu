package io.github.abonsan.hyakunin.ui.match

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
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.ui.advanceMainLooper
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
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * 本物の [MatchViewModel]（100首の実データ）で [MatchRoute] を表示する。
 * ViewModel の時計を Robolectric の仮想時間にし、メインスレッドの時間を進めて読み上げを進める。
 * 画面の大きさと向きはサブクラスの @Config で決め、画像の名前は「[prefix]_場面」。
 */
abstract class MatchRouteScenesTest(private val prefix: String) {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val store = ViewModelStore()
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore get() = store
    }

    /** 句の表示は、これまでのスクリーンショットと見比べられるよう読み（現代仮名遣い）にする。 */
    protected val config = GameConfig(emptyCardCount = 10, cpuLevel = CpuLevel.BEGINNER, verseStyle = VerseStyle.READING)

    @After
    fun tearDown() = store.clear()

    /** [MatchRoute] が `viewModel { ... }` で取り出す ViewModel を先に入れておく。 */
    protected fun prepareViewModel(seed: Int = 2026): MatchViewModel {
        val vm = MatchViewModel(config, Random(seed), clock = { SystemClock.uptimeMillis() })
        val factory = viewModelFactory { initializer { vm } }
        return ViewModelProvider.create(owner, factory)[MatchViewModel::class]
    }

    protected fun showRoute(onExit: () -> Unit = {}) {
        composeRule.setContent {
            CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                HyakuninTheme {
                    MatchRoute(config = config, onExit = onExit)
                }
            }
        }
    }

    @Test
    fun readingWithTheRealViewModel() {
        val vm = prepareViewModel()
        showRoute()
        composeRule.onNodeWithText("札を覚える時間").assertExists()
        composeRule.captureScreenshot("${prefix}_waiting")

        // 札を覚える時間を「すぐ読む」で飛ばすと、仮名を1文字ずつ表示する
        composeRule.runOnUiThread { vm.onStartReading() }
        composeRule.advanceMainLooper(2_000L)
        val state = vm.uiState.value
        assertEquals(MatchPhase.Reading, state.phase)
        val reading = state.reading!!
        assertEquals(1, reading.readNumber)
        assertTrue("revealed=${reading.revealedKana}", reading.revealedKana >= 6)
        assertEquals(20, state.totalReads)
        composeRule.onNodeWithText("第 1 首 ／ 全 20 首").assertExists()
        composeRule.captureScreenshot("${prefix}_reading")
    }
}

/** 縦画面。戻る操作と、アプリが裏に回ったときの一時停止も確かめる。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class MatchRouteScreenshotTest : MatchRouteScenesTest(prefix = "route") {

    @Test
    fun backAsksBeforeLeavingAndPausesMeanwhile() {
        val vm = prepareViewModel()
        var exited = false
        showRoute(onExit = { exited = true })
        // 札を覚える時間を飛ばしてから読みを進める
        composeRule.runOnUiThread { vm.onStartReading() }
        composeRule.advanceMainLooper(1_000L)
        val revealed = vm.uiState.value.reading!!.revealedKana

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("対戦をやめますか？").assertExists()
        assertTrue(vm.uiState.value.isPaused)
        // 確かめている間は読みが進まない
        composeRule.advanceMainLooper(3_000L)
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
        val vm = prepareViewModel()
        showRoute()
        composeRule.advanceMainLooper(1_000L)

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
}

/** 一般的な大きさのスマホの横画面。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w891dp-h411dp-land-xxhdpi")
class LandscapeMatchRouteScreenshotTest : MatchRouteScenesTest(prefix = "land_route") {

    @Test
    fun exitConfirmationInLandscape() {
        prepareViewModel()
        showRoute()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("対戦をやめますか？").assertExists()
        composeRule.captureWholeScreen("land_route_exit_confirm")
    }
}

/** 小さめのスマホの横画面。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w640dp-h360dp-land-xhdpi")
class SmallLandscapeMatchRouteScreenshotTest : MatchRouteScenesTest(prefix = "land_small_route") {

    @Test
    fun exitConfirmationInSmallLandscape() {
        prepareViewModel()
        showRoute()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("対戦をやめますか？").assertExists()
        composeRule.captureWholeScreen("land_small_route_exit_confirm")
    }
}
