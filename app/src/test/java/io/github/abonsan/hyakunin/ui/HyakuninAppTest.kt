package io.github.abonsan.hyakunin.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.CpuLevel
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.MemorizeTime
import io.github.abonsan.hyakunin.game.ReadStartMode
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.ui.home.GameConfigStore
import io.github.abonsan.hyakunin.ui.settings.AppSettings
import io.github.abonsan.hyakunin.ui.settings.AppSettingsStore
import io.github.abonsan.hyakunin.ui.settings.ScreenOrientation
import io.github.abonsan.hyakunin.ui.theme.HyakuninTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** トップ・対戦設定・設定・対戦の行き来と、設定の保存。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class HyakuninAppTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val configStore = GameConfigStore(context)
    private val settingsStore = AppSettingsStore(context)
    private val orientations = mutableListOf<ScreenOrientation>()

    private fun showApp() {
        composeRule.setContent {
            HyakuninTheme {
                HyakuninApp(configStore, settingsStore, onOrientationChange = { orientations += it })
            }
        }
    }

    private fun pressBack() {
        // 直前の操作による画面の切り替え（戻る操作の受け手）を反映してから押す
        composeRule.waitForIdle()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    private fun activeMatch() = composeRule.waitForIdle().let { composeRule.activity.activeMatchViewModel() }

    @Test
    fun topLeadsToTheMatchSetupAndTheMatch() {
        showApp()
        composeRule.onNodeWithText("百人一首").assertExists()

        composeRule.onNodeWithText("競技モード").performClick()
        composeRule.onNodeWithText("対戦設定").assertExists()
        composeRule.onNodeWithText("対戦開始").performClick()
        composeRule.onNodeWithText("札を覚える時間").assertExists()
        assertNotNull(activeMatch())

        // 戻るとやめるかを確かめる。続けると対戦に残る
        pressBack()
        composeRule.onNodeWithText("対戦をやめますか？").assertExists()
        composeRule.onNodeWithText("続ける").performClick()
        composeRule.onNodeWithText("札を覚える時間").assertExists()

        // やめると対戦設定に戻り、対戦の ViewModel は破棄する
        pressBack()
        composeRule.onNodeWithText("やめる").performClick()
        composeRule.onNodeWithText("対戦開始").assertExists()
        assertNull(activeMatch())

        // 対戦設定から戻るとトップ
        pressBack()
        composeRule.onNodeWithText("モードを選んでください").assertExists()
    }

    @Test
    fun topLeadsToSettingsAndBack() {
        showApp()
        composeRule.onNodeWithText("設定").performClick()
        composeRule.onNodeWithText("画面の向き").assertExists()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.onNodeWithText("モードを選んでください").assertExists()

        composeRule.onNodeWithText("設定").performClick()
        pressBack()
        composeRule.onNodeWithText("モードを選んでください").assertExists()
    }

    @Test
    fun settingsAreSavedAndTheOrientationIsApplied() {
        showApp()
        composeRule.onNodeWithText("設定").performClick()
        composeRule.onNodeWithText(ScreenOrientation.LANDSCAPE.label).performClick()
        composeRule.onNodeWithText("敵陣の札を逆さに表示").performScrollTo().performClick()
        composeRule.onNodeWithText(VerseStyle.READING.label).performScrollTo().performClick()

        assertEquals(
            AppSettings(ScreenOrientation.LANDSCAPE, flipOpponentCards = false, verseStyle = VerseStyle.READING),
            settingsStore.load(),
        )
        assertEquals(listOf(ScreenOrientation.LANDSCAPE), orientations)
        // 向きを変えないときは呼ばない
        composeRule.onNodeWithText("敵陣の札を逆さに表示").performScrollTo().performClick()
        composeRule.onNodeWithText(VerseStyle.KANA.label).performScrollTo().performClick()
        assertEquals(VerseStyle.KANA, settingsStore.load().verseStyle)
        composeRule.onNodeWithText(ScreenOrientation.AUTO.label).performScrollTo().performClick()
        assertEquals(listOf(ScreenOrientation.LANDSCAPE, ScreenOrientation.AUTO), orientations)
    }

    @Test
    fun eachModeKeepsItsOwnMatchSettings() {
        showApp()
        composeRule.onNodeWithText("簡易モード").performClick()
        composeRule.onNodeWithText(CpuLevel.MASTER.label).performClick()
        composeRule.onNodeWithText(ReadStartMode.TAP.label).performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("戻る").performClick()

        // 競技モードは別の設定のまま
        composeRule.onNodeWithText("競技モード").performClick()
        composeRule.onNodeWithText(MemorizeTime.MIN_1.label).performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("戻る").performClick()

        assertEquals(CpuLevel.MASTER, configStore.load(RuleMode.SIMPLE).cpuLevel)
        assertEquals(ReadStartMode.TAP, configStore.load(RuleMode.SIMPLE).readStartMode)
        assertEquals(GameConfig().cpuLevel, configStore.load(RuleMode.COMPETITIVE).cpuLevel)
        assertEquals(MemorizeTime.MIN_1, configStore.load(RuleMode.COMPETITIVE).memorizeTime)
        assertEquals(GameConfig().memorizeTime, configStore.load(RuleMode.SIMPLE).memorizeTime)

        // もう一度選ぶと、そのモードの設定で対戦する
        composeRule.onNodeWithText("簡易モード").performClick()
        composeRule.onNodeWithText(CpuLevel.MASTER.label).assertIsSelected()
        composeRule.onNodeWithText("対戦開始").performClick()
        composeRule.onNodeWithText("相手（名人）").assertExists()
        composeRule.onNodeWithText("読み始める").assertExists()
    }

    @Test
    fun theMatchIsStartedWithTheModeSettingsAndTheDisplaySettings() {
        configStore.save(GameConfig(ruleMode = RuleMode.SIMPLE, emptyCardCount = 30, cpuLevel = CpuLevel.BEGINNER))
        settingsStore.save(AppSettings(flipOpponentCards = false, verseStyle = VerseStyle.READING))
        showApp()
        composeRule.onNodeWithText("簡易モード").performClick()
        composeRule.onNodeWithText("対戦開始").performClick()

        val config = activeMatch()!!.uiState.value.config
        assertEquals(
            GameConfig(
                ruleMode = RuleMode.SIMPLE,
                emptyCardCount = 30,
                cpuLevel = CpuLevel.BEGINNER,
                flipOpponentCards = false,
                verseStyle = VerseStyle.READING,
            ),
            config,
        )
        composeRule.onNodeWithText("相手（初級）").assertExists()
    }

    @Test
    fun theFirstMatchShowsVersesInHistoricalKana() {
        // 何も保存していない（初めて起動した）ときは、歴史的仮名遣いで対戦する
        showApp()
        composeRule.onNodeWithText("競技モード").performClick()
        composeRule.onNodeWithText("対戦開始").performClick()
        assertEquals(VerseStyle.KANA, activeMatch()!!.uiState.value.config.verseStyle)
    }
}
