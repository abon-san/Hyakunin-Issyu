package io.github.abonsan.hyakunin.audio

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import io.github.abonsan.hyakunin.BuildConfig
import io.github.abonsan.hyakunin.game.AudioCue
import io.github.abonsan.hyakunin.game.NextAudio

/**
 * Media3 ExoPlayer で読み上げ音声（アセットの Ogg）を鳴らす [VoicePlayer]。追従の決まりは [VoiceCueFollower]。
 * ExoPlayer を2つ（[DECK_COUNT]）持ち、片方で鳴らしている間にもう片方へ次のクリップを読み込んでおく。
 * ExoPlayer は [warmUp] か最初の読み込みで作り、使い回す。メインスレッドからだけ呼ぶこと。
 *
 * 音声フォーカスは自分で扱う（[VoiceAudioFocus]）。フォーカスを失ったとき・得られないとき・イヤホンが外れたときは
 * 音を止めて [onInterrupted] を呼ぶ（呼び出し側が対戦を一時停止する）。
 *
 * 音の大きさは2か所で決める。利用者の音量（[setVolume]、設定の「音量」、0〜2 倍）は PCM に掛け（[VoiceGainProcessor]）、
 * 小さくして止めるときの音量とほかの音の間の音量は ExoPlayer の音量（1 以下、[OutputGain]）にする。
 * どちらも値が変わったときだけ渡す（ふつうに鳴っている間は渡さない）。
 *
 * デバッグ版では、クリップごとに読み込みの時間・鳴らし始めの遅れ・位置を移した回数・ずれの最大・アンダーランの回数を
 * logcat（タグ [TAG]）に出す。
 */
@OptIn(UnstableApi::class)
class ExoVoicePlayer(context: Context, private val onInterrupted: () -> Unit) : VoicePlayer {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val focus = VoiceAudioFocus(
        audioManager = appContext.getSystemService(AudioManager::class.java),
        onLoss = ::interrupt,
        onDuck = { ducked ->
            val gain = if (ducked) VOICE_DUCK_GAIN else 1f
            decks.forEach { it.duck(gain) }
        },
    )

    private var released = false

    private val decks = List(DECK_COUNT) { Deck(it) }
    private val follower = VoiceCueFollower(decks, wallClock = SystemClock::uptimeMillis, onClipFinished = ::logClip)

    override fun update(cue: AudioCue?, engineTimeMillis: Long, paused: Boolean, next: NextAudio?) {
        if (!released) follower.update(cue, engineTimeMillis, paused, next)
    }

    override fun heardPositionMillis(cueId: Long): Long? = if (released) null else follower.heardPositionMillis(cueId)

    override fun warmUp() {
        if (!released) decks.forEach { it.warmUp() }
    }

    override fun setVolume(volume: Float) {
        if (!released) follower.setVolume(volume)
    }

    /** 再生器ごとの、ExoPlayer に渡してある音量（まだ作っていなければ null）。 */
    @VisibleForTesting
    internal val outputVolumes: List<Float?> get() = decks.map { it.exoVolume }

    /** 再生器ごとの、PCM に掛ける利用者の音量。 */
    @VisibleForTesting
    internal val gains: List<Float> get() = decks.map { it.gain }

    override fun release() {
        if (released) return
        released = true
        follower.reset()
        decks.forEach { it.release() }
        focus.abandon()
    }

    /** 外から音を止められた。音をすぐ止めて、呼び出し側に対戦を止めてもらう。 */
    private fun interrupt() {
        decks.forEach { it.pauseNow() }
        onInterrupted()
    }

    private fun logClip(report: VoiceClipReport) {
        if (BuildConfig.DEBUG) Log.d(TAG, report.toLogLine())
    }

