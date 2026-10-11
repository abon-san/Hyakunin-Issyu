package io.github.abonsan.hyakunin.game

import io.github.abonsan.hyakunin.data.Joka
import io.github.abonsan.hyakunin.data.Poem
import io.github.abonsan.hyakunin.data.Poems
import io.github.abonsan.hyakunin.game.voice.VoiceBank
import io.github.abonsan.hyakunin.game.voice.VoiceClip
import kotlin.math.roundToLong
import kotlin.random.Random

/** CPU の行動予定（読みの開始時に決める）。 */
internal sealed interface CpuPlan {
    /** 行動する時刻（エンジンの経過時間、ms）。 */
    val atMillis: Long

    /** 出札を取る。 */
    data class Take(override val atMillis: Long) : CpuPlan

    /** 出札以外の札（友札）に誤って触れる。 */
    data class Fault(override val atMillis: Long, val poemNo: Int, val cardSide: Side) : CpuPlan
}

/** 鳴らす音声クリップ1つ。時刻はエンジンの経過時間（ms）。[cueId] は試合の中で重ならず、鳴る順に増える。 */
internal class AudioItem(
    val cueId: Long,
    val asset: String,
    val startMillis: Long,
    val endMillis: Long,
)

/** 1回の読みの進行状態。時刻はすべてエンジンの経過時間（ms）。 */
internal class ReadSession(
    val poem: Poem,
    /** 何枚目の読みか（1始まり）。 */
    val readNumber: Int,
    /** 出札がある陣。空札なら null。 */
    val fieldSide: Side?,
    /** 読み開始時点の決まり字の仮名の数。 */
    val kimarijiLength: Int,
    val startMillis: Long,
    /** n 番目（0始まりの添字 n-1）の仮名が表示される時刻。上の句→下の句の通し。 */
    val kanaAt: LongArray,
    /** 上の句の仮名の数。 */
    val kamiCount: Int,
    /** 音声で読むときの、上の句・下の句のクリップ（鳴る順）。文字だけなら空。 */
    val audio: List<AudioItem> = emptyList(),
) {
    val isEmpty: Boolean get() = fieldSide == null
    val totalCount: Int get() = kanaAt.size

    /** 音声で読むか（この歌の音声があるか）。 */
    val hasVoice: Boolean get() = audio.isNotEmpty()

    /** 上の句を表示し終えた時刻。音声では、上の句の最後の仮名が鳴る時刻。 */
    val kamiEnd: Long get() = if (kamiCount > 0) kanaAt[kamiCount - 1] else startMillis

    /** 下の句まで表示し終えた時刻。音声では、下の句の最後の仮名が鳴る時刻（余韻の前）。 */
    val shimoEnd: Long get() = if (kanaAt.isNotEmpty()) kanaAt[kanaAt.size - 1] else startMillis

    /** 音声の最後（下の句のクリップの終わり。余韻を含む）の時刻。文字だけなら [shimoEnd]。 */
    val audioEnd: Long get() = audio.lastOrNull()?.endMillis ?: shimoEnd

    var cpuPlan: CpuPlan? = null
    var playerLocked = false
    var cpuLocked = false

    /** 両者がロックアウトした時刻。 */
    var bothLockedAt = 0L
    val faults = mutableSetOf<Side>()
    val safeMisses = mutableSetOf<Side>()

    /** 読みが終わった時点で表示済みだった仮名の数。読み上げ中は null。 */
    var frozenRevealed: Int? = null

    /** 表示される仮名の数の上限。文字だけの空札は上の句までで終わるので、下の句は表示しない。音声の空札は下の句まで読む。 */
    val revealableCount: Int get() = if (isEmpty && !hasVoice) kamiCount else kanaAt.size

    /** 時刻 [t] までに表示される仮名の数。 */
    fun revealedAt(t: Long): Int {
        var n = 0
        while (n < revealableCount && kanaAt[n] <= t) n++
        return n
    }
}

/** 前置きの句1つ。時刻はエンジンの経過時間（ms）。 */
private class LeadInSegment(
    val kind: LeadInKind,
    val poem: Poem,
    val verse: LeadInVerse,
    val pass: Int,
    /** この句を読み始める時刻（音声ではクリップの先頭。文字だけでは最初の仮名の表示間隔の手前）。 */
    val startMillis: Long,
    /** 各仮名が表示される時刻。 */
    val kanaAt: LongArray,
)

/** 前置きの全体。 */
private class LeadInPlan(
    val startMillis: Long,
    val segments: List<LeadInSegment>,
    /** 最後の句を読み終え、間合いに入る時刻。 */
    val maStartMillis: Long,
    /** 間合いが終わり、上の句が始まる時刻。 */
    val endMillis: Long,
    val audio: List<AudioItem>,
)

