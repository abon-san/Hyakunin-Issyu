package io.github.abonsan.hyakunin.game

import io.github.abonsan.hyakunin.game.voice.FakeVoiceBank
import io.github.abonsan.hyakunin.game.voice.fakePoemVoice
import io.github.abonsan.hyakunin.game.voice.fakeVoiceBank
import io.github.abonsan.hyakunin.game.voice.PoemVoice
import io.github.abonsan.hyakunin.game.voice.VoiceClip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

/** 音声の時刻表から作る、仮名の表示時刻（kanaAt）と、音声がないときの文字だけへの戻り。 */
class VoiceTimelineTest {
    /** 前置きなしの簡易式で、音声で読むエンジン。読みはすぐ始まる。 */
    private fun voiceEngine(
        order: List<Int> = listOf(1, 2),
        timing: ReadTiming = voiceTiming(),
        profile: CpuProfile = SLOW_CPU,
        voiceEnabled: Boolean = true,
    ) = layoutEngine(
        order = order,
        timing = timing,
        profile = profile,
        readingStyle = ReadingStyle.SIMPLE,
        voiceEnabled = voiceEnabled,
    )

    private fun ReadSession.offsets(): List<Long> = kanaAt.map { it - startMillis }

    private val kamiOffsets = (0 until 16).map { 100L + 200 * it }
    private val shimoOffsets = (0 until 10).map { 4100L + 300 * it }

    @Test
    fun kanaAreRevealedAtTheClipTimes() {
        val e = voiceEngine()
        val s = e.beginRead()
        assertEquals(kamiOffsets + shimoOffsets, s.offsets())
        assertEquals(16, s.kamiCount)
        assertEquals(26, s.totalCount)
        // 上の句の最後の仮名、下の句の最後の仮名（余韻の前）
        assertEquals(3100L, s.kamiEnd - s.startMillis)
        assertEquals(6800L, s.shimoEnd - s.startMillis)
        // 下の句の余韻を含む、音声の終わり
        assertEquals(9800L, s.audioEnd - s.startMillis)
        assertEquals(0L, s.startMillis)
    }

    @Test
    fun revealedCountFollowsTheClipTimes() {
        val e = voiceEngine()
        val s = e.beginRead()

        fun revealedAt(offset: Long): Int {
            e.advanceToOffset(s, offset)
            return e.state.reading!!.revealedKana
        }
        assertEquals(0, revealedAt(99L))
        assertEquals(1, revealedAt(100L))
        assertEquals(1, revealedAt(299L))
        assertEquals(2, revealedAt(300L))
        assertEquals(16, revealedAt(3100L))
        // 上の句のクリップの終わり（3500）と下の句の間（500）のあいだは増えない
        assertEquals(16, revealedAt(4099L))
        assertEquals(17, revealedAt(4100L))
        assertEquals(24, revealedAt(6499L))
        assertEquals(25, revealedAt(6500L))
        assertEquals(25, revealedAt(6799L))
        assertEquals(26, revealedAt(6800L))
        assertEquals(16, e.state.reading!!.kamiKanaCount)
        assertEquals(26, e.state.reading!!.totalKanaCount)
    }

    @Test
    fun clipTimesAreDividedByThePlaybackSpeed() {
        // 2倍速: 上の句・下の句の間・下の句の位置がすべて半分
        val fast = voiceEngine(timing = voiceTiming(playbackSpeed = 2.0f)).beginRead()
        assertEquals(kamiOffsets.map { it / 2 } + shimoOffsets.map { (it - 4000L) / 2 + 2000L }, fast.offsets())
        assertEquals(1550L, fast.kamiEnd - fast.startMillis)
        assertEquals(3400L, fast.shimoEnd - fast.startMillis)
        assertEquals(4900L, fast.audioEnd - fast.startMillis)

        // 0.5倍速: 2倍
        val slow = voiceEngine(timing = voiceTiming(playbackSpeed = 0.5f)).beginRead()
        assertEquals(kamiOffsets.map { it * 2 } + shimoOffsets.map { (it - 4000L) * 2 + 8000L }, slow.offsets())
        assertEquals(19_600L, slow.audioEnd - slow.startMillis)
    }

    @Test
    fun readingSpeedMapsToThePlaybackSpeedOfTheVoice() {
        for (speed in ReadingSpeed.entries) {
            val timing = ReadTiming.of(speed, fakeVoiceBank())
            val s = layoutEngine(order = listOf(1), timing = timing, readingStyle = ReadingStyle.SIMPLE).beginRead()
            val v = speed.playbackSpeed.toDouble()
            fun scaled(ms: Long) = (ms / v).roundToLong()
            // 上の句は 100 + 200 i、下の句はクリップの先頭が (3500 + 650) / 速度、そこから 100 + 300 j
            val shimoStart = scaled(3500L + 650L)
            assertEquals("$speed", scaled(100L), s.kanaAt[0] - s.startMillis)
            assertEquals("$speed", scaled(100L + 200 * 15), s.kanaAt[15] - s.startMillis)
            assertEquals("$speed", shimoStart + scaled(100L), s.kanaAt[16] - s.startMillis)
            assertEquals("$speed", shimoStart + scaled(100L + 300 * 9), s.kanaAt[25] - s.startMillis)
            assertEquals("$speed", shimoStart + scaled(5800L), s.audioEnd - s.startMillis)
            // 文字だけの速さ（charMillis）は音声には効かない
            assertTrue("$speed", s.kanaAt[0] - s.startMillis != speed.charMillis)
        }
    }

