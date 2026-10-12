package io.github.abonsan.hyakunin.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.abonsan.hyakunin.data.Poem
import io.github.abonsan.hyakunin.data.Poems
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 対戦の進行役。生成と同時に試合を始める。
 *
 * [viewModelScope] で 1 本のループを回し、[tickMillis] ごとにエンジンの時計を進める。
 * 一時停止中は経過時間を進めない（このとき [MatchUiState.engineTimeMillis] も止まる。音声は同じ cue のまま、
 * [MatchUiState.isPaused] が true になる）。UI のスレッド（メイン）からだけ呼ぶこと。
 *
 * ### 時計
 * 音声が鳴っている間は、音声が時計になる: [attachAudioClock] で付けた [AudioClock] の聞こえている位置（再生速度で割る）に
 * エンジンの時刻を合わせる。刻みの遅れ・揺れ（メインスレッドの混み具合）は表示の更新が遅れるだけで、音とのずれにならない。
 * 鳴り始めで音がまだ出ていない間は、エンジンも待つ（表示と CPU が音より先に進まない）。
 * 音声が鳴っていないとき（待ち・文字だけ・クリップの間）は、[clock] で測った経過時間で進める。
 *
 * [MatchUiState.audio] の [AudioCue.cueId] は、やり直し（[onRestart]）をまたいでも増え続ける。
 * [MatchUiState.engineTimeMillis] はやり直しで 0 に戻る。
 */
class MatchViewModel internal constructor(
    private val engineFactory: () -> MatchEngine,
    private val clock: () -> Long,
    private val tickMillis: Long,
) : ViewModel(), MatchActions {

    /**
     * @param poems 100首。決まり字の判定と配札に使う
     * @param timing 読み上げと進行のタイミング。既定は [GameConfig.readingSpeed] に合わせる。
     *   音声で読むときは [ReadTiming.voice] を持たせる（[ReadTiming.of] の `voice`）
     * @param clock 単調増加の時計（ms）。テストでは仮想時間を渡す
     */
    constructor(
        config: GameConfig,
        random: Random = Random.Default,
        poems: List<Poem> = Poems.all,
        timing: ReadTiming = ReadTiming.of(config.readingSpeed),
        clock: () -> Long = ::monotonicMillis,
        tickMillis: Long = DEFAULT_TICK_MILLIS,
    ) : this(
        engineFactory = { MatchEngine.create(config, random, poems, timing) },
        clock = clock,
        tickMillis = tickMillis,
    )

    private var engine: MatchEngine = engineFactory()
    private var paused = false
    private var lastClock = clock()

    /** 作り直す前のエンジンが割り当てた cueId の数。やり直しても [AudioCue.cueId] が増え続けるように足す。 */
    private var cueIdBase = 0L

    /** 鳴っている音声の位置。付いていなければ実時間だけで進める。 */
    private var audioClock: AudioClock? = null

    /** 音が追いつくのを待ってエンジンを止めている実時間（ms）。 */
    private var heldMillis = 0L

    private val _uiState = MutableStateFlow(engine.snapshot())
    val uiState: StateFlow<MatchUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                delay(tickMillis)
                tick()
            }
        }
    }

    /** 鳴っている音声の位置でエンジンの時計を進めるようにする（再生側が付ける）。 */
    fun attachAudioClock(clock: AudioClock) {
        audioClock = clock
        heldMillis = 0L
    }

    /** [attachAudioClock] で付けた [clock] を外す。ほかのものが付いていれば何もしない。 */
    fun detachAudioClock(clock: AudioClock) {
        if (audioClock === clock) audioClock = null
    }

    private fun tick() {
        val now = clock()
        val elapsed = now - lastClock
        lastClock = now
        if (paused) return
        advance(elapsed)
        publish()
    }

    /**
     * エンジンの時計を、実時間で [elapsed] ms たったところまで進める。
     * 音声が鳴っていれば聞こえている位置に合わせる（戻しはしない）。音がエンジンに追いついていない（鳴り始め・音の途切れ）
     * 間は止めて待つが、[MAX_AUDIO_HOLD_MILLIS] より長く動かなければ（再生器が止まったなど）実時間で進める。
     */
    private fun advance(elapsed: Long) {
        val heard = heardEngineTime()
        when {
            heard == null -> {
                heldMillis = 0L
                engine.advanceBy(wallStep(elapsed))
            }
            heard > engine.nowMillis -> {
                heldMillis = 0L
                engine.advanceTo(heard)
            }
            else -> {
                heldMillis += elapsed
                if (heldMillis > MAX_AUDIO_HOLD_MILLIS) engine.advanceBy(wallStep(elapsed))
            }
        }
    }

    /** 今の cue のクリップが鳴っていれば、聞こえている位置に当たるエンジンの時刻。鳴っていなければ null。 */
    private fun heardEngineTime(): Long? {
        val cue = _uiState.value.audio ?: return null
        val position = audioClock?.heardPositionMillis(cue.cueId) ?: return null
        return cue.engineTimeAt(position)
    }

    /** 実時間の遅れ（delay のずれ）を取り込む。仮想時間では 1 刻みぶんだけ進む。 */
    private fun wallStep(elapsed: Long): Long = elapsed.coerceIn(tickMillis, MAX_STEP_MILLIS)

    private fun publish() {
        val snapshot = engine.snapshot()
        _uiState.value = snapshot.copy(
            isPaused = paused,
            audio = snapshot.audio?.let { it.copy(cueId = it.cueId + cueIdBase) },
        )
    }

    override fun onCardTap(side: Side, poemNo: Int) {
        if (paused) return
        engine.tapCard(side, poemNo)
        publish()
    }

    override fun onNext() {
        if (paused) return
        engine.next()
        publish()
    }

    override fun onStartReading() {
        if (paused) return
        engine.startReading()
        publish()
    }

    override fun onRestart() {
        // 音声は止まる（新しいエンジンの audio は null）
        cueIdBase += engine.cueIdsIssued
        engine = engineFactory()
        lastClock = clock()
        heldMillis = 0L
        publish()
    }

    override fun setPaused(paused: Boolean) {
        if (this.paused == paused) return
        this.paused = paused
        // 一時停止していた間の時間は進めない
        lastClock = clock()
        heldMillis = 0L
        publish()
    }

    private companion object {
        const val DEFAULT_TICK_MILLIS = 20L

        /** 1 回の刻みで（実時間で）進める時間の上限。長時間止まった後にいきなり進まないようにする。 */
        const val MAX_STEP_MILLIS = 100L

        /**
         * 音が追いつくのを待ってエンジンを止めておく上限（実時間の ms）。鳴り始めの出力の遅れ（Bluetooth でも 0.3 秒ほど）より長くする。
         * これより長く位置が進まなければ、再生器が止まったとみて実時間で進める。
         */
        const val MAX_AUDIO_HOLD_MILLIS = 500L
    }
}

private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
