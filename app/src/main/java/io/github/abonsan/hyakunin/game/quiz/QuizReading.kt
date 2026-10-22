package io.github.abonsan.hyakunin.game.quiz

import io.github.abonsan.hyakunin.game.ReadTiming
import io.github.abonsan.hyakunin.game.ReadingSpeed

/**
 * 句のクリップの位置 [positionMillis] までに読み始めた仮名の数。
 * [kanaStartMs] は仮名ごとの発音開始時刻（[io.github.abonsan.hyakunin.game.voice.VoiceClip.kanaStartMs]）。
 */
fun revealedKanaAt(positionMillis: Long, kanaStartMs: IntArray): Int = kanaStartMs.count { it <= positionMillis }

/** 対戦の「ふつう」の速さの読み。練習の読みの間合いはこれに合わせる。 */
private val NormalTiming = ReadTiming.of(ReadingSpeed.NORMAL)

/** 読み上げ音声がないときに、仮名を1字ずつ出す間隔（ms）。対戦の文字だけの読み（ふつう）と同じ。 */
val TEXT_KANA_MILLIS: Long = NormalTiming.charMillis

/** 文字だけで読むときの、上の句を出し終えてから下の句を出し始めるまでの間（ms）。対戦と同じ。 */
val TEXT_KAMI_SHIMO_GAP_MILLIS: Long = NormalTiming.kamiShimoGapMillis

/** 音声で読むときの、上の句のクリップの終わりから下の句のクリップを鳴らし始めるまでの間（ms）。対戦と同じ。 */
val VOICE_KAMI_SHIMO_GAP_MILLIS: Long = NormalTiming.voiceKamiShimoGapMillis
