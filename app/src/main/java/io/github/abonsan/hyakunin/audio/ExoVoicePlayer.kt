package io.github.abonsan.hyakunin.audio

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.AssetDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ogg.OggExtractor
import io.github.abonsan.hyakunin.game.AudioCue

/**
 * Media3 ExoPlayer で読み上げ音声（アセットの Ogg）を鳴らす [VoicePlayer]。追従の決まりは [VoiceCueFollower]。
 * ExoPlayer は [warmUp] か最初のクリップの読み込みで作り、1つを使い回す。メインスレッドからだけ呼ぶこと。
 *
 * 音声フォーカスは自分で扱う（[VoiceAudioFocus]）。フォーカスを失ったとき・得られないとき・イヤホンが外れたときは
 * 音を止めて [onInterrupted] を呼ぶ（呼び出し側が対戦を一時停止する）。
 */
@OptIn(UnstableApi::class)
class ExoVoicePlayer(context: Context, private val onInterrupted: () -> Unit) : VoicePlayer {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val focus = VoiceAudioFocus(
        audioManager = appContext.getSystemService(AudioManager::class.java),
        onLoss = ::interrupt,
        onDuck = { ducked ->
            duckGain = if (ducked) DUCK_GAIN else 1f
            applyVolume()
        },
    )

    private var exo: ExoPlayer? = null
    private var loadedAsset: String? = null
    private var failed = false
    private var released = false

    /** 止めるときに小さくしていく音量（1 から 0）。 */
    private var fadeGain = 1f

    /** ほかのアプリの短い音の間に下げる音量。 */
    private var duckGain = 1f
    private var fade: Runnable? = null

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
    }

    private val port = object : VoicePlayerPort {
        override fun prepare(asset: String) {
            cancelFade()
            val p = player()
            p.playWhenReady = false
            if (asset == loadedAsset && !failed && p.playbackState != Player.STATE_IDLE) {
                // 同じクリップ（序歌の下の句の2回目など）は読み直さずに先頭へ戻す
                p.seekTo(0L)
            } else {
                p.setMediaItem(MediaItem.fromUri(ASSET_URI_PREFIX + asset))
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
                    fadeGain = fadeOutGain(SystemClock.uptimeMillis() - start, fadeOutMillis)
                    applyVolume()
                    if (fadeGain > 0f) {
                        handler.postDelayed(this, FADE_STEP_MILLIS)
                    } else {
                        fade = null
                        exo?.stop()
                        fadeGain = 1f
                        applyVolume()
                    }
                }
            }
            fade = step
            step.run()
        }

        override val isReady: Boolean get() = exo?.playbackState == Player.STATE_READY

        override val isPlaying: Boolean get() = exo?.isPlaying == true

        override val positionMillis: Long get() = exo?.currentPosition ?: 0L
    }

    private val follower = VoiceCueFollower(port)

    override fun update(cue: AudioCue?, engineTimeMillis: Long, paused: Boolean) {
        if (!released) follower.update(cue, engineTimeMillis, paused)
    }

    override fun warmUp() {
        if (!released) player()
    }

    override fun release() {
        if (released) return
        released = true
        cancelFade()
        follower.reset()
        exo?.release()
        exo = null
        focus.abandon()
    }

    /** 外から音を止められた。音をすぐ止めて、呼び出し側に対戦を止めてもらう。 */
    private fun interrupt() {
        exo?.pause()
        onInterrupted()
    }

    private fun cancelFade() {
        fade?.let(handler::removeCallbacks)
        fade = null
        fadeGain = 1f
        applyVolume()
    }

    private fun applyVolume() {
        exo?.volume = fadeGain * duckGain
    }

    private fun player(): ExoPlayer = exo ?: buildPlayer().also {
        exo = it
        applyVolume()
    }

    private fun buildPlayer(): ExoPlayer {
        // 鳴らすのはアセットの Ogg（Vorbis / Opus）だけなので、音声の描画とアセットの Ogg の読み取りだけを入れる
        // （映像・字幕の描画や、ほかの形式の読み取りを入れないぶん APK が小さくなる）
        val renderers = RenderersFactory { eventHandler, _, audioListener, _, _ ->
            arrayOf(MediaCodecAudioRenderer(appContext, MediaCodecSelector.DEFAULT, eventHandler, audioListener))
        }
        val source = ProgressiveMediaSource.Factory({ AssetDataSource(appContext) }, OggExtractor.FACTORY)
        return ExoPlayer.Builder(appContext, renderers, source)
            .setAudioAttributes(EXO_ATTRIBUTES, false)
            .setHandleAudioBecomingNoisy(true)
            .build()
            .also { it.addListener(listener) }
    }

    private companion object {
        const val TAG = "ExoVoicePlayer"
        const val ASSET_URI_PREFIX = "asset:///"

        /** ほかのアプリの短い音の間の音量。 */
        const val DUCK_GAIN = 0.2f

        /** 音を小さくしていく刻み（ms）。 */
        const val FADE_STEP_MILLIS = 16L

        /** ゲームの読み上げ（声）。音声フォーカスは [VoiceAudioFocus] で扱うので ExoPlayer には任せない。 */
        val EXO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_GAME)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()
    }
}

/** 止めるときの音量。[fadeMillis] かけて 1 から 0 へまっすぐ下げる。 */
internal fun fadeOutGain(elapsedMillis: Long, fadeMillis: Long): Float =
    if (fadeMillis <= 0L) 0f else (1f - elapsedMillis.toFloat() / fadeMillis).coerceIn(0f, 1f)
