package io.github.abonsan.hyakunin.ui.match

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.data.Joka
import io.github.abonsan.hyakunin.game.LeadInVerse
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.ui.Fixtures
import io.github.abonsan.hyakunin.ui.theme.HyakuninTheme
import io.github.abonsan.hyakunin.ui.theme.Usuzumi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 読み上げ中と前置きの欄で、新しく見せた文字が透明から浮かび上がるのを、描いた画素で確かめる。時計は手で進め、
 * 見せる前・見せてから約 0・100・200ms・浮かび上がり終えた後を撮り、新しい文字の濃さ（終えたときを 1）を比べる。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class ReadingFadeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val clock get() = composeRule.mainClock

    /** 欄の大きさ（スマホの縦画面の中央の欄くらい）。 */
    private val panel = Modifier.size(360.dp, 520.dp)

    private fun capture(): PixelMap = composeRule.onRoot().captureToImage().toPixelMap()

    /** 見せる文字を増やす [reveal] をしてから、約 0・100・200ms と、終えた後に撮る。 */
    private fun fadeFrames(reveal: () -> Unit): List<PixelMap> {
        reveal()
        // 1フレーム目で新しい数を受け取り、2フレーム目から浮かび上がる
        clock.advanceTimeByFrame()
        clock.advanceTimeByFrame()
        val frames = mutableListOf(capture())
        clock.advanceTimeBy(100L)
        frames += capture()
        clock.advanceTimeBy(100L)
        frames += capture()
        clock.advanceTimeBy(300L)
        frames += capture()
        return frames
    }

    /** 見せる前 [before] から、約 0・100・200ms と終えた後 [frames] で、新しい文字がそのとおりに濃くなる。 */
    private fun assertFadesIn(before: PixelMap, frames: List<PixelMap>) {
        val (at0, at100, at200, settled) = frames
        val ink = NewInk(before, settled)
        assertTrue("0ms: ${ink.of(at0)}", ink.of(at0) < 0.2f)
        assertTrue("100ms: ${ink.of(at100)}", ink.of(at100) in 0.55f..0.9f)
        assertTrue("200ms: ${ink.of(at200)}", ink.of(at200) > 0.95f)
        // 変わるのは新しい文字の1字ぶんだけ（ほかの文字は動かず、色も変わらない）
        assertTrue("新しい文字の大きさ: ${ink.width}x${ink.height}", ink.width < 140 && ink.height < 140)
        assertEquals(0, ink.changedElsewhere(at100))
    }

    @Test
    fun theNewKanaOfTheReadingFadesIn() {
        var reading by mutableStateOf(Fixtures.reading(no = 61, readNumber = 4, totalReads = 20, revealed = 3))
        composeRule.setContent { HyakuninTheme { ReadingPanel(reading, VerseStyle.READING, notice = null, modifier = panel) } }
        clock.autoAdvance = false
        val before = capture()
        assertFadesIn(before, fadeFrames { reading = reading.copy(revealedKana = 4) })
    }

    @Test
    fun theNewKanaOfTheLeadInFadesIn() {
        val lead = Scenes.jokaLeadIn(verse = LeadInVerse.KAMI, revealed = 3).leadIn!!
        var state by mutableStateOf(lead)
        composeRule.setContent { HyakuninTheme { LeadInPanel(state, VerseStyle.KANA, modifier = panel) } }
        clock.autoAdvance = false
        val before = capture()
        assertFadesIn(before, fadeFrames { state = lead.copy(revealedKana = 4) })
    }

    @Test
    fun aKanaFadesInToTheSubduedToneOfTheMa() {
        // 前置きの間合いでは句を控えめな色で見せる。新しく見せた文字は、その色まで浮かび上がる
        val verse = verseOf(Joka.poem, VerseStyle.KANA)
        val subdued = SpanStyle(color = Usuzumi)
        var shown by mutableIntStateOf(20)
        composeRule.setContent {
            HyakuninTheme {
                VersePanel(
                    title = "序歌",
                    verse = verse,
                    spanFor = { if (it < shown) subdued else Hidden },
                    fade = rememberKanaFadeIn(verse, shown),
                    description = "",
                    notice = null,
                    modifier = panel,
                    titleRequired = true,
                )
            }
        }
        clock.autoAdvance = false
        val before = capture()
        val frames = fadeFrames { shown = 21 }
        assertFadesIn(before, frames)
        // 浮かび上がり終えた文字は、控えめな色（初めから見せたときと同じ画素）
        val darkest = NewInk(before, frames.last()).darkestColor(frames.last())
        assertEquals(Usuzumi.red, darkest.red, 0.04f)
        assertEquals(Usuzumi.green, darkest.green, 0.04f)
        assertEquals(Usuzumi.blue, darkest.blue, 0.04f)
    }

    @Test
    fun theRepeatedShimoIsHiddenAtOnce() {
        // 序歌の下の句の2回目は、下の句を初めから見せ直す: 読み終えた下の句は、次のフレームですぐに消える
        var state by mutableStateOf(Scenes.jokaLeadIn(pass = 1, revealed = 14).leadIn!!)
        val again = Scenes.jokaLeadIn(pass = 2, revealed = 0).leadIn!!
        var fresh by mutableStateOf(false)
        composeRule.setContent {
            HyakuninTheme {
                if (fresh) {
                    // 比べるための、2回目の初めから表示した欄
                    key("fresh") { LeadInPanel(again, VerseStyle.KANA, modifier = panel) }
                } else {
                    LeadInPanel(state, VerseStyle.KANA, modifier = panel)
                }
            }
        }
        clock.autoAdvance = false
        state = again
        composeRule.waitForIdle()
        clock.advanceTimeByFrame()
        val hidden = capture()
        fresh = true
        composeRule.waitForIdle()
        clock.advanceTimeByFrame()
        assertEquals(0, differingPixels(hidden, capture()))
    }

    @Test
    fun kanaShownWhenThePanelAppearsAreFullyShownAtOnce() {
        // 開いたとき（回した・戻ってきたときも）にもう読んである文字は、最初のフレームから元の色
        clock.autoAdvance = false
        composeRule.setContent {
            HyakuninTheme {
                ReadingPanel(Fixtures.reading(no = 61, readNumber = 4, totalReads = 20, revealed = 9), VerseStyle.READING, null, panel)
            }
        }
        val first = capture()
        clock.advanceTimeBy(1_000L)
        assertEquals(0, differingPixels(first, capture()))
    }
}

