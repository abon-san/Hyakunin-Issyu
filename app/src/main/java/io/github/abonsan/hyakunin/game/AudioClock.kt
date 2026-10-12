package io.github.abonsan.hyakunin.game

/**
 * いま聞こえている読み上げ音声の位置。音声が鳴っている間、[MatchViewModel] はエンジンの時計を実時間ではなく
 * これで進める（表示と CPU を、聞こえている音に合わせる）。再生側（VoicePlayer）が実装する。
 */
fun interface AudioClock {
    /**
     * cue [cueId]（[AudioCue.cueId]）のクリップが鳴っていれば、いま聞こえている位置（クリップの ms、先頭から）。
     * まだ鳴らしていない・止めている・読み込み中・鳴り終えた・知らない cue なら null（エンジンは実時間で進む）。
     */
    fun heardPositionMillis(cueId: Long): Long?
}
