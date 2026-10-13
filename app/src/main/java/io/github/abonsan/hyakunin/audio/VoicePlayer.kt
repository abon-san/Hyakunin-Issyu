package io.github.abonsan.hyakunin.audio

import android.content.Context
import io.github.abonsan.hyakunin.game.AudioClock
import io.github.abonsan.hyakunin.game.AudioCue
import io.github.abonsan.hyakunin.game.NextAudio

/**
 * 対戦の読み上げ音声を鳴らすもの。対戦の状態が変わるたびに [update] で今鳴らすべき音声を渡すと、
 * 再生をそれに合わせる（[VoiceCueFollower]）。使い終えたら [release]。メインスレッドからだけ呼ぶ。
 *
 * 鳴っている間は [AudioClock] として聞こえている位置を返し、対戦の時計はそれに合わせて進む
 * （[io.github.abonsan.hyakunin.game.MatchViewModel.attachAudioClock]）。
 */
interface VoicePlayer : AudioClock {
    /**
     * 今鳴らすべき音声 [cue]（なければ null）、エンジンの時計 [engineTimeMillis]、一時停止中か [paused]、
     * 次に鳴る見込みのクリップ [next]（先に読み込んでおく）。
     * [io.github.abonsan.hyakunin.game.MatchUiState] の `audio`・`engineTimeMillis`・`isPaused`・`nextAudio` をそのまま渡す。
     */
    fun update(cue: AudioCue?, engineTimeMillis: Long, paused: Boolean, next: NextAudio? = null)

    /** 最初の音が遅れないよう、再生器を先に用意しておく。 */
    fun warmUp() = Unit

    /**
     * 利用者の音量（設定の「音量」、0〜1。範囲の外の値は収める）。初めは 1。対戦を始めるときに渡す。
     * 鳴っている最中に変えても、位置は移さずに音量だけ変える。
     */
    fun setVolume(volume: Float) = Unit

    /** 鳴らすのをやめて再生器を手放す。以後は使わない。 */
    fun release()

    /** 音を鳴らさない再生器は、いつも「鳴っていない」（対戦は実時間で進む）。 */
    override fun heardPositionMillis(cueId: Long): Long? = null
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
 * [VoiceCueFollower] が命令する再生器1つ（ExoPlayer 1つ）。位置と長さはクリップの時間（ms、クリップの先頭から）。
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

    /** 再生速度の倍率。音の高さは変えない。読み込む前に合わせておく。 */
    fun setSpeed(speed: Float)

    /**
     * 利用者の音量（0〜1）。出す音量は、これに小さくして止めるときの音量などを掛けたもの。作ったばかりの再生器は 1。
     * 鳴っている最中に変えても位置は移さない。
     */
    fun setVolume(volume: Float)

    /** [fadeOutMillis] かけて音を小さくしてから止める。 */
    fun stop(fadeOutMillis: Long)

    /** 読み込みが終わり、すぐに鳴らせるか。 */
    val isReady: Boolean

    /** 実際に鳴っている（読み込みが終わって再生中の）か。音を小さくしている最中も true。 */
    val isPlaying: Boolean

    /** 今の再生位置（ms）。再生器が見積もった、いま聞こえている位置（ExoPlayer の currentPosition は出力の遅れを含めた位置）。 */
    val positionMillis: Long

    /** 診断用: 最後の [prepare] から読み込みが終わる（すぐ鳴らせる）までにかかった時間（ms）。まだ終わっていなければ null。 */
    val prepareMillis: Long? get() = null

    /** 診断用: 音の出力が途切れた（AudioTrack のアンダーラン）回数の累計。 */
    val underrunCount: Int get() = 0
}
