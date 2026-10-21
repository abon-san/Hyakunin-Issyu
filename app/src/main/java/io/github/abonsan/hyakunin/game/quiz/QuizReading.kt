package io.github.abonsan.hyakunin.game.quiz

import io.github.abonsan.hyakunin.game.ReadingSpeed

/**
 * 上の句のクリップの位置 [positionMillis] までに読み始めた仮名の数。
 * [kanaStartMs] は仮名ごとの発音開始時刻（[io.github.abonsan.hyakunin.game.voice.VoiceClip.kanaStartMs]）。
 */
fun revealedKanaAt(positionMillis: Long, kanaStartMs: IntArray): Int = kanaStartMs.count { it <= positionMillis }

/** 読み上げ音声がないときに、上の句の仮名を1字ずつ出す間隔（ms）。対戦の文字だけの読み（ふつう）と同じ。 */
val TEXT_KANA_MILLIS: Long = ReadingSpeed.NORMAL.charMillis
