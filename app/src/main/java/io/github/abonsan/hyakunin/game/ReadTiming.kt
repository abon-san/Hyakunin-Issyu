package io.github.abonsan.hyakunin.game

/**
 * 読み上げと進行のタイミング定数（ms）。
 * 次の読みを始めるまでの待ち（Waiting）は [GameConfig.memorizeTime] / [GameConfig.readInterval] で決まる。
 *
 * @property charMillis 仮名1文字ごとの表示間隔
 * @property emptyTailMillis 空札で、上の句を表示し終えてから読みを終えるまでの間
 * @property noTakerMillis 両者がロックアウトしたとき、下の句を表示し終えてから出札を取り除くまでの間
 * @property resultAutoMillis 空札（お手つきなし）の Result を表示してから自動で進むまでの間
 * @property cpuLateLimitMillis CPU が遅くとも下の句を表示し終えてから取るまでの間
 */
data class ReadTiming(
    val charMillis: Long,
    val emptyTailMillis: Long = 1200L,
    val noTakerMillis: Long = 1500L,
    val resultAutoMillis: Long = 1200L,
    val cpuLateLimitMillis: Long = 500L,
) {
    /** 句の区切り（空白）で追加する間。 */
    val phraseGapMillis: Long get() = charMillis

    /** 上の句と下の句の間に追加する間。 */
    val kamiShimoGapMillis: Long get() = charMillis * 3

    companion object {
        fun of(speed: ReadingSpeed): ReadTiming = ReadTiming(speed.charMillis)
    }
}
