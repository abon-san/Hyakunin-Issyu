package io.github.abonsan.hyakunin.game

import io.github.abonsan.hyakunin.game.voice.VoiceBank

/**
 * 読み上げと進行のタイミング定数（ms）。
 * 次の読みを始めるまでの待ち（Waiting）は [GameConfig.memorizeTime] / [GameConfig.readInterval] で決まる。
 *
 * 音声があるとき（[voice] が渡され、[GameConfig.voiceEnabled] が true）は、各仮名の表示時刻をクリップの時刻表から作る。
 * クリップの時間（長さ・各仮名の開始・上の句と下の句の間）は [playbackSpeed] で割る。
 * 音声がない歌・音声を使わないときは [charMillis] による文字だけの時刻表で、従来どおり進む。
 *
 * @property charMillis 仮名1文字ごとの表示間隔（文字だけのとき）
 * @property emptyTailMillis 空札で、上の句を表示し終えてから読みを終えるまでの間（文字だけのとき）
 * @property noTakerMillis 両者がロックアウトしたとき、下の句を表示し終えてから出札を取り除くまでの間
 * @property resultAutoMillis 空札（お手つきなし）の Result を表示してから自動で進むまでの間
 * @property cpuLateLimitMillis CPU が遅くとも下の句を表示し終えてから取るまでの間
 * @property voice 読み上げ音声。null なら文字だけ
 * @property playbackSpeed 音声の再生速度の倍率（0 より大きい）。[ReadingSpeed.playbackSpeed] を使う
 * @property voiceKamiShimoGapMillis 音声で連続して読むときの、上の句のクリップの終わりから下の句のクリップの始まりまでの間（再生速度で割る）。
 *   見本の読みでは上の句と下の句の間が約 0.86 秒で、そのうち約 0.2 秒はクリップの余白なので 650 ms
 * @property maMillis 競技かるた式の前置きの終わりの間合い（一定。再生速度では割らない）。この後に上の句が始まる
 * @property yoinMillis 文字だけで前置きを出すときの、下の句の余韻の長さ（音声では下の句のクリップに含まれる）
 * @property textLeadIn 音声がないときも、競技かるた式の前置きを文字だけで出すか。
 *   false（既定）なら、音声を使わないときは [GameConfig.readingStyle] によらず前置きなしで、従来の進行と同じ
 */
data class ReadTiming(
    val charMillis: Long,
    val emptyTailMillis: Long = 1200L,
    val noTakerMillis: Long = 1500L,
    val resultAutoMillis: Long = 1200L,
    val cpuLateLimitMillis: Long = 500L,
    val voice: VoiceBank? = null,
    val playbackSpeed: Float = 1.0f,
    val voiceKamiShimoGapMillis: Long = 650L,
    val maMillis: Long = 1000L,
    val yoinMillis: Long = 3000L,
    val textLeadIn: Boolean = false,
) {
    init {
        require(playbackSpeed > 0f) { "playbackSpeed=$playbackSpeed" }
    }

    /** 句の区切り（空白）で追加する間（文字だけのとき）。 */
    val phraseGapMillis: Long get() = charMillis

    /** 上の句と下の句の間に追加する間（文字だけのとき）。 */
    val kamiShimoGapMillis: Long get() = charMillis * 3

    companion object {
        /** [speed] に合わせたタイミング。[voice] を渡すと音声で進める（[playbackSpeed] は [speed] から決まる）。 */
        fun of(speed: ReadingSpeed, voice: VoiceBank? = null, textLeadIn: Boolean = false): ReadTiming = ReadTiming(
            charMillis = speed.charMillis,
            voice = voice,
            playbackSpeed = speed.playbackSpeed,
            textLeadIn = textLeadIn,
        )
    }
}
