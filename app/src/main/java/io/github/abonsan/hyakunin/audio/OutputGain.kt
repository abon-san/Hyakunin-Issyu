package io.github.abonsan.hyakunin.audio

/**
 * 再生器1つが出す音量（0〜1）。利用者の音量（設定の「音量」）× 小さくして止めるときの音量 × ほかのアプリの短い音の間の音量。
 * 値が変わったときだけ [apply] に渡す（ふつうに鳴っている間は変わらないので、刻みごとには渡さない）。
 * 渡し先（ExoPlayer）の音量は、作ったばかりなら 1 とみなす。
 */
internal class OutputGain(private val apply: (Float) -> Unit) {
    /** 利用者の音量。範囲の外の値は 0〜1 に収める（数でない値は無視する）。 */
    var user: Float = 1f
        set(value) {
            if (value.isNaN()) return
            field = value.coerceIn(0f, 1f)
            update()
        }

    /** 小さくして止めるときの音量（1 から 0 へ下げる）。 */
    var fade: Float = 1f
        set(value) {
            field = value
            update()
        }

    /** ほかのアプリの短い音（通知音など）の間の音量。 */
    var duck: Float = 1f
        set(value) {
            field = value
            update()
        }

    /** 今出す音量。 */
    val value: Float get() = user * fade * duck

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
