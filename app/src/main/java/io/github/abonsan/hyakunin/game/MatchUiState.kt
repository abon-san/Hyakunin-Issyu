package io.github.abonsan.hyakunin.game

import io.github.abonsan.hyakunin.data.Poem

// 対戦画面（UI）と対戦ロジック（MatchViewModel）の間の契約。
// 「仮名の数」は読み（kamiYomi + shimoYomi）を上の句→下の句の順に通して数えた値で、空白は数えない。

/** 場に置かれた取り札。 */
data class FieldCard(
    val poemNo: Int,
    val shimoKana: String,
    /** 直前の送り札で移ってきた札なら true（UIで強調表示する）。 */
    val isNew: Boolean = false,
)

/** 読み上げ中（または直前に読んだ）札の状態。 */
data class ReadingState(
    val poem: Poem,
    /** 何枚目の読みか（1始まり）。 */
    val readNumber: Int,
    /** 読み札の総数（場の札10枚 + 空札）。 */
    val totalReads: Int,
    /** 読み上げ済みの仮名の数。上の句を読み終えると下の句へ続く。 */
    val revealedKana: Int,
    /** 上の句の仮名の数（空白を除く）。 */
    val kamiKanaCount: Int,
    /** 上の句と下の句の仮名の合計。revealedKana がこれに達すると読み終わり。 */
    val totalKanaCount: Int,
)

/** 札の移動（送り札・お手つきの罰）。 */
data class Transfer(
    val from: Side,
    val to: Side,
    val poemNo: Int,
    val reason: Reason,
) {
    enum class Reason { SEND_AFTER_TAKE, FAULT_PENALTY }
}

/** 1回の読みの結果。札を取った後の決まり字（緑）・読み過ぎ（赤）の表示に使う。 */
data class ReadResult(
    val poem: Poem,
    /** 空札（場にない札）だったか。 */
    val isEmptyCard: Boolean,
    /** 読まれた時点の決まり字の仮名の数（既に読まれた札を除いて判定）。 */
    val kimarijiLength: Int,
    /** 札が取られた時点で読み上げ済みだった仮名の数。取られなかった場合は null。 */
    val revealedAtTake: Int?,
    /** 札を取った側。空札・取られなかった場合は null。 */
    val takenBy: Side?,
    /** 取られた札があった陣。 */
    val takenFrom: Side?,
    /** この読みでお手つきした側。 */
    val faults: Set<Side>,
    /** 誤って触れたが同じ陣だったためお手つきにならなかった側（競技かるたルールのみ）。 */
    val safeMisses: Set<Side>,
    /** この読みの結果として行われた（または行われる）札の移動。 */
    val transfers: List<Transfer>,
    /** 結果の説明文（例:「あなたが敵陣の札を取りました」）。 */
    val message: String,
)

sealed interface MatchPhase {
    /** 次の札を読むまでの間。 */
    data object Waiting : MatchPhase

    /** 読み上げ中。札に触れられる。 */
    data object Reading : MatchPhase

    /** 結果表示中。[MatchUiState.pendingPlayerSends] が 0 になると「次へ」で進める。 */
    data object Result : MatchPhase

    /** 試合終了。[winner] が null なら引き分け。 */
    data class Finished(val winner: Side?) : MatchPhase
}

data class MatchUiState(
    val config: GameConfig,
    val phase: MatchPhase,
    /** 自陣（画面下側）の札。 */
    val playerCards: List<FieldCard>,
    /** 敵陣（画面上側）の札。 */
    val cpuCards: List<FieldCard>,
    /** 読み上げ中、または直前に読んだ札。最初の読みの前は null。 */
    val reading: ReadingState?,
    /** 直前の読みの結果。Result / Finished で表示する。 */
    val lastResult: ReadResult?,
    /** この読みではもう札を取れない（誤って触れた後）。 */
    val playerLockedOut: Boolean,
    /** 自陣から送る札をあと何枚選ぶ必要があるか（競技かるたルールで自分が送る場合）。 */
    val pendingPlayerSends: Int,
    val playerTakenCount: Int,
    val cpuTakenCount: Int,
    val playerFaultCount: Int,
    val cpuFaultCount: Int,
    /** 読み終えた札の数。 */
    val readsDone: Int,
    val totalReads: Int,
    val isPaused: Boolean,
)

interface MatchActions {
    /**
     * 札に触れた。[side] は触れた札がある陣。
     * Reading 中は札を取る操作、Result 中で pendingPlayerSends > 0 のときは自陣の送る札を選ぶ操作になる。
     */
    fun onCardTap(side: Side, poemNo: Int)

    /** 結果表示から次の読みへ進む。 */
    fun onNext()

    /** 同じ設定で最初からやり直す。 */
    fun onRestart()

    /** 一時停止・再開（アプリがバックグラウンドに移ったときなど）。 */
    fun setPaused(paused: Boolean)
}