/** 1句の時刻表。 */
private class VerseTimeline(
    val kanaAt: LongArray,
    /** 句を読み終える時刻（余韻を含む）。 */
    val endMillis: Long,
    /** 次の句を読み始める時刻（句の間を含む）。 */
    val nextStartMillis: Long,
    val audio: AudioItem?,
)

/** 1首の読みの時刻表。 */
private class ReadTimeline(
    val poemNo: Int,
    val startMillis: Long,
    val kanaAt: LongArray,
    val kamiCount: Int,
    val audio: List<AudioItem>,
)

/**
 * 対戦の進行（純粋な Kotlin）。時間は [advanceTo] / [advanceBy] で外から与える。
 * 乱数は [Random] を注入するので、シードを固定すれば決定的に動く。スレッド安全ではない。
 *
 * ### 読み上げ音声と前置き
 * [ReadTiming.voice] があり [GameConfig.voiceEnabled] なら、各仮名の表示時刻（[ReadSession.kanaAt]）を音声の時刻表から作る。
 * CPU の予定は [ReadSession.kanaAt] から作るので、表示も CPU も音声に合う。音声がない歌は文字だけの時刻表に戻る。
 *
 * [GameConfig.readingStyle] が [ReadingStyle.COMPETITIVE] なら、待ちの後・上の句の前に前置き（[LeadInState]）が入る。
 * 前置きは新しい [MatchPhase] にはせず、Waiting の後半（内部では LEAD_IN）として扱う。札を取れない・CPU が動かない・
 * 一時停止が効く、という点が Waiting と同じで、画面に出す内容だけが違う。こうすると [MatchPhase] を網羅して分岐する既存の UI
 * と [MatchActions] はそのまま動き、前置きの表示は [MatchUiState.leadIn] を見て足せる。
 *
 * 音声は、前置き・上の句・下の句のクリップを鳴る順に並べて [snapshot] の `audio` に出す。札が取られて Result になっても、
 * その歌の下の句（余韻まで）は鳴り続ける。[next] で Waiting に進む・新しいエンジンになるときは止まる。
 */
