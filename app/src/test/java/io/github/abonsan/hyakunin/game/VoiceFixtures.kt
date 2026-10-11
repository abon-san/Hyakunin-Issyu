package io.github.abonsan.hyakunin.game

import io.github.abonsan.hyakunin.game.voice.VoiceBank
import io.github.abonsan.hyakunin.game.voice.fakeVoiceBank

// 音声（VoiceBank）で読むテストの共通部品。時刻の見積りは、fakeVoiceBank（合成の100首）の次の値による:
//   上の句のクリップ: 仮名 16 個が 100 + 200 i ms、長さ 3500
//   下の句のクリップ: 仮名 10 個が 100 + 300 j ms、長さ 5800（余韻 3000）
//   序歌の上の句: 仮名 17 個が 100 + 100 i ms、長さ 1900 / 序歌の下の句: 仮名 14 個が 100 + 100 j ms、長さ 4400（余韻 3000）
// 再生速度 1.0、上の句と下の句の間 500（voiceTiming の値。ReadTiming の既定は 650）、間合い 1000 のとき、読み始めを R とすると
//   上の句の仮名 = R + 100 + 200 i、下の句のクリップの先頭 = R + 4000、下の句の仮名 = R + 4100 + 300 j、
//   下の句の最後の仮名 = R + 6800、下の句のクリップの終わり = R + 9800
//   最初の前置き（序歌）は 12200ms: 上の句 [0,1900]、下の句 [2400,6800]、もう一度 [6800,11200]、間合い [11200,12200]
//   2枚目以降の前置き（直前の歌の下の句）は 6800ms: 下の句 [0,5800]、間合い [5800,6800]

/**
 * 音声で読むタイミング。1文字の表示間隔（音声がないときのため）は 100ms。
 * 上の句と下の句の間は、計算しやすいよう 500ms にする（[ReadTiming] の既定は 650ms）。
 */
internal fun voiceTiming(
    playbackSpeed: Float = 1.0f,
    voice: VoiceBank? = fakeVoiceBank(),
    textLeadIn: Boolean = false,
    charMillis: Long = 100L,
    kamiShimoGapMillis: Long = 500L,
): ReadTiming = ReadTiming(
    charMillis,
    voice = voice,
    playbackSpeed = playbackSpeed,
    voiceKamiShimoGapMillis = kamiShimoGapMillis,
    textLeadIn = textLeadIn,
)

/** 前置きが入る（競技かるた式で音声あり）ときの、最初の前置きの長さ。 */
internal const val FIRST_LEAD_IN_MILLIS = 12_200L

/** 前置きが入るときの、2枚目以降の前置きの長さ。 */
internal const val LATER_LEAD_IN_MILLIS = 6_800L

/**
 * Waiting の待ちを飛ばして読みを始める。前置きが入るときは、その最後まで進めてから上の句を始める。
 * 戻り値は始まった読み。前置きを飛ばさずに調べたいテストは [MatchEngine.startReading] を直接呼ぶ。
 */
internal fun MatchEngine.beginReadThroughLeadIn(): ReadSession {
    startReading()
    state.leadIn?.let { advanceBy(it.remainingMillis) }
    check(state.phase == MatchPhase.Reading) { "phase=${state.phase}" }
    return session!!
}
