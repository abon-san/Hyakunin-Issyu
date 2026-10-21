package io.github.abonsan.hyakunin.ui.settings

import android.content.Context
import io.github.abonsan.hyakunin.audio.VoicePreview
import io.github.abonsan.hyakunin.audio.VoicePreviewFactory

/** 渡された命令を記録するだけの「試聴」の再生器（音は鳴らさない）。[finish] で鳴り終えたことにできる。 */
internal class FakeVoicePreview(private val onStopped: () -> Unit) : VoicePreview {
    /** 渡された命令（「play 音声 音量」「volume 音量」「pause」「resume」「stop」「release」）。 */
    val commands = mutableListOf<String>()

    var playing = false
        private set

    /** [pause] で止めているか。 */
    var paused = false
        private set

    /** 鳴らしている間に返す位置（ms）。テストが進める。 */
    var position = 0L

    /** 今の音量。まだ渡されていなければ null。 */
    var volume: Float? = null
        private set

    var released = false
        private set

    /** false にすると、音声フォーカスを得られない（電話中など）ことにする。 */
    var focusGranted = true

    override fun play(asset: String, volume: Float): Boolean {
        commands += "play $asset $volume"
        if (released || !focusGranted) return false
        this.volume = volume
        playing = true
        paused = false
        position = 0L
        return true
    }

    override fun pause() {
        commands += "pause"
        if (!playing) return
        playing = false
        paused = true
    }

    override fun resume(): Boolean {
        commands += "resume"
        if (!paused || !focusGranted) return false
        paused = false
        playing = true
        return true
    }

    override val positionMillis: Long?
        get() = if (playing || paused) position else null

    override fun setVolume(volume: Float) {
        commands += "volume $volume"
        this.volume = volume
    }

    override fun stop() {
        commands += "stop"
        playing = false
        paused = false
    }

    override fun release() {
        commands += "release"
        playing = false
        paused = false
        released = true
    }

    /** 鳴り終えた（または外から止められた）ことにする。 */
    fun finish() {
        if (!playing) return
        playing = false
        onStopped()
    }
}

/** [FakeVoicePreview] を作り、作ったものを覚えておく。 */
internal class FakeVoicePreviewFactory : VoicePreviewFactory {
    val previews = mutableListOf<FakeVoicePreview>()

    override fun create(context: Context, onStopped: () -> Unit): VoicePreview =
        FakeVoicePreview(onStopped).also { previews += it }
}
