package io.github.abonsan.hyakunin.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.voice.AssetVoiceBank
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * 設定の画面の「試聴」の再生器（ExoVoicePreview）を、Robolectric の上でアセットの本物のクリップで動かす。音は出ないので、
 * 音声フォーカスの扱い（対戦と同じ）と、鳴らす・止める・音量を変える・手放す流れを確かめる。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ExoVoicePreviewTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var stops = 0
    private val preview = ExoVoicePreview(context) { stops++ }

    /** 序歌の上の句（「試聴」で鳴らすクリップ）。 */
    private val joka = "${AssetVoiceBank.DIR}/joka_kami.ogg"

    @After
    fun tearDown() = preview.release()

    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    @Test
    fun playingAsksForTheFocusAsGameSpeechAndStoppingGivesItBack() {
        assertTrue(preview.play(joka, 0.5f))
        val request = shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, request.focusGain)
        assertEquals(AudioAttributes.USAGE_GAME, request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, request.audioAttributes.contentType)
        // 選んだ音量で鳴らす（PCM に掛ける。ExoPlayer の音量は 1 のまま）
        assertEquals(0.5f, preview.gain)
        assertEquals(1f, preview.outputVolume)
        idle()
        preview.stop()
        assertSame(request, shadowOf(audioManager).lastAbandonedAudioFocusRequest)
        // 自分で止めたときは知らせない
        assertEquals(0, stops)
    }

    @Test
    fun aLiveChangeGoesToThePcmGainWithoutTouchingThePlayer() {
        assertTrue(preview.play(joka, 0.5f))
        idle()
        // 鳴っている最中に 200% にする: PCM の音量だけが変わり（少しずつ寄せる）、ExoPlayer の音量は 1 のまま。止まらない
        preview.setVolume(2f)
        assertEquals(2f, preview.gain)
        assertEquals(1f, preview.outputVolume)
        idle()
        preview.setVolume(1.25f)
        assertEquals(1.25f, preview.gain)
        assertEquals(1f, preview.outputVolume)
        assertEquals(0, stops)
        // 止めてからもう一度鳴らすと、そのとき渡した音量で（読み直さずに）先頭から鳴る
        preview.stop()
        assertTrue(preview.play(joka, 0.6f))
        assertEquals(0.6f, preview.gain)
        assertEquals(1f, preview.outputVolume)
    }

    @Test
    fun losingTheFocusStopsThePreviewAndTellsTheScreen() {
        assertTrue(preview.play(joka, 1f))
        val focus = shadowOf(audioManager).lastAudioFocusRequest
        focus.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, stops)
        // 止まった後に止めても、もう知らせない
        preview.stop()
        assertEquals(1, stops)
    }

    @Test
    fun otherAppsShortSoundsOnlyLowerTheVolume() {
        assertTrue(preview.play(joka, 2f))
        val focus = shadowOf(audioManager).lastAudioFocusRequest
        // ほかの音の間は ExoPlayer の音量で下げる（PCM の 200% はそのまま）
        focus.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        assertEquals(VOICE_DUCK_GAIN, preview.outputVolume!!, 1e-6f)
        assertEquals(2f, preview.gain)
        focus.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(1f, preview.outputVolume)
        assertEquals(0, stops)
    }

    @Test
    fun aRefusedFocusDoesNotPlay() {
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertFalse(preview.play(joka, 1f))
        // ExoPlayer も作らない
        assertNull(preview.outputVolume)
        assertEquals(0, stops)
    }

    @Test
    fun nothingPlaysAfterRelease() {
        assertTrue(preview.play(joka, 1f))
        assertNotNull(preview.outputVolume)
        val request = shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest
        preview.release()
        assertSame(request, shadowOf(audioManager).lastAbandonedAudioFocusRequest)
        assertNull(preview.outputVolume)
        assertFalse(preview.play(joka, 1f))
    }
}
