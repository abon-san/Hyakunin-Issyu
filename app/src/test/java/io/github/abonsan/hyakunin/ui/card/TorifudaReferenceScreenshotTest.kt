package io.github.abonsan.hyakunin.ui.card

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.abonsan.hyakunin.ui.Fixtures
import io.github.abonsan.hyakunin.ui.SCREENSHOT_DIR
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * デザインの参照画像（design/card/torifuda_reference.png）の2枚と同じ札を、参照画像と同じ大きさ
 * （1px = 札の 1px。幅 619.7px・高さ 870px）で撮る。tools/compare_torifuda.py で参照画像と数値で比べる。
 * 参照画像に無い6行の札（21番）も、見比べられるよう同じ大きさで撮る。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w700dp-h1000dp-mdpi")
class TorifudaReferenceScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun poem78() = capture(78)

    @Test
    fun poem58() = capture(58)

    /** 16字で6行になる札。字の大きさと行の間隔を 5/6 にして、5行の札と同じ範囲に収める。 */
    @Test
    fun poem21() = capture(21)

    @Test
    fun ignoresTheFontScaleSetting() {
        val shimoKana = Fixtures.poem(78).shimoKana
        var fontScale by mutableFloatStateOf(1f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                Torifuda(shimoKana, Modifier.width(REFERENCE_WIDTH.dp))
            }
        }
        val normal = composeRule.onNodeWithContentDescription(shimoKana).captureToImage().pixels()
        fontScale = 2f
        composeRule.waitForIdle()
        val large = composeRule.onNodeWithContentDescription(shimoKana).captureToImage().pixels()
        assertTrue("字が書かれていない", normal.any { it.isInk() })
        assertArrayEquals(normal, large)
    }

    private fun capture(no: Int) {
        val shimoKana = Fixtures.poem(no).shimoKana
        composeRule.setContent {
            Torifuda(shimoKana, Modifier.width(REFERENCE_WIDTH.dp))
        }
        composeRule.onNodeWithContentDescription(shimoKana)
            .captureRoboImage("$SCREENSHOT_DIR/torifuda_reference_$no.png")
    }
}

/** 参照画像の札の幅（px）。 */
private const val REFERENCE_WIDTH = 619.7f

private fun ImageBitmap.pixels(): IntArray = toPixelMap().buffer

/** 墨（黒）の画素か。縁の緑や札面の生成りではない。 */
private fun Int.isInk(): Boolean = (this shr 16 and 0xFF) < 0x20 && (this shr 8 and 0xFF) < 0x20 && (this and 0xFF) < 0x20
