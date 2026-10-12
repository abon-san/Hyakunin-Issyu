package io.github.abonsan.hyakunin.ui

import android.content.Context
import io.github.abonsan.hyakunin.audio.VoicePlayer
import io.github.abonsan.hyakunin.audio.VoicePlayerFactory
import io.github.abonsan.hyakunin.game.AudioCue
import io.github.abonsan.hyakunin.game.NextAudio

/** 渡された状態を記録するだけの再生器（音は鳴らさない。対戦は実時間で進む）。 */
internal class RecordingVoicePlayer(val onInterrupted: () -> Unit) : VoicePlayer {
    /** [update] で渡された（cue, エンジンの時刻, 一時停止中か）。 */
    val updates = mutableListOf<Triple<AudioCue?, Long, Boolean>>()

    /** [update] で渡された、次に鳴る見込みのクリップ（[updates] と同じ順）。 */
    val nexts = mutableListOf<NextAudio?>()
    var warmedUp = false
    var released = false

    /** これまでに渡された cue（同じものは1つにまとめる）。 */
    val cues: List<AudioCue> get() = updates.mapNotNull { it.first }.distinctBy { it.cueId }

    override fun update(cue: AudioCue?, engineTimeMillis: Long, paused: Boolean, next: NextAudio?) {
        updates += Triple(cue, engineTimeMillis, paused)
        nexts += next
    }

    override fun warmUp() {
        warmedUp = true
    }

    override fun release() {
        released = true
    }
}

/** [RecordingVoicePlayer] を作り、作ったものを覚えておく。 */
internal class RecordingVoicePlayerFactory : VoicePlayerFactory {
    val players = mutableListOf<RecordingVoicePlayer>()

    override fun create(context: Context, onInterrupted: () -> Unit): VoicePlayer =
        RecordingVoicePlayer(onInterrupted).also { players += it }
}
