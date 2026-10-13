package io.github.abonsan.hyakunin.audio

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Media3 ExoPlayer で「試聴」のクリップを鳴らす [VoicePreview]。ExoPlayer の作り方・音声の属性・音声フォーカスの扱い
 * （[VoiceAudioFocus]）は対戦の読み上げ（[ExoVoicePlayer]）と同じ。ExoPlayer は最初に鳴らすときに作り、
 * 同じクリップをもう一度鳴らすときは読み直さずに先頭へ戻す。
 *
 * 利用者の音量（0〜2 倍）は PCM に掛け（[VoiceGainProcessor]）、鳴っている最中に変えると少しずつ寄せる（段差の雑音を
 * 出さない）。ExoPlayer の音量は、ほかのアプリの短い音の間だけ下げる（[OutputGain]、値が変わったときだけ渡す）。
 * 出力に先に書いておく音を短くして（[PREVIEW_AUDIO_BUFFER_MILLIS]）、スライダーを動かした音量がすぐ聞こえるようにする。
 * 鳴り終えた・音声フォーカスを失った・イヤホンが外れた・鳴らせなかったときは止めて [onStopped] を呼ぶ。
 */
@OptIn(UnstableApi::class)
class ExoVoicePreview(context: Context, private val onStopped: () -> Unit) : VoicePreview {
    private val appContext = context.applicationContext
    private val focus = VoiceAudioFocus(
        audioManager = appContext.getSystemService(AudioManager::class.java),
        onLoss = ::stopBySelf,
        onDuck = { ducked -> output.duck = if (ducked) VOICE_DUCK_GAIN else 1f },
    )

    private var exo: ExoPlayer? = null
    private var loadedAsset: String? = null
    private val gainStage = VoiceGainProcessor()
    private val output = OutputGain { exo?.volume = it }
    private var playing = false
    private var released = false

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) stopBySelf()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            // イヤホンが外れて ExoPlayer が止めた
            if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) stopBySelf()
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.w(ExoVoicePlayer.TAG, "試聴の音声を鳴らせない: $loadedAsset", error)
            // 次に押されたときは読み込み直す
            loadedAsset = null
            stopBySelf()
        }
    }

    override fun play(asset: String, volume: Float): Boolean {
        if (released || !focus.request()) return false
        gainStage.gain = volume
        val p = player()
        // 鳴っていれば止めてから先頭へ戻す（鳴っている最中には位置を移さない）
        p.pause()
        if (asset == loadedAsset && p.playbackState != Player.STATE_IDLE) {
            p.seekTo(0L)
        } else {
            p.setMediaItem(voiceMediaItem(asset))
            p.prepare()
            loadedAsset = asset
        }
        p.play()
        playing = true
        return true
    }

    override fun setVolume(volume: Float) {
        gainStage.gain = volume
    }

    /** ExoPlayer に渡してある音量（まだ作っていなければ null）。 */
    @VisibleForTesting
    internal val outputVolume: Float? get() = exo?.volume

    /** PCM に掛ける利用者の音量。 */
    @VisibleForTesting
    internal val gain: Float get() = gainStage.gain

    override fun stop() {
        if (!playing) return
        playing = false
        exo?.pause()
        focus.abandon()
    }

    override fun release() {
        if (released) return
        released = true
        playing = false
        exo?.release()
        exo = null
        focus.abandon()
    }

    /** 鳴り終えた・外から止められた。止めて、呼び出し側に知らせる。 */
    private fun stopBySelf() {
        if (!playing) return
        stop()
        onStopped()
    }

    private fun player(): ExoPlayer = exo ?: buildVoiceExoPlayer(appContext, gainStage, PREVIEW_AUDIO_BUFFER_MILLIS).also {
        it.addListener(listener)
        exo = it
        // 作ったばかりの ExoPlayer の音量は 1。ほかの音の間の音量を渡し直す（利用者の音量は gainStage が持つ）
        output.reattach()
    }

    companion object {
        /**
         * 出力（AudioTrack）に先に書いておく音の長さ（ms）。PCM に掛けた音量は、これだけ遅れて聞こえる
         * （Media3 の既定の 500ms では、スライダーを動かしてから音量が変わるまでが長い）。対戦の再生器は既定のまま。
         */
        const val PREVIEW_AUDIO_BUFFER_MILLIS = 150
    }
}
