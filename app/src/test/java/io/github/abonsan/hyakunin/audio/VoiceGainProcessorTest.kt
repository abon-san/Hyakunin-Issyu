package io.github.abonsan.hyakunin.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** 利用者の音量を PCM に掛ける段（VoiceGainProcessor）: 16 ビットと浮動小数、鳴っている最中の寄せ方、範囲に収める。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class VoiceGainProcessorTest {
    /** 読み上げ音声と同じ 24kHz の1チャンネル。 */
    private val pcm16 = AudioFormat(24_000, 1, C.ENCODING_PCM_16BIT)
    private val pcmFloat = AudioFormat(24_000, 1, C.ENCODING_PCM_FLOAT)

    /** 寄せるのにかけるフレーム数（50ms）。 */
    private val rampFrames = 24_000 * VoiceGainProcessor.RAMP_MILLIS / 1000

    private fun processor(gain: Float, format: AudioFormat = pcm16) = VoiceGainProcessor(gain).apply {
        assertEquals(format, configure(format))
        flush(AudioProcessor.StreamMetadata.DEFAULT)
    }

    private fun AudioProcessor.feedShorts(vararg samples: Int): ShortArray {
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        for (s in samples) input.putShort(s.toShort())
        input.flip()
        queueInput(input)
        assertFalse(input.hasRemaining())
        val output = getOutput()
        return ShortArray(output.remaining() / 2) { output.getShort() }
    }

    private fun AudioProcessor.feedFloats(vararg samples: Float): FloatArray {
        val input = ByteBuffer.allocateDirect(samples.size * 4).order(ByteOrder.nativeOrder())
        for (s in samples) input.putFloat(s)
        input.flip()
        queueInput(input)
        val output = getOutput()
        return FloatArray(output.remaining() / 4) { output.getFloat() }
    }

    /** 同じ値 [level] が続く音を [frames] フレーム通し、フレームごとに掛かった倍率を返す（浮動小数）。 */
    private fun AudioProcessor.gainsOver(frames: Int, level: Float = 0.25f): List<Float> =
        feedFloats(*FloatArray(frames) { level }).map { it / level }

    @Test
    fun pcm16SamplesAreScaledAndRounded() {
        assertArrayEquals(shortArrayOf(2000, -4000, 6, 0, -32768), processor(2f).feedShorts(1000, -2000, 3, 0, -16384))
        assertArrayEquals(shortArrayOf(500, -1000, 2, 16384), processor(0.5f).feedShorts(1000, -2000, 3, 32767))
        // 100% はそのまま
        assertArrayEquals(shortArrayOf(1, -2, 32767, -32768), processor(1f).feedShorts(1, -2, 32767, -32768))
        // 0% は無音
        assertArrayEquals(shortArrayOf(0, 0), processor(0f).feedShorts(12345, -32768))
    }

    @Test
    fun floatSamplesAreScaled() {
        val out = processor(2f, pcmFloat).feedFloats(0.1f, -0.25f, 0.4f)
        assertArrayEquals(floatArrayOf(0.2f, -0.5f, 0.8f), out, 1e-6f)
        assertArrayEquals(floatArrayOf(0.05f, -0.125f), processor(0.5f, pcmFloat).feedFloats(0.1f, -0.25f), 1e-6f)
    }

    @Test
    fun peaksBeyondTheRangeAreClampedNotWrapped() {
        // 16 ビットで 2 倍すると表せない値は、端に収める（折り返さない）
        assertArrayEquals(
            shortArrayOf(32767, -32768, 32767, -32768),
            processor(2f).feedShorts(20000, -20000, 32767, -32768),
        )
        assertArrayEquals(floatArrayOf(1f, -1f), processor(2f, pcmFloat).feedFloats(0.6f, -0.9f), 0f)
    }

    @Test
    fun theGainIsKeptInRangeAndNaNIsIgnored() {
        val p = VoiceGainProcessor()
        assertEquals(1f, p.gain)
        p.gain = 3f
        assertEquals(2f, p.gain)
        p.gain = -0.5f
        assertEquals(0f, p.gain)
        p.gain = 1.5f
        p.gain = Float.NaN
        assertEquals(1.5f, p.gain)
        assertEquals(2f, VoiceGainProcessor(Float.POSITIVE_INFINITY).gain)
        assertEquals(1f, VoiceGainProcessor(Float.NaN).gain)
    }

    @Test
    fun aStreamStartsAtTheGainWithoutARamp() {
        // 鳴らす前に変えた音量は、鳴り始めからそのまま掛かる（大きい音から寄せていかない）
        val p = processor(1f, pcmFloat)
        p.gain = 1.8f
        assertEquals(List(10) { 1.8f }, p.gainsOver(10).map { it.round() })
        // 位置を移した・読み込み直した（流し直した）後も、そのときの音量で始める
        p.gain = 0.4f
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertEquals(List(10) { 0.4f }, p.gainsOver(10).map { it.round() })
    }

    @Test
    fun aChangeWhilePlayingRampsLinearlyOverFiftyMillis() {
        val p = processor(1f, pcmFloat)
        p.gainsOver(100)
        p.gain = 2f
        val gains = p.gainsOver(rampFrames + 100)
        // 1 フレームごとに少しずつ上がり、50ms（1200 フレーム）で 2 倍に着く。その後は 2 倍のまま
        assertEquals(1f + 1f / rampFrames, gains.first(), 1e-5f)
        assertEquals(1.5f, gains[rampFrames / 2 - 1], 1e-4f)
        assertEquals(2f, gains[rampFrames - 1], 0f)
        assertTrue(gains.drop(rampFrames).all { it == 2f })
        assertTrue(gains.zipWithNext().all { (a, b) -> b >= a })
        // 下げるときも同じ
        p.gain = 0.5f
        val down = p.gainsOver(rampFrames)
        assertEquals(2f - 1.5f / rampFrames, down.first(), 1e-5f)
        assertEquals(0.5f, down.last(), 0f)
    }

    @Test
    fun aSliderDragDuringThePreviewStaysSmooth() {
        // 「試聴」の最中にスライダーを 100% から 200% まで 5% ずつ動かす（画面の更新ごと、16ms 間隔）。音は 10ms ずつ通る
        val p = processor(1f, pcmFloat)
        val gains = mutableListOf<Float>()
        var t = 0
        var step = 0
        while (t < 1_500) {
            if (t % 16 < 10 && step < 20) p.gain = 1f + 0.05f * ++step
            gains += p.gainsOver(240)
            t += 10
        }
        // フレームごとの変わり方は、いつも 2 倍の差を 50ms かけて寄せるより緩やか（段差がない）
        val maxStep = gains.zipWithNext().maxOf { (a, b) -> abs(b - a) }
        assertTrue("maxStep=$maxStep", maxStep <= 2f / rampFrames + 1e-5f)
        assertEquals(2f, gains.last(), 1e-6f)
        assertTrue(gains.zipWithNext().all { (a, b) -> b >= a - 1e-6f })
    }

    @Test
    fun theSameGainAppliesToEveryChannelOfAFrame() {
        val stereo = AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)
        val p = processor(1f, stereo)
        p.feedShorts(*IntArray(200) { 1000 })
        p.gain = 2f
        val out = p.feedShorts(*IntArray(200) { 1000 })
        for (frame in 0 until 100) assertEquals("frame=$frame", out[frame * 2], out[frame * 2 + 1])
        // 48kHz では 50ms が 2400 フレーム。100 フレーム目で 1 + 100/2400 倍
        assertEquals(1042, out[198].toInt())
    }

    @Test
    fun otherEncodingsAreLeftAlone() {
        // 16 ビット・浮動小数のほかは働かない（DefaultAudioSink はこの段を飛ばす）
        val p = VoiceGainProcessor(2f)
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(24_000, 1, C.ENCODING_PCM_24BIT)))
        assertFalse(p.isActive)
        assertEquals(pcm16, p.configure(pcm16))
        assertTrue(p.isActive)
    }

    @Test
    fun resettingKeepsTheGain() {
        val p = processor(1.5f)
        p.reset()
        assertEquals(1.5f, p.gain)
        assertEquals(pcm16, p.configure(pcm16))
        p.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertArrayEquals(shortArrayOf(1500), p.feedShorts(1000))
    }

    private fun Float.round(): Float = Math.round(this * 1e4f) / 1e4f
}
