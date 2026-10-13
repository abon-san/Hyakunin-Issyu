package io.github.abonsan.hyakunin.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 再生器の ExoPlayer の音量（OutputGain）: 小さくして止めるときの音量 × ほかの音の間の音量を、変わったときだけ渡す。
 * 利用者の音量は PCM に掛ける（VoiceGainProcessor）ので、ここには入らず、1 を超えない。
 */
class OutputGainTest {
    /** ExoPlayer に渡した音量（渡した順）。 */
    private val applied = mutableListOf<Float>()
    private val gain = OutputGain { applied += it }

    @Test
    fun theFadeOutGoesFromOneToZeroAndBack() {
        // 小さくして止めるときは 1 から 0 へ下げる（聞こえる音は、PCM に掛けた利用者の音量から 0 へ下がる）
        for (elapsed in listOf(0L, 75L, 150L)) gain.fade = fadeOutGain(elapsed, 150L)
        assertEquals(listOf(0.5f, 0f), applied)
        // 止め終えたら、次のクリップのために 1 へ戻す
        gain.fade = 1f
        assertEquals(1f, applied.last())
        assertEquals(1f, gain.value)
    }

    @Test
    fun theVolumeIsAppliedOnlyWhenItChanges() {
        // 作ったばかりの ExoPlayer の音量は 1 なので、ふつうに鳴っている間は何も渡さない
        repeat(50) {
            gain.fade = 1f
            gain.duck = 1f
        }
        assertEquals(emptyList<Float>(), applied)
        gain.duck = VOICE_DUCK_GAIN
        repeat(50) { gain.duck = VOICE_DUCK_GAIN }
        assertEquals(listOf(VOICE_DUCK_GAIN), applied)
    }

    @Test
    fun duckingMultipliesTheFade() {
        gain.fade = 0.5f
        gain.duck = VOICE_DUCK_GAIN
        assertEquals(0.1f, applied.last(), 1e-6f)
        gain.duck = 1f
        assertEquals(listOf(0.5f, 0.5f * VOICE_DUCK_GAIN, 0.5f), applied)
    }

    @Test
    fun aRebuiltPlayerGetsTheVolumeAgain() {
        gain.duck = VOICE_DUCK_GAIN
        gain.reattach()
        assertEquals(listOf(VOICE_DUCK_GAIN, VOICE_DUCK_GAIN), applied)
        // 1 なら、作り直した ExoPlayer の音量（1）のままでよい
        gain.duck = 1f
        applied.clear()
        gain.reattach()
        assertEquals(emptyList<Float>(), applied)
    }

    @Test
    fun theVolumeNeverExceedsOne() {
        gain.fade = 1.5f
        gain.duck = 2f
        assertEquals(1f, gain.value)
        assertEquals(emptyList<Float>(), applied)
        gain.fade = -0.2f
        assertEquals(listOf(0f), applied)
    }
}
