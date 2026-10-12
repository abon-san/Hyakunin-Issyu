package io.github.abonsan.hyakunin.audio

import android.content.Context
import io.github.abonsan.hyakunin.game.AudioCue

/**
 * 対戦の読み上げ音声を鳴らすもの。対戦の状態が変わるたびに [update] で今鳴らすべき音声を渡すと、
 * 再生をそれに合わせる（[VoiceCueFollower]）。使い終えたら [release]。メインスレッドからだけ呼ぶ。
 */
interface VoicePlayer {
    /**
     * 今鳴らすべき音声 [cue]（なければ null）、エンジンの時計 [engineTimeMillis]、一時停止中か [paused]。
     * [io.github.abonsan.hyakunin.game.MatchUiState] の `audio`・`engineTimeMillis`・`isPaused` をそのまま渡す。
     */
    fun update(cue: AudioCue?, engineTimeMillis: Long, paused: Boolean)

    /** 最初の音が遅れないよう、再生器を先に用意しておく。 */
    fun warmUp() = Unit

    /** 鳴らすのをやめて再生器を手放す。以後は使わない。 */
    fun release()
}

/** [VoicePlayer] を作る。 */
fun interface VoicePlayerFactory {
    /**
     * @param onInterrupted 音声フォーカスを失った・イヤホンが外れたなどで、外から音を止められたときに呼ぶ。
     *   呼び出し側は対戦を一時停止して、表示と音声をそろえる。
     */
    fun create(context: Context, onInterrupted: () -> Unit): VoicePlayer
}

/**
 * [VoiceCueFollower] が命令する再生器。位置と長さはクリップの時間（ms、クリップの先頭から）。
 */
interface VoicePlayerPort {
    /** [asset] を読み込み、先頭で止めて待つ。鳴っていた音はすぐに止める。 */
    fun prepare(asset: String)

    /** 今の位置から鳴らす。 */
    fun play()

    /** 止める（位置はそのまま）。 */
    fun pause()

    /** 位置を [positionMillis] に移す。 */
    fun seekTo(positionMillis: Long)

    /** 再生速度の倍率。音の高さは変えない。 */
    fun setSpeed(speed: Float)

    /** [fadeOutMillis] かけて音を小さくしてから止める。 */
    fun stop(fadeOutMillis: Long)

    /** 読み込みが終わり、すぐに鳴らせるか。 */
    val isReady: Boolean

    /** 実際に鳴っている（読み込みが終わって再生中の）か。 */
    val isPlaying: Boolean

    /** 今の再生位置（ms）。再生器が見積もった、いま聞こえている位置。 */
    val positionMillis: Long
}
