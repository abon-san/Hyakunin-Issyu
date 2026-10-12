package io.github.abonsan.hyakunin.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 読み上げ音声の音声フォーカス（VoiceAudioFocus）。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class VoiceAudioFocusTest {
    private val audioManager = ApplicationProvider.getApplicationContext<Context>().getSystemService(AudioManager::class.java)
    private var losses = 0
    private val ducks = mutableListOf<Boolean>()
    private val focus = VoiceAudioFocus(audioManager, onLoss = { losses++ }, onDuck = { ducks += it })

    private fun changeFocus(change: Int) =
        shadowOf(audioManager).lastAudioFocusRequest.listener.onAudioFocusChange(change)

    @Test
    fun theFocusIsRequestedAsGameSpeech() {
        assertTrue(focus.request())
        assertTrue(focus.held)
        val request = shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest
        assertEquals(AudioManager.AUDIOFOCUS_GAIN, request.focusGain)
        assertEquals(AudioAttributes.USAGE_GAME, request.audioAttributes.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, request.audioAttributes.contentType)
        // 持っている間は求め直さない
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertTrue(focus.request())
    }

    @Test
    fun theFocusCanBeRefusedDuringACall() {
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        assertFalse(focus.request())
        assertFalse(focus.held)
    }

    @Test
    fun abandoningGivesTheFocusBack() {
        focus.abandon()
        assertNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)
        focus.request()
        val request = shadowOf(audioManager).lastAudioFocusRequest.audioFocusRequest
        focus.abandon()
        assertFalse(focus.held)
        assertSame(request, shadowOf(audioManager).lastAbandonedAudioFocusRequest)
    }

    @Test
    fun losingTheFocusAsksToPauseTheMatch() {
        focus.request()
        changeFocus(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, losses)
        assertFalse(focus.held)

        // 一時的に取られたとき（電話など）も止める
        focus.request()
        changeFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT)
        assertEquals(2, losses)
        assertFalse(focus.held)
    }

    @Test
    fun shortSoundsOfOtherAppsOnlyLowerTheVolume() {
        focus.request()
        changeFocus(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)
        changeFocus(AudioManager.AUDIOFOCUS_GAIN)
        assertEquals(listOf(true, false), ducks)
        assertEquals(0, losses)
        assertTrue(focus.held)
    }

    @Test
    fun theFadeOutGoesLinearlyToSilence() {
        assertEquals(1f, fadeOutGain(0L, 150L))
        assertEquals(0.5f, fadeOutGain(75L, 150L))
        assertEquals(0f, fadeOutGain(150L, 150L))
        assertEquals(0f, fadeOutGain(400L, 150L))
        assertEquals(0f, fadeOutGain(0L, 0L))
    }
}
