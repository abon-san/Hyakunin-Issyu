package io.github.abonsan.hyakunin.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.CpuLevel
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.MemorizeTime
import io.github.abonsan.hyakunin.game.ReadInterval
import io.github.abonsan.hyakunin.game.ReadStartMode
import io.github.abonsan.hyakunin.game.ReadingSpeed
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.ui.settings.AppSettings
import io.github.abonsan.hyakunin.ui.settings.ScreenOrientation
import io.github.abonsan.hyakunin.ui.settings.SettingsScreen
import io.github.abonsan.hyakunin.ui.theme.HyakuninTheme
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 縦画面のトップ・対戦設定・設定を撮り、操作が呼び出し側へ渡るかを確かめる。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class MenuScreenshotTest : MenuScreensScreenshotTest(prefix = "") {

    @Test
    fun topLeadsToEachModeAndSettings() {
        val selected = mutableListOf<RuleMode>()
        var settingsOpened = 0
        composeRule.setContent {
            HyakuninTheme {
                TopScreen(onSelectMode = { selected += it }, onOpenSettings = { settingsOpened++ })
            }
        }
        composeRule.onNodeWithText("競技モード").performClick()
        composeRule.onNodeWithText("簡易モード").performClick()
        composeRule.onNodeWithText("設定").performClick()
        assertEquals(listOf(RuleMode.COMPETITIVE, RuleMode.SIMPLE), selected)
        assertEquals(1, settingsOpened)
    }

    @Test
    fun matchSetupIsEditable() {
        var config by mutableStateOf(GameConfig(emptyCardCount = 1))
        var started = false
        var back = false
        composeRule.setContent {
            HyakuninTheme {
                MatchSetupScreen(
                    config = config,
                    onConfigChange = { config = it },
                    onStart = { started = true },
                    onBack = { back = true },
                )
            }
        }
        composeRule.onNodeWithText(CpuLevel.MASTER.label).performClick()
        composeRule.onNodeWithText(ReadingSpeed.SLOW.label).performClick()
        composeRule.onNodeWithContentDescription("空札を1枚減らす").performClick()
        composeRule.onNodeWithContentDescription("空札を1枚減らす").performClick()
        composeRule.onNodeWithContentDescription("空札を1枚増やす").performClick()
        composeRule.onNodeWithText(MemorizeTime.MIN_1.label).performScrollTo().performClick()
        composeRule.onNodeWithText(ReadInterval.SEC_6.label).performScrollTo().performClick()
        composeRule.onNodeWithText(CpuLevel.MASTER.label).assertIsSelected()
        assertEquals(
            GameConfig(
                emptyCardCount = 1,
                cpuLevel = CpuLevel.MASTER,
                readingSpeed = ReadingSpeed.SLOW,
                memorizeTime = MemorizeTime.MIN_1,
                readInterval = ReadInterval.SEC_6,
            ),
            config,
        )

        // タップで読み始めるときは、待ち時間を選べない
        composeRule.onNodeWithText(ReadStartMode.TAP.label).performScrollTo().performClick()
        assertEquals(ReadStartMode.TAP, config.readStartMode)
        composeRule.onNodeWithText(MemorizeTime.SEC_30.label).assertIsNotEnabled()
        composeRule.onNodeWithText(ReadInterval.SEC_2.label).assertIsNotEnabled()
        composeRule.onNodeWithText(ReadStartMode.AUTO.label).performClick()
        composeRule.onNodeWithText(MemorizeTime.SEC_30.label).assertIsEnabled()

        composeRule.onNodeWithText("対戦開始").performClick()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        assertEquals(true, started)
        assertEquals(true, back)
    }

    @Test
    fun settingsAreEditable() {
        var settings by mutableStateOf(AppSettings())
        var back = false
        composeRule.setContent {
            HyakuninTheme {
                SettingsScreen(settings = settings, onSettingsChange = { settings = it }, onBack = { back = true })
            }
        }
        composeRule.onNodeWithText(ScreenOrientation.LANDSCAPE.label).performClick()
        composeRule.onNodeWithText(ScreenOrientation.LANDSCAPE.description).assertExists()
        composeRule.onNodeWithText("敵陣の札を逆さに表示").performClick()
        assertEquals(AppSettings(orientation = ScreenOrientation.LANDSCAPE, flipOpponentCards = false), settings)
        composeRule.onNodeWithContentDescription("戻る").performClick()
        assertEquals(true, back)
    }
}