/**
 * 新しく見せた文字の濃さ。見せる前 [before] と浮かび上がり終えた後 [after] で明るさが変わる画素を新しい文字とし、
 * そこが見せる前からどれだけ暗くなったかを、終えたときを 1 として測る（文字の不透明度に比例する）。
 */
private class NewInk(private val before: PixelMap, after: PixelMap) {
    private val pixels = buildList {
        for (y in 0 until before.height) {
            for (x in 0 until before.width) {
                if (luminance(before[x, y]) - luminance(after[x, y]) > 0.05f) add(x to y)
            }
        }
    }
    private val full = darkening(after)

    init {
        check(pixels.isNotEmpty()) { "新しい文字が描かれていない" }
    }

    val width: Int = pixels.maxOf { it.first } - pixels.minOf { it.first } + 1
    val height: Int = pixels.maxOf { it.second } - pixels.minOf { it.second } + 1

    fun of(image: PixelMap): Float = (darkening(image) / full).toFloat()

    /** 新しい文字のまわり（外接矩形）の外で、見せる前と違う画素の数。 */
    fun changedElsewhere(image: PixelMap): Int {
        val xs = pixels.minOf { it.first } - 2..pixels.maxOf { it.first } + 2
        val ys = pixels.minOf { it.second } - 2..pixels.maxOf { it.second } + 2
        var count = 0
        for (y in 0 until before.height) {
            for (x in 0 until before.width) {
                if ((x !in xs || y !in ys) && before[x, y] != image[x, y]) count++
            }
        }
        return count
    }

    /** 新しい文字のうち、[image] で一番暗い画素の色（文字の色）。 */
    fun darkestColor(image: PixelMap): Color = pixels.map { (x, y) -> image[x, y] }.minBy(::luminance)

    private fun darkening(image: PixelMap): Double =
        pixels.sumOf { (x, y) -> (luminance(before[x, y]) - luminance(image[x, y])).toDouble() }
}

private fun luminance(c: Color): Float = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue

private fun differingPixels(a: PixelMap, b: PixelMap): Int {
    var count = 0
    for (y in 0 until a.height) for (x in 0 until a.width) if (a[x, y] != b[x, y]) count++
    return count
}
