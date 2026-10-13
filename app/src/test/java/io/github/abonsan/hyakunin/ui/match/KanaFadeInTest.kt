package io.github.abonsan.hyakunin.ui.match

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * 字幕の1字ずつのフェードイン（KanaFadeIn）。時計は手で進め（mainClock.autoAdvance = false）、
 * 見せた文字の不透明度を、見せてから約 0・100・200ms で確かめる。
 */
/** テストの時計の1フレーム（ms）。 */
private const val FRAME_MILLIS = 16L

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class KanaFadeInTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var shown by mutableIntStateOf(0)
    private var fade: KanaFadeIn? = null

    private val clock get() = composeRule.mainClock

    /** 見せる文字の数 [initial] で、浮かび上がりだけを置く。 */
    private fun showFade(initial: Int) {
        shown = initial
        composeRule.setContent { fade = rememberKanaFadeIn("verse", shown) }
    }

    /** 見せる文字の数を [count] に変え、浮かび上がりが受け取るまでフレームを進める（ここを見せた時刻とする）。 */
    private fun reveal(count: Int) {
        change(count) { fade!!.isRising(count - 1) }
    }

    /** 見せる文字の数を [count] に変え、[done] になるまで（10 フレームまで）フレームを進める。 */
    private fun change(count: Int, done: () -> Boolean) {
        shown = count
        // 書き込みを Compose に知らせてから（時計は進めない）、受け取るまで進める
        composeRule.waitForIdle()
        repeat(10) { if (!done()) clock.advanceTimeByFrame() }
        check(done()) { "見せる文字の数 $count を受け取らない" }
    }

    @Test
    fun aRevealedKanaFadesInOver200msWithAnEaseOut() {
        showFade(3)
        clock.autoAdvance = false
        reveal(4)
        val fade = fade!!
        // 約 0ms: ほとんど透明。前から見せている文字は元の色のまま
        assertTrue("0ms: ${fade.alphaOf(3)}", fade.alphaOf(3) < 0.2f)
        assertEquals(1f, fade.alphaOf(2))
        assertTrue(fade.fading)
        // 約 100ms: ease-out なので、まっすぐ（0.5）より濃い
        clock.advanceTimeBy(100L)
        assertTrue("100ms: ${fade.alphaOf(3)}", fade.alphaOf(3) in 0.55f..0.9f)
        // 約 200ms: 元の色
        clock.advanceTimeBy(100L)
        assertTrue("200ms: ${fade.alphaOf(3)}", fade.alphaOf(3) >= 0.97f)
        clock.advanceTimeBy(100L)
        assertTrue(fade.isSettled(3))
        assertEquals(1f, fade.alphaOf(3))
        // 浮かび上がり終えたら、毎フレームの仕事はない
        assertFalse(fade.fading)
        // まだ見せていない文字は透明のまま
        assertEquals(0f, fade.alphaOf(4))
    }

    @Test
    fun theFadeFollowsTheEasingCurve() {
        showFade(0)
        clock.autoAdvance = false
        reveal(1)
        val fade = fade!!
        val start = clock.currentTime
        var previous = fade.alphaOf(0)
        while (clock.currentTime - start < KANA_FADE_MILLIS + 32) {
            clock.advanceTimeByFrame()
            val elapsed = clock.currentTime - start
            val alpha = fade.alphaOf(0)
            // 見せた時刻からの ease-out（どのフレームから始まるかの分、1フレーム前後してよい）
            val low = ease(elapsed - FRAME_MILLIS) - 0.01f
            val high = ease(elapsed + FRAME_MILLIS) + 0.01f
            assertTrue("${elapsed}ms: $alpha は $low..$high", alpha in low..high)
            assertTrue("${elapsed}ms: 薄くならない", alpha >= previous)
            previous = alpha
        }
        assertEquals(1f, fade.alphaOf(0))
    }

    /** 見せてから [elapsed] ms の不透明度（ease-out）。 */
    private fun ease(elapsed: Long): Float =
        KanaFadeEasing.transform((elapsed.toFloat() / KANA_FADE_MILLIS).coerceIn(0f, 1f))

    @Test
    fun kanaAlreadyShownWhenThePanelAppearsAreFullyShownAtOnce() {
        // 画面を開いた・回した・一時停止や裏から戻ったときに、もう見せている文字
        clock.autoAdvance = false
        showFade(5)
        val fade = fade!!
        for (i in 0 until 5) {
            assertTrue("$i", fade.isSettled(i))
            assertEquals("$i", 1f, fade.alphaOf(i))
        }
        assertEquals(0f, fade.alphaOf(5))
        assertFalse(fade.fading)
        clock.advanceTimeBy(500L)
        assertFalse(fade.fading)
    }

    @Test
    fun kanaShownTogetherFadeTogetherAndLaterOnesOverlap() {
        showFade(0)
        clock.autoAdvance = false
        // 2文字を一度に見せた: 同じ時刻から一緒に浮かび上がる
        reveal(2)
        val fade = fade!!
        assertEquals(fade.alphaOf(0), fade.alphaOf(1))
        clock.advanceTimeBy(80L)
        val earlier = fade.alphaOf(0)
        // 約 100ms 後に次の文字: 前の2文字は止まらずに濃くなり続け、新しい文字は透明から始まる
        reveal(3)
        assertTrue("${fade.alphaOf(2)}", fade.alphaOf(2) < 0.2f)
        assertTrue("${fade.alphaOf(0)} > $earlier", fade.alphaOf(0) > earlier)
        // 前の2文字は見せてから 200ms を過ぎて元の色。新しい文字はまだ途中
        clock.advanceTimeBy(128L)
        assertEquals(1f, fade.alphaOf(0))
        assertEquals(1f, fade.alphaOf(1))
        assertTrue("${fade.alphaOf(2)}", fade.alphaOf(2) in 0.5f..0.97f)
        clock.advanceTimeBy(160L)
        assertEquals(1f, fade.alphaOf(2))
        assertFalse(fade.fading)
    }

    @Test
    fun showingFewerKanaHidesThemAtOnceAndTheyFadeInAgain() {
        // 序歌の下の句の2回目: 下の句を初めから見せ直す（描くときは見せる数でその場で消える。ReadingFadeTest）
        showFade(10)
        clock.autoAdvance = false
        reveal(12)
        val fade = fade!!
        change(6) { fade.alphaOf(6) == 0f }
        assertEquals(0f, fade.alphaOf(11))
        assertEquals(0f, fade.alphaOf(6))
        assertEquals(1f, fade.alphaOf(5))
        assertFalse(fade.fading)
        reveal(7)
        assertTrue("${fade.alphaOf(6)}", fade.alphaOf(6) < 0.2f)
        clock.advanceTimeBy(240L)
        assertEquals(1f, fade.alphaOf(6))
    }

    @Test
    fun aRecreatedScreenShowsTheKanaAtOnce() {
        // 画面を回した（作り直した）: 浮かび上がっている最中だった文字も、すぐに元の色で見せる
        val tester = StateRestorationTester(composeRule)
        shown = 3
        tester.setContent { fade = rememberKanaFadeIn("verse", shown) }
        clock.autoAdvance = false
        reveal(4)
        assertTrue(fade!!.alphaOf(3) < 1f)
        // 作り直しにはフレームが要るので、時計は自動で進める
        clock.autoAdvance = true
        tester.emulateSavedInstanceStateRestore()
        val recreated = fade!!
        assertFalse(recreated.fading)
        assertEquals(1f, recreated.alphaOf(3))
    }
}

/** 端末の「アニメーションを削除」がオン（アニメーションの速さ 0）のときは、浮かび上がらせない。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class KanaFadeInWithoutAnimationsTest {
    @get:Rule
    val composeRule = createComposeRule(effectContext = NoMotion)

    @Test
    fun kanaAreShownAtOnce() {
        var shown by mutableIntStateOf(3)
        var fade: KanaFadeIn? = KanaFadeIn(0)
        composeRule.setContent { fade = rememberKanaFadeIn("verse", shown) }
        // 浮かび上がりを使わない（句は見せる文字の数のとおりにすぐ描く）
        assertNull(fade)
        shown = 4
        composeRule.waitForIdle()
        assertNull(fade)
    }

    private object NoMotion : MotionDurationScale {
        override val scaleFactor: Float = 0f
    }
}
