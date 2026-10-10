package io.github.abonsan.hyakunin.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.CpuLevel
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.ReadingSpeed
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.ui.captureScreenshot
import io.github.abonsan.hyakunin.ui.theme.HyakuninTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class HomeScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun home() {
        composeRule.setContent {
            HyakuninTheme {
                HomeScreen(config = GameConfig(), onConfigChange = {}, onStart = {})
            }
        }
        composeRule.captureScreenshot("home")
    }

    @Test
    fun homeSimpleRule() {
        val config = GameConfig(
            ruleMode = RuleMode.SIMPLE,
            emptyCardCount = 90,
            cpuLevel = CpuLevel.MASTER,
            readingSpeed = ReadingSpeed.FAST,
            flipOpponentCards = false,
        )
        composeRule.setContent {
            HyakuninTheme {
                HomeScreen(config = config, onConfigChange = {}, onStart = {})
            }
        }
        composeRule.captureScreenshot("home_simple")
    }

    @Test
    fun settingsAreEditable() {
        var config by mutableStateOf(GameConfig(emptyCardCount = 1))
        var started = false
        composeRule.setContent {
            HyakuninTheme {
                HomeScreen(config = config, onConfigChange = { config = it }, onStart = { started = true })
            }
        }
        composeRule.onNodeWithText(RuleMode.SIMPLE.label).performClick()
        composeRule.onNodeWithText(CpuLevel.MASTER.label).performClick()
        composeRule.onNodeWithText(ReadingSpeed.SLOW.label).performClick()
        composeRule.onNodeWithText("敵陣の札を逆さに表示").performClick()
        composeRule.onNodeWithContentDescription("空札を1枚減らす").performClick()
        composeRule.onNodeWithContentDescription("空札を1枚減らす").performClick()
        composeRule.onNodeWithContentDescription("空札を1枚増やす").performClick()
        composeRule.onNodeWithText(RuleMode.SIMPLE.description).assertExists()
        composeRule.onNodeWithText(CpuLevel.MASTER.label).assertIsSelected()

        assertEquals(
            GameConfig(
                ruleMode = RuleMode.SIMPLE,
                emptyCardCount = 1,
                cpuLevel = CpuLevel.MASTER,
                readingSpeed = ReadingSpeed.SLOW,
                flipOpponentCards = false,
            ),
            config,
        )
        composeRule.onNodeWithText("対戦開始").performClick()
        assertEquals(true, started)
    }
}
