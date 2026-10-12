package io.github.abonsan.hyakunin

import android.content.Context
import android.content.pm.ActivityInfo
import android.media.AudioManager
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.MatchPhase
import io.github.abonsan.hyakunin.game.MatchViewModel
import io.github.abonsan.hyakunin.ui.activeMatchViewModel
import io.github.abonsan.hyakunin.ui.advanceMainLooper
import io.github.abonsan.hyakunin.ui.captureWholeScreen
import io.github.abonsan.hyakunin.ui.settings.AppSettings
import io.github.abonsan.hyakunin.ui.settings.AppSettingsStore
import io.github.abonsan.hyakunin.ui.settings.ScreenOrientation
import io.github.abonsan.hyakunin.ui.startFirstRead
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 実際の [MainActivity] を起動して確かめる（画面の向きの指定、回転しても対戦が続くこと）。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class MainActivityTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        scenario?.close()
    }

    private fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    private fun ActivityScenario<MainActivity>.activity(): MainActivity {
        var activity: MainActivity? = null
        onActivity { activity = it }
        return activity!!
    }

    /**
     * 表示中の対戦の ViewModel。直前の操作による画面の切り替えを反映してから取り出す。
     * 読み上げ音声を使うときは、裏のスレッドで音声を読み終えてから対戦が始まるので、それを待つ。
     */
    private fun ActivityScenario<MainActivity>.matchViewModel(): MatchViewModel {
        composeRule.waitUntil(timeoutMillis = 10_000L) {
            runCatching { activity().activeMatchViewModel() }.getOrNull() != null
        }
        return activity().activeMatchViewModel()!!
    }

    /** 画面を回したことにして Activity を作り直す。 */
    private fun ActivityScenario<MainActivity>.rotateTo(qualifiers: String) {
        RuntimeEnvironment.setQualifiers(qualifiers)
        recreate()
        composeRule.waitForIdle()
    }

    @Test
    fun launchesIntoTheTopScreen() {
        val scenario = launch()
        composeRule.onNodeWithText("百人一首").assertExists()
        composeRule.onNodeWithText("競技モード").assertExists()
        composeRule.onNodeWithText("設定").assertExists()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, scenario.activity().requestedOrientation)
        // 音量ボタンは読み上げ音声（メディア）の音量を変える
        assertEquals(AudioManager.STREAM_MUSIC, scenario.activity().volumeControlStream)
        composeRule.captureWholeScreen("app_top")
    }

    @Test
    fun appliesTheSavedOrientationWhenLaunched() {
        AppSettingsStore(context).save(AppSettings(orientation = ScreenOrientation.LANDSCAPE))
        val scenario = launch()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, scenario.activity().requestedOrientation)
    }

    @Test
    fun changingTheOrientationSettingAppliesItRightAway() {
        val scenario = launch()
        composeRule.onNodeWithText("設定").performClick()

        composeRule.onNodeWithText(ScreenOrientation.LANDSCAPE.label).performClick()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, scenario.activity().requestedOrientation)
        composeRule.onNodeWithText(ScreenOrientation.PORTRAIT.label).performClick()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, scenario.activity().requestedOrientation)
        composeRule.onNodeWithText(ScreenOrientation.AUTO.label).performClick()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, scenario.activity().requestedOrientation)
        assertEquals(ScreenOrientation.AUTO, AppSettingsStore(context).load().orientation)

        // 回して作り直されても設定の画面のまま
        composeRule.onNodeWithText(ScreenOrientation.LANDSCAPE.label).performClick()
        scenario.rotateTo("w891dp-h411dp-land-xxhdpi")
        composeRule.onNodeWithText("画面の向き").assertExists()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, scenario.activity().requestedOrientation)
    }

    @Test
    fun rotatingKeepsTheMatchAndThePauseDialog() {
        val scenario = launch()
        composeRule.onNodeWithText("競技モード").performClick()
        composeRule.onNodeWithText("対戦開始").performClick()
        val viewModel = scenario.matchViewModel()
        composeRule.startFirstRead { scenario.matchViewModel() }
        composeRule.advanceMainLooper(1_500L)

        composeRule.onNodeWithContentDescription("一時停止").performClick()
        composeRule.onNodeWithText("一時停止中").assertExists()
        val before = viewModel.uiState.value
        assertTrue(before.isPaused)
        composeRule.captureWholeScreen("app_rotate_portrait")

        scenario.rotateTo("w891dp-h411dp-land-xxhdpi")
        // 同じ対戦がそのまま続き、一時停止のダイアログも残る
        assertSame(viewModel, scenario.matchViewModel())
        composeRule.onNodeWithText("一時停止中").assertExists()
        val after = viewModel.uiState.value
        assertTrue(after.isPaused)
        assertEquals(before.reading, after.reading)
        assertEquals(before.playerCards, after.playerCards)
        assertEquals(before.cpuCards, after.cpuCards)
        composeRule.captureWholeScreen("app_rotate_landscape")

        composeRule.onNodeWithText("再開").performClick()
        assertFalse(viewModel.uiState.value.isPaused)
        composeRule.advanceMainLooper(1_000L)
        val resumed = viewModel.uiState.value
        assertTrue(
            "読みが進む: ${before.reading} -> ${resumed.reading}",
            resumed.phase != MatchPhase.Reading || resumed.reading!!.revealedKana > before.reading!!.revealedKana,
        )

        // 縦に戻しても同じ対戦
        scenario.rotateTo("w411dp-h891dp-xxhdpi")
        assertSame(viewModel, scenario.matchViewModel())
    }

    @Test
    fun rotatingKeepsTheExitConfirmation() {
        val scenario = launch()
        composeRule.onNodeWithText("簡易モード").performClick()
        composeRule.onNodeWithText("対戦開始").performClick()
        val viewModel = scenario.matchViewModel()

        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("対戦をやめますか？").assertExists()
        assertTrue(viewModel.uiState.value.isPaused)

        scenario.rotateTo("w891dp-h411dp-land-xxhdpi")
        composeRule.onNodeWithText("対戦をやめますか？").assertExists()
        assertSame(viewModel, scenario.matchViewModel())
        assertTrue(viewModel.uiState.value.isPaused)

        // 続けると、止める前の状態から再開する
        composeRule.onNodeWithText("続ける").performClick()
        assertFalse(viewModel.uiState.value.isPaused)
        composeRule.onNodeWithText("対戦をやめますか？").assertDoesNotExist()

        // やめると、回した後でも対戦設定に戻る
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        composeRule.onNodeWithText("やめる").performClick()
        composeRule.onNodeWithText("対戦開始").assertExists()
    }
}
