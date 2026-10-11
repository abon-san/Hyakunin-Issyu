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
 * [viewModelScope] で 1 本のループを回し、[tickMillis] ごとに [clock] で測った経過時間をエンジンへ渡す。
 * 一時停止中は経過時間を進めない（このとき [MatchUiState.engineTimeMillis] も止まる。音声は同じ cue のまま、
 * [MatchUiState.isPaused] が true になる）。UI のスレッド（メイン）からだけ呼ぶこと。
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

    private fun tick() {
        val now = clock()
        val elapsed = now - lastClock
        lastClock = now
        if (paused) return
        // 実時間の遅れ（delay のずれ）を取り込む。仮想時間では 1 刻みぶんだけ進む
        engine.advanceBy(elapsed.coerceIn(tickMillis, MAX_STEP_MILLIS))
        publish()
    }

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
        publish()
    }

    override fun setPaused(paused: Boolean) {
        if (this.paused == paused) return
        this.paused = paused
        // 一時停止していた間の時間は進めない
        lastClock = clock()
        publish()
    }

    private companion object {
        const val DEFAULT_TICK_MILLIS = 20L

        /** 1 回の刻みで進める時間の上限。長時間止まった後にいきなり進まないようにする。 */
        const val MAX_STEP_MILLIS = 100L
    }
}

private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
