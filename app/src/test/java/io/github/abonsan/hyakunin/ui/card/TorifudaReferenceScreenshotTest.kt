package io.github.abonsan.hyakunin.ui.card

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * デザインの参照画像（design/card/torifuda_reference.png）の2枚と同じ札を、参照画像と同じ大きさ
 * （1px = 札の 1px。幅 619.7px・高さ 870px）で撮る。tools/compare_torifuda.py で参照画像と数値で比べる。
 * 参照画像の札は濁点つきで書かれているので、比べる札も濁点を外さずに書く（列の分け方はアプリと同じ）。
 * アプリで実際に出す札（濁点なし）も、同じ大きさで別の名前（torifuda_display_<番号>.png）で撮る。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w700dp-h1000dp-mdpi")
class TorifudaReferenceScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun poem78() = captureReference(78)

    @Test
    fun poem58() = captureReference(58)

    @Test
    fun displayed78() {
        captureDisplayed(78)
    }

    @Test
    fun displayed58() {
        captureDisplayed(58)
    }

    /**
     * 16字の札（5・5・6）。3列目「いてつるかな」は字の大きさを変えずに字間を詰め、インクの上端を
     * 1行目の線（122）から5行目の線（660.4）まで等間隔（107.68）に置く。撮った絵の字の位置で確かめる。
     */
    @Test
    fun displayed21() {
        val card = captureDisplayed(21)
        val right = card.glyphRows(column = 0, count = 5)
        val middle = card.glyphRows(column = 1, count = 5)
        val left = card.glyphRows(column = 2, count = 6)
        // 上下の字が重なっていれば、インクのある行がつながって字の数が足りなくなる
        assertEquals(listOf(5, 5, 6), listOf(right.size, middle.size, left.size))
        right.forEachIndexed { row, rows -> assertNear("1列目 ${row + 1}字目", 122f + 134.6f * row, rows.first) }
        middle.forEachIndexed { row, rows -> assertNear("2列目 ${row + 1}字目", 122f + 134.6f * row, rows.first) }
        left.forEachIndexed { row, rows -> assertNear("3列目 ${row + 1}字目", 122f + 107.68f * row, rows.first) }
        // 最後の字は5字の列の最後の字と同じ高さから書く
        assertNear("3列目の最後の字", right.last().first.toFloat(), left.last().first)
        // 詰めた列でも、字と字のあいだは十分にあく（狭いところで 25px ほど）
        left.zipWithNext { upper, lower ->
            assertTrue("3列目の字の間が狭い: $upper $lower", lower.first - upper.last > 10)
        }
    }

    @Test
    fun writesWithoutDakutenAndDescribesTheVerseAsIs() {
        // 78番「いくよねざめぬ　すまのせきもり」。札には「ざ」を「さ」と書き、読み上げの説明は濁点つきのまま
        val shimoKana = Fixtures.poem(78).shimoKana
        var columns by mutableStateOf<List<String>?>(null)
        composeRule.setContent {
            val written = columns
            if (written == null) {
                Torifuda(shimoKana, Modifier.width(CARD_WIDTH.dp))
            } else {
                Torifuda(written, shimoKana, Modifier.width(CARD_WIDTH.dp))
            }
        }
        composeRule.onNodeWithContentDescription(shimoKana)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(shimoKana)))
        composeRule.onNodeWithContentDescription(withoutDakuten(shimoKana)).assertDoesNotExist()
        val card = capture(shimoKana)
        columns = torifudaColumns(withoutDakuten(shimoKana))
        composeRule.waitForIdle()
        val plain = capture(shimoKana)
        columns = torifudaColumns(shimoKana)
        composeRule.waitForIdle()
        val voiced = capture(shimoKana)
        assertArrayEquals("濁点を外した字と違う", plain, card)
        assertFalse("濁点つきの字と同じ", voiced.contentEquals(card))
    }

    @Test
    fun ignoresTheFontScaleSetting() {
        val shimoKana = Fixtures.poem(78).shimoKana
        var fontScale by mutableFloatStateOf(1f)
        composeRule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                Torifuda(shimoKana, Modifier.width(REFERENCE_WIDTH.dp))
            }
        }
        val normal = capture(shimoKana)
        fontScale = 2f
        composeRule.waitForIdle()
        val large = capture(shimoKana)
        assertTrue("字が書かれていない", normal.any { it.isInk() })
        assertArrayEquals(normal, large)
    }

    /** 参照画像と比べる札。参照画像と同じく濁点つきで書く。 */
    private fun captureReference(no: Int) {
        val shimoKana = Fixtures.poem(no).shimoKana
        composeRule.setContent {
            Torifuda(torifudaColumns(shimoKana), shimoKana, Modifier.width(REFERENCE_WIDTH.dp))
        }
        composeRule.onNodeWithContentDescription(shimoKana)
            .captureRoboImage("$SCREENSHOT_DIR/torifuda_reference_$no.png")
    }

    /** アプリで実際に出す札（濁点なし）を撮り、撮った絵を返す。 */
    private fun captureDisplayed(no: Int): PixelMap {
        val shimoKana = Fixtures.poem(no).shimoKana
        composeRule.setContent {
            Torifuda(shimoKana, Modifier.width(REFERENCE_WIDTH.dp))
        }
        val node = composeRule.onNodeWithContentDescription(shimoKana)
        node.captureRoboImage("$SCREENSHOT_DIR/torifuda_display_$no.png")
        return node.captureToImage().toPixelMap()
    }

    private fun capture(shimoKana: String): IntArray =
        composeRule.onNodeWithContentDescription(shimoKana).captureToImage().pixels()
}

