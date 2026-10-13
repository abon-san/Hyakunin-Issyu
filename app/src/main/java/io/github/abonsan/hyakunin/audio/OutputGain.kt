package io.github.abonsan.hyakunin.audio

/**
 * 再生器1つの ExoPlayer の音量（0〜1）。小さくして止めるときの音量 × ほかのアプリの短い音の間の音量。
 * 利用者の音量（設定の「音量」）は含めない（PCM に掛ける。[VoiceGainProcessor]）ので、1 を超えることはない。
 * 値が変わったときだけ [apply] に渡す（ふつうに鳴っている間は変わらないので、刻みごとには渡さない）。
 * 渡し先（ExoPlayer）の音量は、作ったばかりなら 1 とみなす。
 */
internal class OutputGain(private val apply: (Float) -> Unit) {
    /** 小さくして止めるときの音量（1 から 0 へ下げる）。0〜1 に収める。 */
    var fade: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            update()
        }

    /** ほかのアプリの短い音（通知音など）の間の音量。0〜1 に収める。 */
    var duck: Float = 1f
        set(value) {
            field = value.coerceIn(0f, 1f)
            update()
        }

    /** 今の ExoPlayer の音量。 */
    val value: Float get() = fade * duck

    /** 渡し先に渡してある音量。 */
    private var applied = 1f

    /** 渡し先を作り直した（音量は 1 に戻っている）。今の音量を渡し直す。 */
    fun reattach() {
        applied = 1f
        update()
    }

    private fun update() {
        val v = value
        if (v == applied) return
        applied = v
        apply(v)
    }
}
