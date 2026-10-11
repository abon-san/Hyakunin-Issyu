package io.github.abonsan.hyakunin.game

import io.github.abonsan.hyakunin.data.Poem
import kotlin.math.roundToLong

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

/** 前置きで読んでいるもの。 */
enum class LeadInKind {
    /** 序歌（最初の札の前だけ）。 */
    JOKA,

    /** 直前に読んだ歌の下の句（2枚目以降の前）。 */
    PREVIOUS_SHIMO,
}

/** 前置きで読んでいる句。 */
enum class LeadInVerse { KAMI, SHIMO }

/**
 * 競技かるた式の前置き（[GameConfig.readingStyle] が [ReadingStyle.COMPETITIVE] のときだけ）。
 * 待ち（自動のカウントダウン、またはタップの「読み始める」）が終わってから、札の上の句を読み始めるまでの間に入る。
 *
 * - 最初の読み: 序歌の上の句 → 間 → 序歌の下の句（余韻つき）→ 序歌の下の句をもう一度（余韻つき）→ 間合い
 * - 2枚目以降: 直前に読んだ歌の下の句（余韻つき。空札でも、取られた札でも）→ 間合い
 *
 * 前置きの間は、[MatchPhase] は [MatchPhase.Waiting] のまま（[MatchUiState.leadIn] が null でない間）。
 * 札を取る操作・お手つきは無視され、CPU も動かない。[MatchActions.onStartReading] も無視される。
 * 一時停止は効く。[MatchUiState.waitRemainingMillis] は null、[MatchUiState.isMemorizing] は false。
 */
data class LeadInState(
    val kind: LeadInKind,
    /** 読んでいる歌。[LeadInKind.JOKA] では [io.github.abonsan.hyakunin.data.Joka.poem]（歌番号 0）。 */
    val poem: Poem,
    /** 今読んでいる句。[LeadInKind.PREVIOUS_SHIMO] では常に [LeadInVerse.SHIMO]。 */
    val verse: LeadInVerse,
    /** 同じ句を何回目に読んでいるか（1始まり）。序歌の下の句は 2 回読むので 1 → 2。それ以外は 1。 */
    val pass: Int,
    /** 今読んでいる句で表示済みの仮名の数（0..[verseKanaCount]）。句と句の間（間）は前の句が表示し終えたまま。 */
    val revealedKana: Int,
    /** 今読んでいる句の仮名の数。 */
    val verseKanaCount: Int,
    /** 前置きが終わり、札の上の句が始まるまでの残り時間（ms）。 */
    val remainingMillis: Long,
    /** 前置き全体の長さ（ms）。 */
    val totalMillis: Long,
    /** 最後の句を読み終え（余韻まで含めて）、間合いに入っているか。 */
    val inMa: Boolean,
)

/**
 * 今鳴らすべき音声。再生側は [MatchUiState.engineTimeMillis] に合わせて再生位置を追従させる。
 *
 * - [cueId] が前回と同じなら「同じクリップが鳴り続けている」、違えば「新しいクリップ」。
 *   同じファイルを続けて読む（序歌の下の句の2回目など）ときも別の [cueId] になる。単調に増える
 * - 再生位置は [positionAt]。[startedAtMillis] が [MatchUiState.engineTimeMillis] より先（正でない位置）のときは、
 *   まだ始まっていない次のクリップの予告で、始まる時刻まで待つ（先に読み込んでおける）
 * - 一時停止中は同じ cue のまま [MatchUiState.isPaused] が true になる。再生側が止める
 */
data class AudioCue(
    /** [io.github.abonsan.hyakunin.game.voice.VoiceClip.asset]。 */
    val asset: String,
    /** このクリップの先頭が鳴る（鳴った）エンジンの時刻（ms）。 */
    val startedAtMillis: Long,
    /** 再生速度の倍率。エンジンの 1ms はクリップの [playbackSpeed] ms に当たる。 */
    val playbackSpeed: Float,
    val cueId: Long,
) {
    /** エンジンの時刻 [engineTimeMillis] に鳴っているべきクリップ内の位置（ms）。まだ始まっていなければ負。 */
    fun positionAt(engineTimeMillis: Long): Long = ((engineTimeMillis - startedAtMillis) * playbackSpeed.toDouble()).roundToLong()

    /** [engineTimeMillis] までにクリップの先頭が鳴っているべきか。 */
    fun hasStartedAt(engineTimeMillis: Long): Boolean = engineTimeMillis >= startedAtMillis
}

sealed interface MatchPhase {
    /**
     * 次の札を読むまでの間。自動のカウントダウン、タップの「読み始める」待ち、
     * 競技かるた式の前置き（[MatchUiState.leadIn] が null でないとき）を含む。
     */
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
    /**
     * Waiting 中、次の札を自動で読み始めるまでの残り時間（ms）。
     * [ReadStartMode.TAP] で「読み始める」を待っているとき、および Waiting 以外では null。
     */
    val waitRemainingMillis: Long? = null,
    /** Waiting 中で、まだ1枚も読んでいない（札を覚える時間）なら true。 */
    val isMemorizing: Boolean = false,
    /** 競技かるた式の前置きの最中なら、その状態。それ以外は null。前置き中も [phase] は Waiting。 */
    val leadIn: LeadInState? = null,
    /**
     * 今鳴らすべき音声。何も鳴らさないとき（Waiting のカウントダウン、音声の終わり、next() の後、文字だけのとき）は null。
     * 札を取って Result / Finished になっても、その歌の下の句（余韻まで）は鳴り続ける。
     */
    val audio: AudioCue? = null,
    /**
     * エンジンの時計（ms）。試合の始まりを 0 とし、一時停止中は進まない。再生側が
     * `audio.positionAt(engineTimeMillis)` で再生位置のずれを直す。
     */
    val engineTimeMillis: Long = 0L,
)

interface MatchActions {
    /**
     * 札に触れた。[side] は触れた札がある陣。
     * Reading 中は札を取る操作、Result 中で pendingPlayerSends > 0 のときは自陣の送る札を選ぶ操作になる。
     */
    fun onCardTap(side: Side, poemNo: Int)

    /** 結果表示から次の読みへ進む。 */
    fun onNext()

    /**
     * Waiting 中に、待ち時間を飛ばして（または [ReadStartMode.TAP] で）次の札を読み始める。
     * 競技かるた式の前置きが始まっているとき（[MatchUiState.leadIn] が null でないとき）は無視される。
     */
    fun onStartReading() = Unit

    /** 同じ設定で最初からやり直す。 */
    fun onRestart()

    /** 一時停止・再開（アプリがバックグラウンドに移ったときなど）。 */
    fun setPaused(paused: Boolean)
}
