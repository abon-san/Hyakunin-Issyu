package io.github.abonsan.hyakunin.audio

import android.content.Context

/**
 * 読み上げ音声のクリップ1つを鳴らすもの。設定の画面の「試聴」と、「札を覚える」の上の句に使う。
 * メインスレッドからだけ呼ぶ。使い終えたら [release]。
 */
interface VoicePreview {
    /**
     * アセットの音声ファイル [asset] を、先頭から音量 [volume]（クリップそのままの大きさを 1 とした倍率、0〜2）で鳴らす。鳴っていれば先頭から鳴らし直す。
     * 鳴らし始めたら true。音声フォーカスを得られない（電話中など）・手放した後なら、鳴らさずに false。
     */
    fun play(asset: String, volume: Float): Boolean

    /** 音量を [volume]（0〜2）にする。鳴っていれば、そのまま音量だけ変わる（少しずつ寄せる）。 */
    fun setVolume(volume: Float)

    /** 鳴っていれば、位置を保ったまま止める（[resume] で続きから鳴らす）。鳴っていなければ何もしない。 */
    fun pause()

    /**
     * [pause] で止めていれば、続きから鳴らす。鳴らし始めたら true。止めていない・音声フォーカスを得られない
     * （電話中など）なら false（止めたまま）。
     */
    fun resume(): Boolean

    /** 鳴らしている（[pause] で止めている間も）クリップの、聞こえている位置（ms）。鳴らしていなければ null。 */
    val positionMillis: Long?

    /** 止める（[pause] で止めていたものも）。鳴っていなければ何もしない。 */
    fun stop()

    /** 止めて再生器を手放す。以後は鳴らさない。 */
    fun release()
}

/** [VoicePreview] を作る。 */
fun interface VoicePreviewFactory {
    /**
     * @param onStopped [VoicePreview.stop] を呼ばずに止まった（鳴り終えた・音声フォーカスを失った・イヤホンが外れた・
     *   鳴らせなかった）ときに呼ぶ。
     */
    fun create(context: Context, onStopped: () -> Unit): VoicePreview
}