/** 参照画像の札の幅（px）。 */
private const val REFERENCE_WIDTH = 619.7f

/** 濁点の有無を比べる札の幅（dp。mdpi なので px も同じ）。 */
private const val CARD_WIDTH = 200

private fun ImageBitmap.pixels(): IntArray = toPixelMap().buffer

/** 墨（黒）の画素か。縁の緑や札面の生成りではない。 */
private fun Int.isInk(): Boolean = (this shr 16 and 0xFF) < 0x20 && (this shr 8 and 0xFF) < 0x20 && (this and 0xFF) < 0x20

/**
 * 撮った札（参照画像の大きさ）の [column] 列目（右から数える）に書かれた字の、インクのある行の範囲（px）。上から [count] 字分。
 * 墨が半分より濃い画素を字とみなす。字の中の隙間（「き」など）は字と字の間より狭いので、狭い隙間からつなぐ。
 */
private fun PixelMap.glyphRows(column: Int, count: Int): List<IntRange> {
    // 列の中心は札の中心と、その左右に 158.9px。隣の列にかからない幅（中心から 158.9 / 2）を見る
    val step = width * 158.9f / 619.7f
    val center = width / 2f + step * (1 - column)
    val xs = (center - step / 2).roundToInt()..(center + step / 2).roundToInt()
    // 縁（40px）の内側だけを見る
    val edge = (width * 40 / 619.7f).roundToInt()
    val runs = mutableListOf<IntRange>()
    for (y in edge + 1 until height - edge - 1) {
        if (xs.none { x -> this[x, y].let { (it.red + it.green + it.blue) / 3 < 0.5f } }) continue
        val last = runs.lastOrNull()
        if (last != null && last.last == y - 1) runs[runs.lastIndex] = last.first..y else runs += y..y
    }
    while (runs.size > count) {
        val i = (0 until runs.lastIndex).minBy { runs[it + 1].first - runs[it].last }
        runs[i] = runs[i].first..runs[i + 1].last
        runs.removeAt(i + 1)
    }
    return runs
}

/** 字のインクの上端の行 [actual] が、[expected]（px、小数）から 2px 以内にあるか。 */
private fun assertNear(label: String, expected: Float, actual: Int) {
    assertTrue("$label: 上端 $actual（想定 $expected）", abs(actual - expected) <= 2f)
}
