package io.github.abonsan.hyakunin.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.audio.AudioSink
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAudioTrack
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 再生器の出力先（voiceAudioSink、Media3 の DefaultAudioSink）を Robolectric の AudioTrack で動かし、
 * 利用者の音量が PCM に掛かってから AudioTrack に書かれることを確かめる。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class VoiceAudioSinkTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val written = ByteArrayOutputStream()
    private val listener = ShadowAudioTrack.OnAudioDataWrittenListener { _, data, _ -> written.write(data) }

    /** 読み上げ音声と同じ、24kHz の1チャンネルの 16 ビット PCM。 */
    private val format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW)
        .setPcmEncoding(C.ENCODING_PCM_16BIT)
        .setChannelCount(1)
        .setSampleRate(24_000)
        .build()

    init {
        ShadowAudioTrack.addAudioDataListener(listener)
    }

    @After
    fun tearDown() = ShadowAudioTrack.removeAudioDataListener(listener)

    /** DefaultAudioSink が流し始めに自分で音を立ち上げる長さ（20ms）のフレーム数。ここは比べない。 */
    private val sinkRampFrames = 24_000 * 20 / 1000

    /** [sink] に [samples] を渡し、AudioTrack に書かれた値を返す（流し始めの立ち上げの分は除く）。 */
    private fun play(sink: AudioSink, samples: ShortArray): List<Int> {
        sink.configure(AudioSink.AudioSinkConfig.Builder(format).build())
        sink.play()
        val input = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) input.putShort(s)
        input.flip()
        var tries = 0
        while (!sink.handleBuffer(input, 0L, 1) && tries++ < 100) Unit
        val bytes = ByteBuffer.wrap(written.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        val out = List(bytes.remaining() / 2) { bytes.getShort().toInt() }
        assertTrue("書かれた音が足りない: ${out.size}", out.size >= samples.size)
        return out.subList(sinkRampFrames, samples.size)
    }

    @Test
    fun theUserVolumeIsAppliedToThePcmBeforeTheAudioTrack() {
        val samples = ShortArray(2_400) { (if (it % 2 == 0) 6_000 else -9_000).toShort() }
        val out = play(voiceAudioSink(context, VoiceGainProcessor(2f)), samples)
        assertEquals(samples.drop(sinkRampFrames).map { it * 2 }, out)
    }

    @Test
    fun theShortPreviewBufferStillPassesThePcmThrough() {
        val samples = ShortArray(2_400) { 3_000 }
        val out = play(voiceAudioSink(context, VoiceGainProcessor(0.5f), audioBufferMillis = 150), samples)
        assertTrue(out.all { it == 1_500 })
    }
}
