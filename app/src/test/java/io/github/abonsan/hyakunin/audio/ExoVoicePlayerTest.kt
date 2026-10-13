package io.github.abonsan.hyakunin.audio

import android.content.Context
import android.media.AudioManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.AudioCue
import io.github.abonsan.hyakunin.game.voice.AssetVoiceBank
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Media3 ExoPlayer を使う再生器を、Robolectric の上でアセットの本物のクリップで動かす。音は出ないので、
 * 読み込み・再生・一時停止・停止・手放す流れが落ちないことと、音声フォーカスの扱いを確かめる。鳴り方は実機で確かめる。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class ExoVoicePlayerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var interruptions = 0
    private val player = ExoVoicePlayer(context) { interruptions++ }

    /** 1番の上の句。エンジンの時刻 0 から鳴る。 */
    private val kami = AudioCue("${AssetVoiceBank.DIR}/001_kami.ogg", startedAtMillis = 0L, playbackSpeed = 1f, cueId = 1L)

    @After
    fun tearDown() = player.release()

    /**
     * ExoPlayer が読み込みを終えて鳴らし始める（音声フォーカスを求める）まで、エンジンの時刻を進めながら状態を渡す。
     * ExoPlayer は自分のスレッドで読み込むので、実時間でも待つ（遅いマシンでも 20 秒まで）。戻り値はそのときのエンジンの時刻。
     */
    private fun playUntilStarted(cue: AudioCue): Long {
        var t = 0L
        val deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos()
        while (shadowOf(audioManager).lastAudioFocusRequest == null && interruptions == 0 && System.nanoTime() < deadline) {
            player.update(cue, t, paused = false)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            Thread.sleep(10)
            t += 100L
        }
        return t
    }

    @Test
    fun aClipIsLoadedPlayedPausedAndStopped() {
        player.warmUp()
        val t = playUntilStarted(kami)
        val focus = shadowOf(audioManager).lastAudioFocusRequest
        assertNotNull("20 秒たっても鳴らし始めない", focus)
        // 一時停止したら音声フォーカスを返し、再開したら求め直す
        player.update(kami, t, paused = true)
        assertSame(focus.audioFocusRequest, shadowOf(audioManager).lastAbandonedAudioFocusRequest)
        player.update(kami, t, paused = false)
        // 音声がなくなったら止める
        player.update(null, t + 100L, paused = false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300))
        player.release()
        // 手放した後は何もしない
        player.update(kami, t + 400L, paused = false)
        assertEquals(0, interruptions)
    }

    @Test
    fun theUserVolumeReachesBothPlayersAndSurvivesTheFadeOut() {
        // 100% なら ExoPlayer の音量は初めの 1 のまま
        player.warmUp()
        assertEquals(listOf(1f, 1f), player.outputVolumes)
        // 対戦の初めに渡した音量は、どちらの ExoPlayer にも渡る
        player.setVolume(0.4f)
        assertEquals(listOf(0.4f, 0.4f), player.outputVolumes)
        val t = playUntilStarted(kami)
        assertNotNull(shadowOf(audioManager).lastAudioFocusRequest)
        assertEquals(0.4f, player.outputVolumes[0])
        // 音声がなくなると小さくしてから止め、止め終えたら利用者の音量に戻す（次のクリップもその音量で鳴る）
        player.update(null, t + 100L, paused = false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400))
        assertEquals(listOf(0.4f, 0.4f), player.outputVolumes)
    }

    @Test
    fun aVolumeSetBeforeThePlayersExistIsAppliedWhenTheyAreMade() {
        player.setVolume(0.25f)
        assertEquals(listOf(null, null), player.outputVolumes)
        player.warmUp()
        assertEquals(listOf(0.25f, 0.25f), player.outputVolumes)
    }

    @Test
    fun losingTheAudioFocusInterruptsTheMatch() {
        playUntilStarted(kami)
        val focus = shadowOf(audioManager).lastAudioFocusRequest
        assertNotNull(focus)
        focus.listener.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS)
        assertEquals(1, interruptions)
    }

    @Test
    fun aRefusedAudioFocusInterruptsInsteadOfPlaying() {
        shadowOf(audioManager).setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED)
        playUntilStarted(kami)
        assertEquals(1, interruptions)
        assertNull(shadowOf(audioManager).lastAbandonedAudioFocusRequest)
    }
}
