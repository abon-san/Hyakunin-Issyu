package io.github.abonsan.hyakunin.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.CpuLevel
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.ui.home.GameConfigStore
import io.github.abonsan.hyakunin.ui.theme.HyakuninTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** ホームと対戦画面の行き来、設定の保存。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class HyakuninAppTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val store = GameConfigStore(ApplicationProvider.getApplicationContext<Context>())

    private fun pressBack() {
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    @Test
    fun startsAMatchAndComesBackHome() {
        composeRule.setContent { HyakuninTheme { HyakuninApp(store) } }
        composeRule.onNodeWithText("百人一首").assertExists()

        composeRule.onNodeWithText("対戦開始").performClick()
        composeRule.onNodeWithText("まもなく読み始めます").assertExists()

        // 戻るとやめるかを確かめる。続けると対戦に残る
        pressBack()
        composeRule.onNodeWithText("対戦をやめますか？").assertExists()
        composeRule.onNodeWithText("続ける").performClick()
        composeRule.onNodeWithText("まもなく読み始めます").assertExists()

        pressBack()
        composeRule.onNodeWithText("やめる").performClick()
        composeRule.onNodeWithText("対戦開始").assertExists()
    }

    @Test
    fun settingsAreSavedAsTheyChange() {
        composeRule.setContent { HyakuninTheme { HyakuninApp(store) } }
        composeRule.onNodeWithText(RuleMode.SIMPLE.label).performClick()
        composeRule.onNodeWithText(CpuLevel.ADVANCED.label).performClick()

        val saved = store.load()
        assertEquals(RuleMode.SIMPLE, saved.ruleMode)
        assertEquals(CpuLevel.ADVANCED, saved.cpuLevel)
    }
}
