package io.github.abonsan.hyakunin.ui.match

import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 一般的な大きさのスマホの横画面（891×411dp）で、対戦画面の主な場面を撮る。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w891dp-h411dp-land-xxhdpi")
class LandscapeMatchScreenshotTest : MatchScenesScreenshotTest(prefix = "land_match", fieldsAside = true) {

    @Test
    fun emptyCard() {
        show(Scenes.emptyCard())
        composeRule.onNodeWithText("空札でした").assertExists()
        capture("result_empty")
    }

    @Test
    fun finishedInADraw() {
        show(Scenes.draw())
        composeRule.onNodeWithText("引き分けです").assertExists()
        capture("finished_draw")
    }
}

/** 小さめのスマホの横画面（640×360dp）でも、文字が欠けたり重なったりしないかを撮って確かめる。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w640dp-h360dp-land-xhdpi")
class SmallLandscapeMatchScreenshotTest : MatchScenesScreenshotTest(prefix = "land_small_match", fieldsAside = true)