internal class MatchEngine private constructor(
    val config: GameConfig,
    private val random: Random,
    poems: List<Poem>,
    private val timing: ReadTiming,
    private val profile: CpuProfile,
    playerNos: List<Int>,
    cpuNos: List<Int>,
    /** 読む順の歌番号。場にない歌番号は空札になる。 */
    val readOrder: List<Int>,
) {
    private enum class Phase { WAITING, LEAD_IN, READING, RESULT, FINISHED }

    private class Outcome(val winner: Side?)

    /** 使う音声。[GameConfig.voiceEnabled] が false なら使わない。 */
    private val voice: VoiceBank? = if (config.voiceEnabled) timing.voice else null

    /** 競技かるた式の前置きを入れるか。音声があるとき、または [ReadTiming.textLeadIn] のとき。 */
    private val leadInEnabled: Boolean =
        config.readingStyle == ReadingStyle.COMPETITIVE && (voice != null || timing.textLeadIn)

    private val poemByNo: Map<Int, Poem> = poems.associateBy { it.no }
    private val normalizedKami: Map<Int, String> = poems.associate { it.no to normalizeYomi(it.kamiYomi) }

    /** まだ読まれていない歌（決まり字の判定に使う。場にあるか空札かは問わない）。 */
    private val unreadNos: MutableSet<Int> = poems.mapTo(mutableSetOf()) { it.no }

    private val playerCards: MutableList<FieldCard> = playerNos.map(::fieldCard).toMutableList()
    private val cpuCards: MutableList<FieldCard> = cpuNos.map(::fieldCard).toMutableList()

    /** 経過時間（ms）。 */
    var nowMillis: Long = 0L
        private set

    private var phase = Phase.WAITING
    private var phaseStartedAt = 0L
    private var nextReadIndex = 0

    /** 現在（または直前）の読み。最初の読みの前は null。 */
    var session: ReadSession? = null
        private set

    private var lastResult: ReadResult? = null
    private var pendingPlayerSends = 0
    private val pendingReasons = mutableListOf<Transfer.Reason>()
    private var outcome: Outcome? = null
    private var autoAdvance = false

    /** 前置きの最中（LEAD_IN）の計画。 */
    private var leadIn: LeadInPlan? = null

    /** 前置きの間に、次の読みの時刻表を先に作っておく（上の句の音声を前置きのうちに予告するため）。 */
    private var pendingTimeline: ReadTimeline? = null

    /** 今の音声の並び（前置き・上の句・下の句）。止めるときは空にする。 */
    private var audioItems: List<AudioItem> = emptyList()
    private var cueCounter = 0L

    /** これまでに割り当てた [AudioCue.cueId] の数。エンジンを作り直しても cueId が増え続けるように ViewModel が使う。 */
    val cueIdsIssued: Long get() = cueCounter

    private var playerTaken = 0
    private var cpuTaken = 0
    private var playerFaults = 0
    private var cpuFaults = 0
    private var readsDone = 0

    val totalReads: Int get() = readOrder.size

    // ---- 時間の進行 ----

    fun advanceBy(deltaMillis: Long) = advanceTo(nowMillis + deltaMillis)

    /** 経過時間を [timeMillis] まで進める。途中にある出来事は発生した時刻の順にすべて処理する。 */
    fun advanceTo(timeMillis: Long) {
        while (true) {
            val at = nextEventTime() ?: break
            if (at > timeMillis) break
            nowMillis = maxOf(nowMillis, at)
            fireEvent()
        }
        nowMillis = maxOf(nowMillis, timeMillis)
    }

    private fun nextEventTime(): Long? = when (phase) {
        Phase.WAITING -> waitMillis()?.let { phaseStartedAt + it }
        Phase.LEAD_IN -> leadIn!!.endMillis
        Phase.READING -> {
            val s = session!!
            listOfNotNull(s.cpuPlan?.atMillis, readEndTime(s)).minOrNull()
        }
        Phase.RESULT -> if (autoAdvance) phaseStartedAt + timing.resultAutoMillis else null
        Phase.FINISHED -> null
    }

    /**
     * Waiting の長さ（ms）。最初の読みの前は [GameConfig.memorizeTime]、2枚目以降は [GameConfig.readInterval]。
     * [ReadStartMode.TAP] では自動では終わらないので null。
     */
    private fun waitMillis(): Long? = when (config.readStartMode) {
        ReadStartMode.AUTO -> if (isFirstWait) config.memorizeTime.millis else config.readInterval.millis
        ReadStartMode.TAP -> null
    }

    /** まだ1枚も読んでいない（札を覚える時間の）Waiting か。 */
    private val isFirstWait: Boolean get() = phase == Phase.WAITING && nextReadIndex == 0

    /** 誰も取らずに読みが終わる時刻。まだ決まっていなければ null。 */
    private fun readEndTime(s: ReadSession): Long? = when {
        // 音声の空札は、下の句を余韻まで読み終えたら終わり
        s.isEmpty && s.hasVoice -> s.audioEnd
        s.isEmpty -> s.kamiEnd + timing.emptyTailMillis
        s.playerLocked && s.cpuLocked -> maxOf(s.shimoEnd + timing.noTakerMillis, s.bothLockedAt)
        else -> null
    }

    private fun fireEvent() {
        when (phase) {
            Phase.WAITING -> beginAfterWait()
            Phase.LEAD_IN -> startRead()
            Phase.READING -> fireReadingEvent()
            Phase.RESULT -> {
                // 空札の結果表示が終わったので自動で次へ
                phase = Phase.WAITING
                phaseStartedAt = nowMillis
                autoAdvance = false
                audioItems = emptyList()
            }
            Phase.FINISHED -> Unit
        }
    }

    private fun fireReadingEvent() {
        val s = session!!
        val plan = s.cpuPlan
        val end = readEndTime(s)
        if (plan != null && (end == null || plan.atMillis <= end)) {
            s.cpuPlan = null
            when (plan) {
                is CpuPlan.Take -> if (!s.cpuLocked) take(Side.CPU)
                is CpuPlan.Fault -> if (!s.cpuLocked) touchWrong(Side.CPU, plan.cardSide)
            }
        } else {
            finishRead(takenBy = null, takenFrom = null)
        }
    }

    // ---- 読みの開始 ----

    /**
     * Waiting 中に、残りの待ちを飛ばして（または [ReadStartMode.TAP] で）直ちに次の札を読み始める。
     * 前置きが入るときは前置きから始まる。Waiting 以外（前置きの最中を含む）では何もしない。
     */
    fun startReading() {
        if (phase == Phase.WAITING) beginAfterWait()
    }

    /** 待ちが終わった。前置きを入れるならその開始、入れないなら上の句の開始。 */
    private fun beginAfterWait() {
        if (leadInEnabled && nextReadIndex < readOrder.size) startLeadIn() else startRead()
    }

    // ---- 前置き ----

    private fun startLeadIn() {
        val plan = buildLeadIn(nowMillis)
        // 次の読みの時刻表も先に作る。上の句のクリップを前置きのうちに予告できる
        val timeline = buildReadTimeline(poemByNo.getValue(readOrder[nextReadIndex]), plan.endMillis)
        leadIn = plan
        pendingTimeline = timeline
        audioItems = plan.audio + timeline.audio
        phase = Phase.LEAD_IN
        phaseStartedAt = nowMillis
    }

    /**
     * 前置きの時刻表。最初の読みは序歌（上の句→下の句→下の句）、2枚目以降は直前の歌の下の句。
     * 最後に間合い（[ReadTiming.maMillis]）を置く。
     */
    private fun buildLeadIn(start: Long): LeadInPlan {
        val segments = ArrayList<LeadInSegment>()
        val audio = ArrayList<AudioItem>()

        fun add(
            kind: LeadInKind,
            poem: Poem,
            verse: LeadInVerse,
            pass: Int,
            at: Long,
            clip: VoiceClip?,
            gapAfter: Boolean,
        ): VerseTimeline {
            val yomi = if (verse == LeadInVerse.KAMI) poem.kamiYomi else poem.shimoYomi
            // 上の句は余韻なし、下の句は余韻つき
            val timeline = verseTimeline(yomi, at, clip, withYoin = verse == LeadInVerse.SHIMO, gapAfter = gapAfter)
            segments += LeadInSegment(kind, poem, verse, pass, at, timeline.kanaAt)
            timeline.audio?.let { audio += it }
            return timeline
        }

        val end: Long
        if (nextReadIndex == 0) {
            val joka = Joka.poem
            val kami = add(LeadInKind.JOKA, joka, LeadInVerse.KAMI, 1, start, voice?.jokaKami, gapAfter = true)
            val first = add(LeadInKind.JOKA, joka, LeadInVerse.SHIMO, 1, kami.nextStartMillis, voice?.jokaShimo, gapAfter = false)
            val second = add(LeadInKind.JOKA, joka, LeadInVerse.SHIMO, 2, first.endMillis, voice?.jokaShimo, gapAfter = false)
            end = second.endMillis
        } else {
            val previous = checkNotNull(session).poem
            val clip = voice?.poem(previous.no)?.takeIf { it.fits(previous) }?.shimo
            end = add(LeadInKind.PREVIOUS_SHIMO, previous, LeadInVerse.SHIMO, 1, start, clip, gapAfter = false).endMillis
        }
        return LeadInPlan(
            startMillis = start,
            segments = segments,
            maStartMillis = end,
            endMillis = end + timing.maMillis,
            audio = audio,
        )
    }

    private fun leadInState(): LeadInState? {
        if (phase != Phase.LEAD_IN) return null
        val plan = leadIn ?: return null
        // 句と句の間（間）は、前の句を表示し終えたままにする
        val seg = plan.segments.lastOrNull { it.startMillis <= nowMillis } ?: plan.segments.first()
        var revealed = 0
        while (revealed < seg.kanaAt.size && seg.kanaAt[revealed] <= nowMillis) revealed++
        return LeadInState(
            kind = seg.kind,
            poem = seg.poem,
            verse = seg.verse,
            pass = seg.pass,
            revealedKana = revealed,
            verseKanaCount = seg.kanaAt.size,
            remainingMillis = (plan.endMillis - nowMillis).coerceAtLeast(0L),
            totalMillis = plan.endMillis - plan.startMillis,
            inMa = nowMillis >= plan.maStartMillis,
        )
    }

    // ---- 時刻表 ----

    /** クリップの時間（ms）を再生速度で割って、エンジンの時間にする。 */
    private fun scaled(clipMillis: Long): Long = (clipMillis / timing.playbackSpeed.toDouble()).roundToLong()

    private fun newAudioItem(asset: String, start: Long, end: Long): AudioItem = AudioItem(++cueCounter, asset, start, end)

    /**
     * 1句の時刻表。[clip] があって仮名の数が合えば音声の時刻表、なければ文字だけ（[ReadTiming.charMillis]）。
     * [withYoin] は文字だけのとき、句を読み終える時刻に余韻（[ReadTiming.yoinMillis]）を足すか（音声ではクリップに含まれる）。
     * [gapAfter] は上の句で、次の句（下の句）を読み始める時刻に句の間を足すか。
     */
    private fun verseTimeline(yomi: String, start: Long, clip: VoiceClip?, withYoin: Boolean, gapAfter: Boolean): VerseTimeline {
        if (clip != null && clip.fits(yomi)) {
            val end = start + scaled(clip.durationMs)
            val kanaAt = LongArray(clip.kanaStartMs.size) { start + scaled(clip.kanaStartMs[it].toLong()) }
            val next = if (gapAfter) start + scaled(clip.durationMs + timing.voiceKamiShimoGapMillis) else end
            return VerseTimeline(kanaAt, end, next, newAudioItem(clip.asset, start, end))
        }
        val kanaAt = textKanaTimes(yomi, start)
        val last = kanaAt.lastOrNull() ?: start
        val end = if (withYoin) last + timing.yoinMillis else last
        val next = if (gapAfter) end + timing.kamiShimoGapMillis else end
        return VerseTimeline(kanaAt, end, next, null)
    }

    /** 1首の読みの時刻表。音声があればそれ、なければ文字だけ。 */
    private fun buildReadTimeline(poem: Poem, start: Long): ReadTimeline {
        val pv = voice?.poem(poem.no)?.takeIf { it.fits(poem) }
        val kami = verseTimeline(poem.kamiYomi, start, pv?.kami, withYoin = false, gapAfter = true)
        val shimo = verseTimeline(poem.shimoYomi, kami.nextStartMillis, pv?.shimo, withYoin = false, gapAfter = false)
        return ReadTimeline(
            poemNo = poem.no,
            startMillis = start,
            kanaAt = kami.kanaAt + shimo.kanaAt,
            kamiCount = kami.kanaAt.size,
            audio = listOfNotNull(kami.audio, shimo.audio),
        )
    }

    /**
     * 文字だけで読むときの、各仮名が表示される時刻。[start] から数え、句の区切り（空白）には間を足す。
     * 最初の仮名は [start] の [ReadTiming.charMillis] 後。
     */
    private fun textKanaTimes(yomi: String, start: Long): LongArray {
        val times = ArrayList<Long>()
        var t = start
        var gap = 0L
        var started = false
        for (c in yomi) {
            if (isYomiSpace(c)) {
                if (started) gap += timing.phraseGapMillis
                continue
            }
            t += gap + timing.charMillis
            gap = 0L
            started = true
            times += t
        }
        return times.toLongArray()
    }

    private fun startRead() {
        if (nextReadIndex >= readOrder.size) {
            // 読み札が尽きた（通常は直前の読みの終わりで勝敗が付いている）
            outcome = outcome ?: evaluateOutcome() ?: Outcome(null)
            phase = Phase.FINISHED
            return
        }
        val no = readOrder[nextReadIndex]
        nextReadIndex++
        val poem = poemByNo.getValue(no)

        val kimariji = kimarijiLength(
            normalizedKami.getValue(no),
            unreadNos.filter { it != no }.map { normalizedKami.getValue(it) },
        )
        unreadNos.remove(no)

        // 前の送り札の強調表示を戻す
        for (cards in listOf(playerCards, cpuCards)) {
            for (i in cards.indices) if (cards[i].isNew) cards[i] = cards[i].copy(isNew = false)
        }

        // 前置きの間に作っておいた時刻表（音声の cueId を予告したもの）を使う。なければここで作る
        val timeline = pendingTimeline?.takeIf { it.poemNo == no && it.startMillis == nowMillis }
            ?: buildReadTimeline(poem, nowMillis)
        pendingTimeline = null
        leadIn = null
        audioItems = timeline.audio

        val s = ReadSession(
            poem = poem,
            readNumber = nextReadIndex,
            fieldSide = sideOf(no),
            kimarijiLength = kimariji,
            startMillis = nowMillis,
            kanaAt = timeline.kanaAt,
            kamiCount = timeline.kamiCount,
            audio = timeline.audio,
        )
        s.cpuPlan = planCpu(s)
        session = s
        phase = Phase.READING
        phaseStartedAt = nowMillis
    }

    // ---- CPU ----

    private fun planCpu(s: ReadSession): CpuPlan? {
        val friend = if (profile.faultProbability > 0.0 && random.nextDouble() < profile.faultProbability) {
            pickFriendCard(s)
        } else {
            null
        }
        val reaction = random.nextLong(profile.reactionMinMillis, profile.reactionMaxMillis + 1)
        if (friend != null) {
            val (card, side, common) = friend
            val at = s.kanaAt[common - 1] + reaction / 2
            return CpuPlan.Fault(at, card.poemNo, side)
        }
        if (s.isEmpty || s.totalCount == 0) return null
        val kimarijiAt = s.kanaAt[s.kimarijiLength.coerceIn(1, s.totalCount) - 1]
        return CpuPlan.Take(minOf(kimarijiAt + reaction, s.shimoEnd + timing.cpuLateLimitMillis))
    }

    private data class Friend(val card: FieldCard, val side: Side, val common: Int)

    /** 出札以外の場の札のうち、上の句の読みの共通接頭辞が最長（1文字以上）の札。同じ長さならランダム。 */
    private fun pickFriendCard(s: ReadSession): Friend? {
        val target = normalizedKami.getValue(s.poem.no)
        var best = 0
        val candidates = mutableListOf<Friend>()
        for (side in Side.entries) {
            for (card in cardsOf(side)) {
                if (card.poemNo == s.poem.no) continue
                val common = commonPrefixLength(target, normalizedKami.getValue(card.poemNo))
                if (common < 1 || common < best) continue
                if (common > best) {
                    best = common
                    candidates.clear()
                }
                candidates += Friend(card, side, common)
            }
        }
        if (candidates.isEmpty()) return null
        return candidates[random.nextInt(candidates.size)]
    }

    // ---- 札に触れる ----

    /** 札に触れた。Reading 中は取る操作、Result 中は送る札を選ぶ操作。それ以外は無視する。 */
    fun tapCard(side: Side, poemNo: Int) {
        when (phase) {
            Phase.READING -> playerTap(side, poemNo)
            Phase.RESULT -> chooseSendCard(side, poemNo)
            else -> Unit
        }
    }

    private fun playerTap(side: Side, poemNo: Int) {
        val s = session ?: return
        if (s.playerLocked) return
        if (cardsOf(side).none { it.poemNo == poemNo }) return
        if (poemNo == s.poem.no) take(Side.PLAYER) else touchWrong(Side.PLAYER, side)
    }

    private fun take(taker: Side) {
        val s = session!!
        val from = s.fieldSide ?: return
        if (taker == Side.PLAYER) playerTaken++ else cpuTaken++
        finishRead(takenBy = taker, takenFrom = from)
    }

    /** 出札以外の札 ([cardSide] の陣) に触れた。 */
    private fun touchWrong(actor: Side, cardSide: Side) {
        val s = session!!
        // 競技かるた: 出札が場にあり、触れた札が出札と同じ陣ならお手つきにならない
        val safe = config.ruleMode == RuleMode.COMPETITIVE && s.fieldSide == cardSide
        if (actor == Side.PLAYER) s.playerLocked = true else s.cpuLocked = true
        if (s.playerLocked && s.cpuLocked) s.bothLockedAt = nowMillis
        if (safe) {
            s.safeMisses += actor
        } else {
            s.faults += actor
            if (actor == Side.PLAYER) playerFaults++ else cpuFaults++
        }
    }

    // ---- 読みの終了 ----

    private fun finishRead(takenBy: Side?, takenFrom: Side?) {
        val s = session!!
        s.cpuPlan = null
        s.frozenRevealed = s.revealedAt(nowMillis)
        readsDone++

        // 出札を場から取り除く（取られた場合も、取り手なしの場合も）
        s.fieldSide?.let { side -> cardsOf(side).removeAll { it.poemNo == s.poem.no } }

        val transfers = mutableListOf<Transfer>()
        pendingReasons.clear()
        pendingPlayerSends = 0
        if (config.ruleMode == RuleMode.COMPETITIVE) applyTransfers(s, takenBy, takenFrom, transfers)

        outcome = if (pendingPlayerSends == 0) evaluateOutcome() else null
        autoAdvance = s.isEmpty && s.faults.isEmpty() && outcome == null
        lastResult = ReadResult(
            poem = s.poem,
            isEmptyCard = s.isEmpty,
            kimarijiLength = s.kimarijiLength,
            revealedAtTake = if (takenBy != null) s.frozenRevealed else null,
            takenBy = takenBy,
            takenFrom = takenFrom,
            faults = s.faults.toSet(),
            safeMisses = s.safeMisses.toSet(),
            transfers = transfers,
            message = buildResultMessage(
                isEmptyCard = s.isEmpty,
                takenBy = takenBy,
                takenFrom = takenFrom,
                faults = s.faults,
                safeMisses = s.safeMisses,
                transfers = transfers,
                pendingPlayerSends = pendingPlayerSends,
            ),
        )
        phase = Phase.RESULT
        phaseStartedAt = nowMillis
    }

    /**
     * 競技かるたの送り札。CPU が送る札は即座に移し、プレイヤーが送る札は [pendingPlayerSends] にして選ばせる。
     * 送る札が足りなければある分だけ送る。
     */
    private fun applyTransfers(
        s: ReadSession,
        takenBy: Side?,
        takenFrom: Side?,
        transfers: MutableList<Transfer>,
    ) {
        val sender: Side
        val afterTake: Int
        val penalty: Int
        if (takenBy != null) {
            // 取った側が、(相手の陣の札を取ったら1枚) + (相手がお手つきしていたら1枚) を相手へ送る
            sender = takenBy
            afterTake = if (takenFrom == takenBy.opponent) 1 else 0
            penalty = if (takenBy.opponent in s.faults) 1 else 0
        } else if (s.faults.size == 1) {
            // 空札・取り手なし: 片方だけお手つきなら、もう片方がお手つきした側へ1枚送る。両方なら相殺
            sender = s.faults.first().opponent
            afterTake = 0
            penalty = 1
        } else {
            return
        }
        repeat(afterTake) { sendCard(sender, Transfer.Reason.SEND_AFTER_TAKE, transfers) }
        repeat(penalty) { sendCard(sender, Transfer.Reason.FAULT_PENALTY, transfers) }
    }

    private fun sendCard(sender: Side, reason: Transfer.Reason, transfers: MutableList<Transfer>) {
        if (sender == Side.CPU) {
            if (cpuCards.isEmpty()) return
            val card = cpuCards.removeAt(random.nextInt(cpuCards.size))
            playerCards += card.copy(isNew = true)
            transfers += Transfer(Side.CPU, Side.PLAYER, card.poemNo, reason)
        } else if (pendingPlayerSends < playerCards.size) {
            pendingPlayerSends++
            pendingReasons += reason
        }
    }

    /** Result 中、プレイヤーが送る札を1枚選ぶ。 */
    private fun chooseSendCard(side: Side, poemNo: Int) {
        if (pendingPlayerSends <= 0 || side != Side.PLAYER) return
        val index = playerCards.indexOfFirst { it.poemNo == poemNo }
        if (index < 0) return
        val card = playerCards.removeAt(index)
        cpuCards += card.copy(isNew = true)
        val transfer = Transfer(Side.PLAYER, Side.CPU, card.poemNo, pendingReasons.removeAt(0))
        pendingPlayerSends--
        lastResult = lastResult?.let { it.copy(transfers = it.transfers + transfer) }
        if (pendingPlayerSends == 0) {
            outcome = evaluateOutcome()
            autoAdvance = false
        }
    }

    /** 勝敗が付いていれば返す。付いていなければ null。 */
    private fun evaluateOutcome(): Outcome? {
        val playerEmpty = playerCards.isEmpty()
        val cpuEmpty = cpuCards.isEmpty()
        // 読み札が尽きた（読みの途中でなければ、これ以上続けられない）
        val exhausted = nextReadIndex >= readOrder.size
        return when (config.ruleMode) {
            RuleMode.COMPETITIVE -> when {
                playerEmpty && cpuEmpty -> Outcome(null)
                playerEmpty -> Outcome(Side.PLAYER)
                cpuEmpty -> Outcome(Side.CPU)
                // 残りの札が少ない方の勝ち
                exhausted -> Outcome(winnerBy(playerPoints = cpuCards.size, cpuPoints = playerCards.size))
                else -> null
            }
            RuleMode.SIMPLE -> {
                // 場の札がすべて読まれたら、取った枚数の多い方の勝ち
                if ((playerEmpty && cpuEmpty) || exhausted) {
                    Outcome(winnerBy(playerPoints = playerTaken, cpuPoints = cpuTaken))
                } else {
                    null
                }
            }
        }
    }

    /** 点が大きい方が勝ち。同じなら引き分け（null）。 */
    private fun winnerBy(playerPoints: Int, cpuPoints: Int): Side? = when {
        playerPoints > cpuPoints -> Side.PLAYER
        playerPoints < cpuPoints -> Side.CPU
        else -> null
    }

    /**
     * 結果表示から進む。送る札の選択が残っている間は何もしない。
     * 次の読みへ進む（Waiting になる）と、鳴っていた音声は途中でも止まる。試合が終わる（Finished になる）ときは、
     * 最後の札の下の句を余韻まで読み切るので止めない。
     */
    fun next() {
        if (phase != Phase.RESULT || pendingPlayerSends > 0) return
        if (outcome != null) {
            phase = Phase.FINISHED
        } else {
            phase = Phase.WAITING
            audioItems = emptyList()
        }
        phaseStartedAt = nowMillis
        autoAdvance = false
    }

    // ---- 状態の取り出し ----

    fun snapshot(): MatchUiState {
        val s = session
        return MatchUiState(
            config = config,
            phase = when (phase) {
                // 前置きは Waiting の後半。leadIn で見分ける
                Phase.WAITING, Phase.LEAD_IN -> MatchPhase.Waiting
                Phase.READING -> MatchPhase.Reading
                Phase.RESULT -> MatchPhase.Result
                Phase.FINISHED -> MatchPhase.Finished(outcome?.winner)
            },
            playerCards = playerCards.toList(),
            cpuCards = cpuCards.toList(),
            reading = s?.let {
                ReadingState(
                    poem = it.poem,
                    readNumber = it.readNumber,
                    totalReads = readOrder.size,
                    revealedKana = it.frozenRevealed ?: it.revealedAt(nowMillis),
                    kamiKanaCount = it.kamiCount,
                    totalKanaCount = it.totalCount,
                )
            },
            lastResult = lastResult,
            // ロックアウトは読み上げ中だけの状態。Result 中は送る札を選ぶため false にする
            playerLockedOut = phase == Phase.READING && s?.playerLocked == true,
            pendingPlayerSends = pendingPlayerSends,
            playerTakenCount = playerTaken,
            cpuTakenCount = cpuTaken,
            playerFaultCount = playerFaults,
            cpuFaultCount = cpuFaults,
            readsDone = readsDone,
            totalReads = readOrder.size,
            isPaused = false,
            waitRemainingMillis = waitRemainingMillis(),
            isMemorizing = isFirstWait,
            leadIn = leadInState(),
            audio = currentCue(),
            engineTimeMillis = nowMillis,
        )
    }

    /**
     * 今鳴らすべき音声。並びのうち、まだ鳴り終えていない最初のクリップ（鳴っている最中か、次に鳴るもの）。
     * 次に鳴るものは始まる時刻が先なので、再生側は始まるまで待てる。全部鳴り終えたら null。
     */
    private fun currentCue(): AudioCue? {
        val item = audioItems.firstOrNull { it.endMillis > nowMillis } ?: return null
        return AudioCue(item.asset, item.startMillis, timing.playbackSpeed, item.cueId)
    }

    /** AUTO の Waiting 中の残り時間（ms）。それ以外は null。 */
    private fun waitRemainingMillis(): Long? {
        if (phase != Phase.WAITING) return null
        val wait = waitMillis() ?: return null
        return (phaseStartedAt + wait - nowMillis).coerceAtLeast(0L)
    }

    // ---- 補助 ----

    private fun fieldCard(no: Int) = FieldCard(no, poemByNo.getValue(no).shimoKana)

    private fun cardsOf(side: Side): MutableList<FieldCard> = if (side == Side.PLAYER) playerCards else cpuCards

    private fun sideOf(no: Int): Side? = when {
        playerCards.any { it.poemNo == no } -> Side.PLAYER
        cpuCards.any { it.poemNo == no } -> Side.CPU
        else -> null
    }

    companion object {
        /**
         * 通常の準備。100首（[poems]）をシャッフルし、先頭から自陣・敵陣に [GameConfig.CARDS_PER_SIDE] 枚ずつ、
         * 次の [GameConfig.emptyCardCount] 枚を空札とする。読み札は場の札と空札をシャッフルした順。
         * [poems] が少ないときは、置ける範囲に枚数を減らす。
         */
        fun create(
            config: GameConfig,
            random: Random,
            poems: List<Poem> = Poems.all,
            timing: ReadTiming = ReadTiming.of(config.readingSpeed),
            profile: CpuProfile = CpuProfile.of(config.cpuLevel),
        ): MatchEngine {
            val shuffled = poems.map { it.no }.shuffled(random)
            val perSide = minOf(GameConfig.CARDS_PER_SIDE, shuffled.size / 2)
            val emptyCount = minOf(config.emptyCardCount, shuffled.size - 2 * perSide)
            val player = shuffled.take(perSide)
            val cpu = shuffled.drop(perSide).take(perSide)
            val empty = shuffled.drop(2 * perSide).take(emptyCount)
            val order = (player + cpu + empty).shuffled(random)
            return MatchEngine(config, random, poems, timing, profile, player, cpu, order)
        }

        /** テスト用。配置と読み順を直接指定する。[readOrder] のうち場にない歌が空札になる。 */
        fun withLayout(
            config: GameConfig,
            random: Random,
            poems: List<Poem>,
            playerNos: List<Int>,
            cpuNos: List<Int>,
            readOrder: List<Int>,
            timing: ReadTiming = ReadTiming.of(config.readingSpeed),
            profile: CpuProfile = CpuProfile.of(config.cpuLevel),
        ): MatchEngine = MatchEngine(config, random, poems, timing, profile, playerNos, cpuNos, readOrder)
    }
}
