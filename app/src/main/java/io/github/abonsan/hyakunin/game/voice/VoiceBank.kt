package io.github.abonsan.hyakunin.game.voice

import io.github.abonsan.hyakunin.data.Poem
import io.github.abonsan.hyakunin.game.kanaCount

// 事前に作った読み上げ音声（VOICEVOX）の型。生成したデータの読み込みと再生（Media3）がこの型を使い、
// 対戦のエンジンは時刻表（kanaStartMs）だけを見て、表示と CPU を音声に合わせる。

/**
 * 1つの句（上の句または下の句）を読み上げた音声クリップ。
 *
 * @property asset 音声ファイルの名前（アセット内のパス）。再生側が解決する
 * @property durationMs クリップ全体の長さ（ms）。下の句のクリップは、最後の音を伸ばして消える余韻（約 3 秒）を含む
 * @property kanaStartMs 読みの仮名（空白を除く。小書き仮名も1文字）ごとの発音開始時刻（ms、クリップの先頭から）。
 *   大きさは句の仮名の数と同じで、小さい順に並ぶ
 */
data class VoiceClip(
    val asset: String,
    val durationMs: Long,
    val kanaStartMs: IntArray,
) {
    init {
        require(durationMs >= 0L) { "durationMs=$durationMs" }
        for (i in kanaStartMs.indices) {
            require(kanaStartMs[i] >= 0) { "kanaStartMs[$i]=${kanaStartMs[i]}" }
            require(i == 0 || kanaStartMs[i - 1] <= kanaStartMs[i]) { "kanaStartMs is not ascending at $i" }
        }
        require(kanaStartMs.isEmpty() || kanaStartMs.last() <= durationMs) {
            "kanaStartMs.last=${kanaStartMs.last()} > durationMs=$durationMs"
        }
    }

    /** この句の読み [yomi] の仮名の数と、時刻表の大きさが合っているか。 */
    fun fits(yomi: String): Boolean = kanaStartMs.size == kanaCount(yomi)

    // IntArray を持つので、内容で比べる
    override fun equals(other: Any?): Boolean =
        this === other || (
            other is VoiceClip &&
                asset == other.asset &&
                durationMs == other.durationMs &&
                kanaStartMs.contentEquals(other.kanaStartMs)
            )

    override fun hashCode(): Int = (asset.hashCode() * 31 + durationMs.hashCode()) * 31 + kanaStartMs.contentHashCode()

    override fun toString(): String = "VoiceClip($asset, ${durationMs}ms, ${kanaStartMs.size} kana)"
}

/** 1首ぶんの音声。上の句と下の句のクリップは別ファイルで、どちらも必要。 */
data class PoemVoice(
    val kami: VoiceClip,
    val shimo: VoiceClip,
) {
    /** 両方のクリップの時刻表が、[poem] の読みの仮名の数と合っているか。合わなければエンジンは文字だけの時刻表に戻る。 */
    fun fits(poem: Poem): Boolean = kami.fits(poem.kamiYomi) && shimo.fits(poem.shimoYomi)
}

/**
 * 読み上げ音声の一覧。生成したデータから作り、[io.github.abonsan.hyakunin.game.ReadTiming.voice] に渡す。
 * 音声がない歌・序歌は null（エンジンはその部分を文字だけの時刻表で進める）。
 */
interface VoiceBank {
    /** 歌番号 [no]（1..100）の音声。ない場合は null。 */
    fun poem(no: Int): PoemVoice?

    /** 序歌の上の句。ない場合は null。 */
    val jokaKami: VoiceClip?

    /** 序歌の下の句（余韻を含む）。ない場合は null。 */
    val jokaShimo: VoiceClip?
}
