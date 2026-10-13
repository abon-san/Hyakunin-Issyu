package io.github.abonsan.hyakunin.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** 再生器が出す音量（OutputGain）: 利用者の音量 × 小さくして止めるときの音量 × ほかの音の間の音量を、変わったときだけ渡す。 */
class OutputGainTest {
    /** ExoPlayer に渡した音量（渡した順）。 */
    private val applied = mutableListOf<Float>()
    private val gain = OutputGain { applied += it }

    @Test
    fun theUserVolumeScalesTheFadeOut() {
        gain.user = 0.6f
        assertEquals(listOf(0.6f), applied)
        // 小さくして止めるときは、利用者の音量から 0 へ下げる（1 からではない）
        for (elapsed in listOf(0L, 75L, 150L)) gain.fade = fadeOutGain(elapsed, 150L)
        assertEquals(listOf(0.6f, 0.3f, 0f), applied)
        // 止め終えたら、次のクリップのために利用者の音量へ戻す
        gain.fade = 1f
        assertEquals(0.6f, applied.last())
        assertEquals(0.6f, gain.value)
    }

    @Test
    fun theVolumeIsAppliedOnlyWhenItChanges() {
        // 作ったばかりの ExoPlayer の音量は 1 なので、100% なら何も渡さない
        gain.user = 1f
        gain.fade = 1f
        gain.duck = 1f
        assertEquals(emptyList<Float>(), applied)
        gain.user = 0.5f
        // 同じ値をいくら渡しても（刻みごとに呼ばれても）、ExoPlayer には1回しか渡さない
        repeat(50) {
            gain.user = 0.5f
            gain.fade = 1f
            gain.duck = 1f
        }
        assertEquals(listOf(0.5f), applied)
    }

    @Test
    fun duckingMultipliesTheUserVolume() {
        gain.user = 0.5f
        gain.duck = VOICE_DUCK_GAIN
        assertEquals(0.1f, applied.last(), 1e-6f)
        gain.duck = 1f
        assertEquals(listOf(0.5f, 0.5f * VOICE_DUCK_GAIN, 0.5f), applied)
    }

    @Test
    fun aRebuiltPlayerGetsTheVolumeAgain() {
        gain.user = 0.4f
        gain.reattach()
        assertEquals(listOf(0.4f, 0.4f), applied)
        // 100% なら、作り直した ExoPlayer の音量（1）のままでよい
        gain.user = 1f
        applied.clear()
        gain.reattach()
        assertEquals(emptyList<Float>(), applied)
    }

    @Test
    fun theUserVolumeIsKeptInRange() {
        gain.user = 1.5f
        assertEquals(emptyList<Float>(), applied)
        gain.user = -0.2f
        assertEquals(listOf(0f), applied)
        gain.user = Float.NaN
        assertEquals(0f, gain.value)
        assertEquals(listOf(0f), applied)
    }
}
