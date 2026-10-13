package io.github.abonsan.hyakunin.audio

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.StreamMetadata
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import io.github.abonsan.hyakunin.game.GameConfig
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 読み上げ音声の PCM に、利用者の音量（設定の「音量」）を掛ける AudioProcessor。再生器の DefaultAudioSink に入れる
 * （[voiceAudioSink]）。音量はクリップそのままの大きさを 1 とした倍率で、0〜[GameConfig.MAX_VOICE_VOLUME]（2 倍）。
 * ExoPlayer の音量（setVolume）は 1 を超えられないので、利用者の音量はすべてここで掛ける（ExoPlayer の音量は、
 * 小さくして止めるときと、ほかのアプリの短い音の間だけに使う。[OutputGain]）。
 *
 * Media3 の GainProcessor は使わない。倍率は 0〜1 まで、再生位置ごとに決めた値を後から変えない決まりで
 * （鳴っている最中にスライダーで変える「試聴」に合わない）、16 ビットの音に 1 を超える倍率を掛けると値が折り返す。
 *
 * - [gain] はどのスレッドから変えてもよい（ふつうはメインスレッド。音を通すのは ExoPlayer の再生のスレッド）
 * - 鳴っている最中に変わったら、[RAMP_MILLIS] かけてまっすぐ寄せる（一度に変えると段差の雑音が出る）。
 *   鳴り始め（読み込み・位置の移動で DefaultAudioSink が流し直した後）は、そのときの倍率でそのまま始める
 * - 掛けた値は表せる範囲に収める（16 ビットは −32768〜32767、浮動小数は −1〜1）。クリップの山は約 −7 dBFS なので
 *   2 倍までなら収めることはないが、念のため
 * - 16 ビット整数と浮動小数の PCM を扱う（DefaultAudioSink は 16 ビットにしてから渡す）。ほかの形式は手を加えずに通す
 *
 * 1つの DefaultAudioSink（再生器1つ）にだけ入れること。
 */
@OptIn(UnstableApi::class)
internal class VoiceGainProcessor(gain: Float = 1f) : BaseAudioProcessor() {
    /** 目指す倍率。メインスレッドが書き、再生のスレッドが読む。 */
    @Volatile
    private var target: Float = clampGain(gain) ?: 1f

    /**
     * 利用者の音量（倍率）。0〜[GameConfig.MAX_VOICE_VOLUME] に収める（数でない値は無視する）。
     * 鳴っている最中に変えると、[RAMP_MILLIS] かけてその倍率に寄せる。
     */
    var gain: Float
        get() = target
        set(value) {
            clampGain(value)?.let { target = it }
        }

    // ここから下は再生のスレッドだけが触る

    /** 最後に通したフレームに掛けた倍率。 */
    private var current = target

    /** 寄せ始めた倍率。 */
    private var rampFrom = target

    /** 寄せる先の倍率（寄せ終えたら [current] と同じ）。 */
    private var rampTo = target

    /** 寄せるのにかけるフレーム数（[RAMP_MILLIS] 分）。 */
    private var rampFrames = 1

    /** 寄せ終えたフレーム数。[rampFrames] なら寄せ終えている。 */
    private var rampDone = 1

    /** 流し直してから、まだ音を通していない（次の音は、そのときの倍率でそのまま始める）。 */
    private var fresh = true

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat =
        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT -> inputAudioFormat
            // 扱えない形式では働かない（DefaultAudioSink はこの段を飛ばして、手を加えずに流す）
            else -> AudioFormat.NOT_SET
        }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val format = inputAudioFormat
        require(inputBuffer.remaining() % format.bytesPerFrame == 0) { "フレームの途中で切れている: ${inputBuffer.remaining()}" }
        follow(target)
        val output = replaceOutputBuffer(inputBuffer.remaining())
        if (rampDone >= rampFrames && current == 1f) {
            // 1 倍のまま（既定の 100%）。そのまま写す
            output.put(inputBuffer)
        } else {
            val frames = inputBuffer.remaining() / format.bytesPerFrame
            val channels = format.channelCount
            if (format.encoding == C.ENCODING_PCM_16BIT) {
                repeat(frames) {
                    val g = nextGain()
                    repeat(channels) { output.putShort(scalePcm16(inputBuffer.getShort(), g)) }
                }
            } else {
                repeat(frames) {
                    val g = nextGain()
                    repeat(channels) { output.putFloat(scalePcmFloat(inputBuffer.getFloat(), g)) }
                }
            }
        }
        output.flip()
    }

    override fun onFlush(streamMetadata: StreamMetadata) {
        rampFrames = max(1, inputAudioFormat.sampleRate * RAMP_MILLIS / 1000)
        fresh = true
    }

    override fun onReset() {
        fresh = true
    }

    /** 目指す倍率 [goal] を受け取る。鳴り始めならそのまま使い、鳴っている最中に変わったら今の倍率から寄せ始める。 */
    private fun follow(goal: Float) {
        if (fresh) {
            fresh = false
            current = goal
            rampFrom = goal
            rampTo = goal
            rampDone = rampFrames
        } else if (goal != rampTo) {
            rampFrom = current
            rampTo = goal
            rampDone = 0
        }
    }

    /** 次のフレームに掛ける倍率。寄せている間は、1フレームごとに寄せる先へまっすぐ近づける。 */
    private fun nextGain(): Float {
        if (rampDone < rampFrames) {
            rampDone++
            current = if (rampDone == rampFrames) rampTo else rampFrom + (rampTo - rampFrom) * rampDone / rampFrames
        }
        return current
    }

    internal companion object {
        /** 鳴っている最中に倍率が変わったとき、新しい倍率へ寄せるのにかける時間（ms）。 */
        const val RAMP_MILLIS = 50

        /** 倍率を 0〜[GameConfig.MAX_VOICE_VOLUME] に収める。数でなければ null。 */
        private fun clampGain(gain: Float): Float? =
            if (gain.isNaN()) null else gain.coerceIn(0f, GameConfig.MAX_VOICE_VOLUME)

        /** 16 ビットの値に倍率を掛ける（四捨五入して、表せる範囲に収める）。 */
        private fun scalePcm16(sample: Short, gain: Float): Short =
            (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()

        /** 浮動小数の値に倍率を掛ける（−1〜1 に収める）。 */
        private fun scalePcmFloat(sample: Float, gain: Float): Float = (sample * gain).coerceIn(-1f, 1f)
    }
}
