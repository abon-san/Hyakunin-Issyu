package io.github.abonsan.hyakunin

import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.ui.captureWholeScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 実際の [MainActivity] を起動してホームを撮る（テーマや端から端までの表示を含む）。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class MainActivityTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesIntoHome() {
        composeRule.onNodeWithText("百人一首").assertExists()
        composeRule.onNodeWithText("対戦開始").assertExists()
        composeRule.captureWholeScreen("app_home")
    }
}
