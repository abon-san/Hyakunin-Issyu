package io.github.abonsan.hyakunin.audio

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.AssetDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ogg.OggExtractor

// 対戦の読み上げ（ExoVoicePlayer）と設定の画面の「試聴」（ExoVoicePreview）で共通の、ExoPlayer の作り方と音声の属性。

/**
 * 読み上げ音声（アセットの Ogg）を鳴らす ExoPlayer を作る。ゲームの読み上げ（声）の音声の属性で鳴らし、
 * 音声フォーカスは ExoPlayer に任せない（呼び出し側が [VoiceAudioFocus] で扱う）。イヤホンが外れたら止める。
 * 音は [gain]（利用者の音量を PCM に掛ける）を通してから出す（[voiceAudioSink]）。[gain] はこの ExoPlayer だけに使う。
 *
 * @param audioBufferMillis 出力（AudioTrack）に先に書いておく音の長さ（ms）。null なら Media3 の既定（500ms）。
 *   [gain] を変えてから聞こえるまで、おおよそこれだけ遅れる
 */
@OptIn(UnstableApi::class)
internal fun buildVoiceExoPlayer(context: Context, gain: VoiceGainProcessor, audioBufferMillis: Int? = null): ExoPlayer {
    val appContext = context.applicationContext
    // 鳴らすのはアセットの Ogg（Vorbis / Opus）だけなので、音声の描画とアセットの Ogg の読み取りだけを入れる
    // （映像・字幕の描画や、ほかの形式の読み取りを入れないぶん APK が小さくなる）
    val renderers = RenderersFactory { eventHandler, _, audioListener, _, _ ->
        arrayOf(
            MediaCodecAudioRenderer(
                appContext,
                MediaCodecSelector.DEFAULT,
                eventHandler,
                audioListener,
                voiceAudioSink(appContext, gain, audioBufferMillis),
            ),
        )
    }
    val source = ProgressiveMediaSource.Factory({ AssetDataSource(appContext) }, OggExtractor.FACTORY)
    return ExoPlayer.Builder(appContext, renderers, source)
        .setAudioAttributes(VOICE_EXO_ATTRIBUTES, false)
        .setHandleAudioBecomingNoisy(true)
        .build()
}

/**
 * 読み上げ音声の出力先。Media3 の既定の DefaultAudioSink に、利用者の音量を PCM に掛ける段 [gain] を足したもの
 * （DefaultAudioSink は 16 ビットにしてから [gain] に渡し、その後に再生速度の段を通す）。浮動小数での出力は使わない
 * （使うと DefaultAudioSink は足した段を通さない）。
 *
 * @param audioBufferMillis 出力（AudioTrack）に先に書いておく音の長さ（ms）。null なら Media3 の既定
 */
@OptIn(UnstableApi::class)
internal fun voiceAudioSink(context: Context, gain: VoiceGainProcessor, audioBufferMillis: Int? = null): AudioSink {
    val builder = DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf(gain))
    if (audioBufferMillis != null) {
        val bufferSize = DefaultAudioTrackBufferSizeProvider.Builder()
            .setTargetPcmBufferDurationUs(audioBufferMillis * 1_000)
            .build()
        builder.setAudioOutputProvider(
            AudioTrackAudioOutputProvider.Builder(context).setAudioTrackBufferSizeProvider(bufferSize).build(),
        )
    }
    return builder.build()
}

/** アセットの音声ファイル [asset]（例: voice/himari/001_kami.ogg）を鳴らす MediaItem。 */
internal fun voiceMediaItem(asset: String): MediaItem = MediaItem.fromUri("asset:///$asset")

/** ほかのアプリの短い音（通知音など）の間の音量。 */
internal const val VOICE_DUCK_GAIN = 0.2f

/** ゲームの読み上げ（声）。[VoiceAudioFocus.SPEECH_ATTRIBUTES] と同じ。 */
private val VOICE_EXO_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
    .setUsage(C.USAGE_GAME)
    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
    .build()
