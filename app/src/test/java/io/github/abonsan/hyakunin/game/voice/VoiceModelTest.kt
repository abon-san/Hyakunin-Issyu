package io.github.abonsan.hyakunin.game.voice

import io.github.abonsan.hyakunin.data.Joka
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.ReadTiming
import io.github.abonsan.hyakunin.game.ReadingSpeed
import io.github.abonsan.hyakunin.game.ReadingStyle
import io.github.abonsan.hyakunin.game.SYNTHETIC_POEMS
import io.github.abonsan.hyakunin.game.kanaCount
import io.github.abonsan.hyakunin.game.testPoem
import io.github.abonsan.hyakunin.game.verseBoundaries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 音声のデータモデル、序歌、設定の既定値。 */
class VoiceModelTest {
    @Test
    fun voiceClipRequiresAscendingNonNegativeStartsWithinTheDuration() {
        VoiceClip("a.ogg", 1000L, intArrayOf(0, 100, 100, 900))
        VoiceClip("empty.ogg", 0L, intArrayOf())
        assertThrows(IllegalArgumentException::class.java) { VoiceClip("a.ogg", 1000L, intArrayOf(100, 50)) }
        assertThrows(IllegalArgumentException::class.java) { VoiceClip("a.ogg", 1000L, intArrayOf(-1, 50)) }
        assertThrows(IllegalArgumentException::class.java) { VoiceClip("a.ogg", 1000L, intArrayOf(100, 1001)) }
        assertThrows(IllegalArgumentException::class.java) { VoiceClip("a.ogg", -1L, intArrayOf()) }
    }

    @Test
    fun voiceClipsAreComparedByContent() {
        val a = VoiceClip("a.ogg", 1000L, intArrayOf(0, 100))
        assertEquals(a, VoiceClip("a.ogg", 1000L, intArrayOf(0, 100)))
        assertEquals(a.hashCode(), VoiceClip("a.ogg", 1000L, intArrayOf(0, 100)).hashCode())
        assertNotEquals(a, VoiceClip("a.ogg", 1000L, intArrayOf(0, 101)))
        assertNotEquals(a, VoiceClip("b.ogg", 1000L, intArrayOf(0, 100)))
    }

    @Test
    fun clipFitsWhenTheKanaCountMatches() {
        // 空白は数えない
        val clip = fakeClip("a.ogg", 5, firstMs = 0, stepMs = 100, tailMs = 100L)
        assertTrue(clip.fits("あいう　えお"))
        assertFalse(clip.fits("あいう　え"))

        val poem = SYNTHETIC_POEMS.first()
        assertTrue(fakePoemVoice(poem).fits(poem))
        assertFalse(fakePoemVoice(poem).fits(testPoem(1, "あい", "うえ")))
    }

    @Test
    fun fakeBankReturnsOnlyTheClipsItHolds() {
        val bank = fakeVoiceBank(only = setOf(1, 2))
        assertEquals(fakePoemVoice(SYNTHETIC_POEMS[0]), bank.poem(1))
        assertNull(bank.poem(3))
        assertEquals(17, bank.jokaKami!!.kanaStartMs.size)
        assertEquals(14, bank.jokaShimo!!.kanaStartMs.size)
        assertNull(fakeVoiceBank(joka = false).jokaKami)
        assertNull(fakeVoiceBank(joka = false).jokaShimo)
    }

    @Test
    fun jokaHoldsTheOpeningPoem() {
        assertEquals("なにわずに　さくやこのはな　ふゆごもり", Joka.kamiYomi)
        assertEquals("いまをはるべと　さくやこのはな", Joka.shimoYomi)
        assertEquals("難波津に　咲くやこの花　冬ごもり", Joka.kami)
        assertEquals("今を春べと　咲くやこの花", Joka.shimo)
        assertEquals(17, kanaCount(Joka.kamiYomi))
        assertEquals(14, kanaCount(Joka.shimoYomi))
        assertEquals(0, Joka.poem.no)
        assertEquals(Joka.kamiYomi, Joka.poem.kamiYomi)
        assertEquals(Joka.shimoYomi, Joka.poem.shimoYomi)
        // 歴史的仮名遣いの表示にも使える
        assertEquals(17 + 14 + 1, verseBoundaries(Joka.poem).size)
    }

    @Test
    fun playbackSpeedFollowsTheReadingSpeed() {
        assertEquals(0.85f, ReadingSpeed.SLOW.playbackSpeed)
        assertEquals(1.0f, ReadingSpeed.NORMAL.playbackSpeed)
        assertEquals(1.2f, ReadingSpeed.FAST.playbackSpeed)
        for (speed in ReadingSpeed.entries) {
            val timing = ReadTiming.of(speed)
            assertEquals(speed.playbackSpeed, timing.playbackSpeed)
            assertEquals(speed.charMillis, timing.charMillis)
        }
        val bank = fakeVoiceBank()
        assertEquals(bank, ReadTiming.of(ReadingSpeed.FAST, bank).voice)
    }

    @Test
    fun readTimingVoiceDefaults() {
        val t = ReadTiming(100L)
        assertNull(t.voice)
        assertEquals(1.0f, t.playbackSpeed)
        assertEquals(650L, t.voiceKamiShimoGapMillis)
        assertEquals(1000L, t.maMillis)
        assertEquals(3000L, t.yoinMillis)
        assertFalse(t.textLeadIn)
        // 文字だけの間は従来のまま
        assertEquals(300L, t.kamiShimoGapMillis)
        assertEquals(100L, t.phraseGapMillis)
        assertThrows(IllegalArgumentException::class.java) { ReadTiming(100L, playbackSpeed = 0f) }
    }

    @Test
    fun gameConfigVoiceDefaults() {
        val config = GameConfig()
        assertEquals(ReadingStyle.COMPETITIVE, config.readingStyle)
        assertTrue(config.voiceEnabled)
        assertEquals("競技かるた式", ReadingStyle.COMPETITIVE.label)
        assertEquals("簡易式", ReadingStyle.SIMPLE.label)
    }
}