    @Test
    fun theDefaultGapBetweenTheVersesIs650Millis() {
        // ReadTiming の既定（650ms）。下の句のクリップの先頭は 3500 + 650
        val timing = ReadTiming(100L, voice = fakeVoiceBank())
        val s = voiceEngine(order = listOf(1), timing = timing).beginRead()
        assertEquals(kamiOffsets, s.offsets().take(16))
        assertEquals(shimoOffsets.map { it + 150L }, s.offsets().drop(16))
        assertEquals(4150L, s.audio[1].startMillis - s.startMillis)
        assertEquals(9950L, s.audioEnd - s.startMillis)
    }

    @Test
    fun theGapBetweenTheVersesIsConfigurable() {
        val timing = voiceTiming().copy(voiceKamiShimoGapMillis = 1_000L)
        val s = voiceEngine(timing = timing).beginRead()
        assertEquals(shimoOffsets.map { it + 500L }, s.offsets().drop(16))
        assertEquals(kamiOffsets, s.offsets().take(16))
    }

    @Test
    fun aPoemWithoutVoiceFallsBackToTheTextTimeline() {
        // 1 番だけ音声がある。2 番は文字だけ（従来の時刻表）
        val bank = fakeVoiceBank(only = setOf(1))
        val e = voiceEngine(order = listOf(1, 2), timing = voiceTiming(voice = bank))
        e.beginRead()
        e.tapCard(Side.PLAYER, 1)
        e.next()
        val second = e.beginRead()

        val reference = layoutEngine(order = listOf(2), readingStyle = ReadingStyle.SIMPLE).beginRead()
        assertEquals(reference.offsets(), second.offsets())
        assertEquals(reference.kamiCount, second.kamiCount)
        assertTrue(second.audio.isEmpty())
        assertNull(e.state.audio)
        // 文字だけの時刻表では上の句の最初の仮名は 100ms（charMillis）
        assertEquals(100L, second.kanaAt[0] - second.startMillis)
    }

    @Test
    fun aClipWhoseKanaCountDoesNotMatchTheReadingIsNotUsed() {
        val poem = SYNTHETIC_POEMS[0]
        val good = fakePoemVoice(poem)
        // 上の句の時刻表が 1 つ足りない
        val broken = PoemVoice(
            kami = VoiceClip(good.kami.asset, good.kami.durationMs, good.kami.kanaStartMs.copyOf(15)),
            shimo = good.shimo,
        )
        val bank = FakeVoiceBank(poems = mapOf(1 to broken))
        val s = voiceEngine(order = listOf(1), timing = voiceTiming(voice = bank)).beginRead()

        val reference = layoutEngine(order = listOf(1), readingStyle = ReadingStyle.SIMPLE).beginRead()
        assertEquals(reference.offsets(), s.offsets())
        assertTrue(s.audio.isEmpty())
    }

    @Test
    fun disablingTheVoiceGivesTheTextTimelineBitForBit() {
        val reference = layoutEngine(order = listOf(1), readingStyle = ReadingStyle.SIMPLE).beginRead()
        val off = voiceEngine(order = listOf(1), voiceEnabled = false)
        val s = off.beginRead()
        assertEquals(reference.offsets(), s.offsets())
        assertEquals(reference.kamiCount, s.kamiCount)
        assertNull(off.state.audio)

        // 音声を渡さない場合も同じ
        val none = voiceEngine(order = listOf(1), timing = voiceTiming(voice = null)).beginRead()
        assertEquals(reference.offsets(), none.offsets())
    }

    @Test
    fun textOnlyKeepsTheClassicTimelineAndStartsAfterTheWait() {
        // 音声なし・既定のタイミングでは、競技かるた式の設定でも前置きなしで、待ちが終われば読みが始まる
        val e = layoutEngine(order = listOf(1))
        e.advanceTo(9_999L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        assertNull(e.state.leadIn)
        e.advanceTo(10_000L)
        val st = e.state
        assertEquals(MatchPhase.Reading, st.phase)
        assertNull(st.leadIn)
        assertNull(st.audio)
        assertEquals(10_000L, st.engineTimeMillis)
        val s = e.session!!
        assertEquals(
            listOf(
                100L, 200L, 300L, 400L, 500L, 600L, 800L, 900L, 1000L, 1100L, 1200L, 1400L, 1500L, 1600L, 1700L, 1800L,
                2200L, 2300L, 2400L, 2500L, 2600L, 2800L, 2900L, 3000L, 3100L, 3200L,
            ),
            s.offsets(),
        )
        assertEquals(3200L, s.audioEnd - s.startMillis)
    }
}