    /** ExoPlayer 1つ。読み込み・鳴らす・止めるを [VoiceCueFollower] が命令する。 */
    private inner class Deck(private val index: Int) : VoicePlayerPort {
        private var exo: ExoPlayer? = null
        private var loadedAsset: String? = null
        private var failed = false

        private var fade: Runnable? = null

        /** 利用者の音量を PCM に掛ける段。この再生器の ExoPlayer の DefaultAudioSink に入れる。 */
        private val gainStage = VoiceGainProcessor()

        /**
         * ExoPlayer に渡す音量（小さくして止めるときの音量 × ほかの音の間の音量）。変わるときだけ渡す
         * （鳴っている間は、小さくして止めるとき・ほかの音の間しか変えない）。
         */
        private val output = OutputGain { exo?.volume = it }

        /** 読み込みを頼んだ時刻（実時間）。読み込みが終わったら null。 */
        private var prepareStartedAt: Long? = null

        override var prepareMillis: Long? = null
            private set

        override var underrunCount: Int = 0
            private set

        private val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // このクリップは鳴らさない（表示は文字のまま進む）。次のクリップでまた読み込む
                Log.w(TAG, "音声を鳴らせない: $loadedAsset", error)
                failed = true
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // イヤホンが外れて ExoPlayer が止めた
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) interrupt()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState != Player.STATE_READY) return
                prepareStartedAt?.let { prepareMillis = SystemClock.uptimeMillis() - it }
                prepareStartedAt = null
            }
        }

        private val analytics = object : AnalyticsListener {
            override fun onAudioUnderrun(
                eventTime: AnalyticsListener.EventTime,
                bufferSize: Int,
                bufferSizeMs: Long,
                elapsedSinceLastFeedMs: Long,
            ) {
                underrunCount++
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "underrun deck=$index clip=${loadedAsset?.substringAfterLast('/')} " +
                            "buffer=${bufferSizeMs}ms sinceLastFeed=${elapsedSinceLastFeedMs}ms",
                    )
                }
            }
        }

        override fun prepare(asset: String) {
            cancelFade()
            val p = player()
            p.playWhenReady = false
            prepareStartedAt = SystemClock.uptimeMillis()
            prepareMillis = null
            if (asset == loadedAsset && !failed && p.playbackState != Player.STATE_IDLE) {
                // 同じクリップ（序歌の下の句の2回目など）は読み直さずに先頭へ戻す
                p.seekTo(0L)
            } else {
                p.setMediaItem(voiceMediaItem(asset))
                p.prepare()
                loadedAsset = asset
                failed = false
            }
        }

        override fun play() {
            val p = exo ?: return
            if (!focus.request()) {
                // 電話中などで鳴らせない。対戦を止めて表示と音声をそろえる
                interrupt()
                return
            }
            p.play()
        }

        override fun pause() {
            exo?.pause()
            focus.abandon()
        }

        override fun seekTo(positionMillis: Long) {
            exo?.seekTo(positionMillis.coerceAtLeast(0L))
        }

        override fun setSpeed(speed: Float) {
            // 速さだけを変える（音の高さは 1 倍のまま）
            player().playbackParameters = PlaybackParameters(speed)
        }

        override fun setVolume(volume: Float) {
            gainStage.gain = volume
        }

        override fun stop(fadeOutMillis: Long) {
            val p = exo ?: return
            cancelFade()
            if (!p.isPlaying || fadeOutMillis <= 0L) {
                p.stop()
                return
            }
            val start = SystemClock.uptimeMillis()
            val step = object : Runnable {
                override fun run() {
                    output.fade = fadeOutGain(SystemClock.uptimeMillis() - start, fadeOutMillis)
                    if (output.fade > 0f) {
                        handler.postDelayed(this, FADE_STEP_MILLIS)
                    } else {
                        fade = null
                        exo?.stop()
                        output.fade = 1f
                    }
                }
            }
            fade = step
            step.run()
        }

        override val isReady: Boolean get() = exo?.playbackState == Player.STATE_READY

        override val isPlaying: Boolean get() = exo?.isPlaying == true

        override val positionMillis: Long get() = exo?.currentPosition ?: 0L

        val exoVolume: Float? get() = exo?.volume

        val gain: Float get() = gainStage.gain

        fun warmUp() {
            player()
        }

        /** 外から止められたときに、すぐ止める。 */
        fun pauseNow() {
            exo?.pause()
        }

        fun release() {
            cancelFade()
            exo?.release()
            exo = null
        }

        /** ほかのアプリの短い音の間の音量にする（戻すときは 1）。 */
        fun duck(gain: Float) {
            output.duck = gain
        }

        private fun cancelFade() {
            fade?.let(handler::removeCallbacks)
            fade = null
            output.fade = 1f
        }

        private fun player(): ExoPlayer = exo ?: buildVoiceExoPlayer(appContext, gainStage).also {
            it.addListener(listener)
            it.addAnalyticsListener(analytics)
            exo = it
            // 作ったばかりの ExoPlayer の音量は 1。ほかの音の間の音量などを渡し直す（利用者の音量は gainStage が持つ）
            output.reattach()
        }
    }

    companion object {
        /** 読み上げ音声の logcat のタグ（エラーと、デバッグ版の診断）。 */
        const val TAG = "HyakuninVoice"

        /** ExoPlayer の数。1つで鳴らし、もう1つに次のクリップを読み込んでおく。 */
        private const val DECK_COUNT = 2

        /** 音を小さくしていく刻み（ms）。 */
        private const val FADE_STEP_MILLIS = 16L
    }
}

/** 止めるときの音量。[fadeMillis] かけて 1 から 0 へまっすぐ下げる。 */
internal fun fadeOutGain(elapsedMillis: Long, fadeMillis: Long): Float =
    if (fadeMillis <= 0L) 0f else (1f - elapsedMillis.toFloat() / fadeMillis).coerceIn(0f, 1f)
