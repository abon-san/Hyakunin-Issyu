package io.github.abonsan.hyakunin.ui.card

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.ui.Fixtures
import io.github.abonsan.hyakunin.ui.theme.CardEdge
import io.github.abonsan.hyakunin.ui.theme.CardFace
import io.github.abonsan.hyakunin.ui.theme.Kin
import io.github.abonsan.hyakunin.ui.theme.Shu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.abs
import kotlin.math.roundToInt

/** 札の状態の見た目。選べる札は朱、送られてきた札は金の縁にし、暗くした札は全体を暗くする。逆さの札は札ごと回す。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w900dp-h600dp-mdpi")
class TorifudaStateTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun edgeColorShowsTheState() {
        val normal = Fixtures.poem(1).shimoKana
        val selectable = Fixtures.poem(2).shimoKana
        val isNew = Fixtures.poem(3).shimoKana
        val both = Fixtures.poem(4).shimoKana
        val dimmed = Fixtures.poem(5).shimoKana
        composeRule.setContent {
            Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Torifuda(normal, Modifier.width(CARD_WIDTH.dp))
                Torifuda(selectable, Modifier.width(CARD_WIDTH.dp), selectable = true)
                Torifuda(isNew, Modifier.width(CARD_WIDTH.dp), isNew = true)
                Torifuda(both, Modifier.width(CARD_WIDTH.dp), isNew = true, selectable = true)
                Torifuda(dimmed, Modifier.width(CARD_WIDTH.dp), dimmed = true)
            }
        }
        assertCard(normal, edge = CardEdge)
        assertCard(selectable, edge = Shu)
        assertCard(isNew, edge = Kin)
        // 送られてきた札でも、送る札として選べるときは朱にする
        assertCard(both, edge = Shu)
        assertDimmed(dimmed)
    }

    @Test
    fun redrawsWhenTheStateChanges() {
        // 縁の色と暗さは描くときに読むので、同じ札の状態が変われば描き直す
        val shimoKana = Fixtures.poem(1).shimoKana
        var selectable by mutableStateOf(false)
        var isNew by mutableStateOf(false)
        var dimmed by mutableStateOf(false)
        composeRule.setContent {
            Torifuda(shimoKana, Modifier.width(CARD_WIDTH.dp), isNew = isNew, selectable = selectable, dimmed = dimmed)
        }
        assertCard(shimoKana, edge = CardEdge)
        selectable = true
        composeRule.waitForIdle()
        assertCard(shimoKana, edge = Shu)
        selectable = false
        isNew = true
        composeRule.waitForIdle()
        assertCard(shimoKana, edge = Kin)
        isNew = false
        dimmed = true
        composeRule.waitForIdle()
        assertDimmed(shimoKana)
        dimmed = false
        composeRule.waitForIdle()
        assertCard(shimoKana, edge = CardEdge)
    }

    @Test
    fun upsideDownTurnsTheWholeCard() {
        val shimoKana = Fixtures.poem(78).shimoKana
        var upsideDown by mutableStateOf(false)
        composeRule.setContent {
            Torifuda(shimoKana, Modifier.width(UPSIDE_DOWN_WIDTH.dp), upsideDown = upsideDown)
        }
        val upright = capture(shimoKana)
        upsideDown = true
        composeRule.waitForIdle()
        val turned = capture(shimoKana)
        assertEquals(upright.width, turned.width)
        assertEquals(upright.height, turned.height)
        val (w, h) = turned.width to turned.height
        var worst = 0f
        var unchanged = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                worst = maxOf(worst, difference(turned[x, y], upright[w - 1 - x, h - 1 - y]))
                if (difference(turned[x, y], upright[x, y]) <= 0.1f) unchanged++
            }
        }
        // 逆さの札は、正しい向きの札を180度回したもの（字の縁のにじみの違いだけを許す）
        assertTrue("回した札と違う（差 $worst）", worst <= 0.1f)
        // 字の置き方は上下・左右に対称ではないので、回すと見た目が変わる
        assertTrue("逆さにしても変わらない", unchanged < w * h * 0.98f)
    }

    private fun difference(a: Color, b: Color): Float =
        maxOf(abs(a.red - b.red), abs(a.green - b.green), abs(a.blue - b.blue))

    private fun capture(shimoKana: String): PixelMap =
        composeRule.onNodeWithContentDescription(shimoKana).captureToImage().toPixelMap()

    /** 四辺の縁の中ほどの色と、左の縁のすぐ内側の地の色。 */
    private fun sample(card: PixelMap): Pair<List<Color>, Color> {
        val edge = (card.width * 40 / 619.7f).roundToInt()
        val half = edge / 2
        val edges = listOf(
            card[half, card.height / 2],
            card[card.width - 1 - half, card.height / 2],
            card[card.width / 2, half],
            card[card.width / 2, card.height - 1 - half],
        )
        return edges to card[edge + 3, card.height / 2]
    }

    private fun assertCard(shimoKana: String, edge: Color) {
        val (edges, face) = sample(capture(shimoKana))
        edges.forEach { assertColor("$shimoKana の縁", edge, it) }
        assertColor("$shimoKana の地", CardFace, face)
    }

    /** 通常の縁の札を暗くしたもの。縁も地も暗い。 */
    private fun assertDimmed(shimoKana: String) {
        val (edges, face) = sample(capture(shimoKana))
        edges.forEach { assertTrue("$shimoKana の縁が暗くない", it.luminance() < CardEdge.luminance() * 0.8f) }
        assertTrue("$shimoKana の地が暗くない", face.luminance() < CardFace.luminance() * 0.8f)
    }

    private fun assertColor(label: String, expected: Color, actual: Color) {
        assertTrue("$label: $expected のはずが $actual", difference(expected, actual) <= TOLERANCE)
    }
}

/** 色を見る札の幅（dp。mdpi なので px も同じ）。縁は 8px。 */
private const val CARD_WIDTH = 120

/** 逆さを比べる札の幅。 */
private const val UPSIDE_DOWN_WIDTH = 200

/** 色の許容差（0..1）。 */
private const val TOLERANCE = 2f / 255
