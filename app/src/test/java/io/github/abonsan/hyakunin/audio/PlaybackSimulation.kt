package io.github.abonsan.hyakunin.audio

import androidx.lifecycle.viewModelScope
import io.github.abonsan.hyakunin.game.MatchPhase
import io.github.abonsan.hyakunin.game.MatchViewModel
import io.github.abonsan.hyakunin.game.SLOW_CPU
import io.github.abonsan.hyakunin.game.SYNTHETIC_POEMS
import io.github.abonsan.hyakunin.game.layoutEngine
import io.github.abonsan.hyakunin.game.voice.FAKE_JOKA_KAMI
import io.github.abonsan.hyakunin.game.voice.FAKE_JOKA_SHIMO
import io.github.abonsan.hyakunin.game.voice.fakePoemVoice
import io.github.abonsan.hyakunin.game.voiceTiming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import kotlin.random.Random

// 対戦（MatchViewModel と MatchEngine）と再生側（VoiceCueFollower）を、仮想の時計と ExoPlayer に似せた偽の再生器で
// 一緒に動かし、再生器への命令（位置の移動・読み込み・停止・音量など）と、聞こえる音と表示のずれ、聞こえる音量を数える。
// 端末の遅れやメインスレッドの混み具合を変えて、音が途切れる・揺れる仕組みを再現する。

/**
 * 偽の再生器の振る舞い（ms）。ExoPlayer に似せる: 読み込み（prepare から READY）、鳴らしてから音が出始めるまで、
 * 位置を移した後（ExoPlayer は AudioTrack を作り直す）の READY までと音が出始めるまで。範囲は試合ごとの乱数で選ぶ。
 *
 * @property reportedOffset 報告する位置と、聞こえている位置の差（正なら先の位置を報告する）。鳴っている間だけ
 */
internal data class SimDevice(
    val prepare: IntRange = 0..0,
    val startLatency: IntRange = 0..0,
    val seekBuffer: IntRange = 0..0,
    val seekLatency: IntRange = 0..0,
    val reportedOffset: Long = 0L,
)

/**
 * メインスレッドの刻みの揺れ。ViewModel の刻みは 20ms ごとに予定され、実際にはそれより遅れて動く。
 *
 * @property jitter 刻みごとに増える遅れ（実際の間隔は 20ms + これ）
 * @property shortTickProbability 間隔が 19ms と測れる（ms への切り捨て）確率
 * @property stallEvery この間隔（仮想時間）ごとに、メインスレッドが [stall] ms 止まる（GC・重い描画）
 * @property collectorDelay 対戦の状態が出てから、再生側（Compose の collect）が動くまでの遅れ
 */
internal data class SimTicks(
    val jitter: IntRange = 0..0,
    val shortTickProbability: Double = 0.0,
    val stallEvery: Long = 0L,
    val stall: Long = 0L,
    val collectorDelay: IntRange = 0..0,
)

/**
 * 試合のはじめ（序歌と 3 枚の読み）を鳴らした間の記録。時間は ms。
 *
 * @property lateStartSeeks 鳴らし始めと同時に位置を移した回数（遅れて始めた）
 * @property driftSeeks 鳴っている最中に位置を移した回数（ずれの補正）
 * @property maxDriftSeekJump 鳴っている最中の位置の移動で、聞こえる位置が飛んだ量の最大
 * @property midClipCuts 鳴っている途中（終わりの 200ms より前）で止めた・読み直した回数
 * @property onsetSkips 先頭からではなく途中から鳴らし始めたクリップの数（出だしの音が欠ける）
 * @property earlyStarts クリップの始まりの時刻より前に鳴らし始めた回数
 * @property maxOutputs 同時に音を出していた再生器の数の最大
 * @property strayOutputs 今の cue を受け持っていない再生器が音を出していた刻みの数（先に読み込んだ再生器が鳴った・前の再生器が残った）
 * @property fadesWhileSounding 音を出している再生器を小さくしながら止めた回数
 * @property maxDisplayAhead 表示（エンジンの時刻）が、聞こえている音より先に進んでいた量の最大
 * @property maxDisplayBehind 表示が、聞こえている音より遅れていた量の最大
 * @property volumeCalls 再生器に利用者の音量を渡した回数（すべての再生器の合計）
 * @property volumeCallsWhileSounding 音を出している再生器があるときに、利用者の音量を渡した回数
 * @property steadyGainMismatches ふつうに鳴っている（小さくして止めている最中でない）再生器の音量が、利用者の音量と違った刻みの数
 * @property maxHeardGain 聞こえた音量の最大（小さくして止める最中も含む）。音が出なければ 0
 */
internal class PlaybackRecord(
    val seeks: Int,
    val lateStartSeeks: Int,
    val driftSeeks: Int,
    val maxDriftSeekJump: Long,
    val prepares: Int,
    val cues: Int,
    val midClipCuts: Int,
    val onsetSkips: Int,
    val maxOnsetSkip: Long,
    val earlyStarts: Int,
    val stops: Int,
    val pauses: Int,
    val speedCalls: Int,
    val maxOutputs: Int,
    val strayOutputs: Int,
    val fadesWhileSounding: Int,
    val maxDisplayAhead: Long,
    val maxDisplayBehind: Long,
    val reads: Int,
    val volumeCalls: Int,
    val volumeCallsWhileSounding: Int,
    val steadyGainMismatches: Int,
    val maxHeardGain: Float,
) {
    override fun toString(): String =
        "seeks=$seeks (late=$lateStartSeeks drift=$driftSeeks maxJump=$maxDriftSeekJump) prepares=$prepares cues=$cues " +
            "cuts=$midClipCuts onsetSkips=$onsetSkips (max=$maxOnsetSkip) early=$earlyStarts stops=$stops pauses=$pauses " +
            "speeds=$speedCalls outputs=$maxOutputs stray=$strayOutputs fades=$fadesWhileSounding displayAhead=$maxDisplayAhead displayBehind=$maxDisplayBehind reads=$reads " +
            "volumes=$volumeCalls (sounding=$volumeCallsWhileSounding) gainMismatches=$steadyGainMismatches maxGain=$maxHeardGain"
}

/** 偽の voice バンク（fakeVoiceBank）のクリップの長さ。 */
private val CLIP_DURATIONS: Map<String, Long> = buildMap {
    put(FAKE_JOKA_KAMI.asset, FAKE_JOKA_KAMI.durationMs)
    put(FAKE_JOKA_SHIMO.asset, FAKE_JOKA_SHIMO.durationMs)
    for (poem in SYNTHETIC_POEMS) {
        val voice = fakePoemVoice(poem)
        put(voice.kami.asset, voice.kami.durationMs)
        put(voice.shimo.asset, voice.shimo.durationMs)
    }
}

/**
 * ExoPlayer に似せた偽の再生器。時刻 [clock] は実時間（ms）。位置を移すと、音が出るまでまた遅れる（AudioTrack の作り直し）。
 * 報告する位置（[positionMillis]）は ExoPlayer と同じく聞こえている位置で、[SimDevice.reportedOffset] だけずらせる。
 * 聞こえる音量（[heardGain]）は ExoVoicePlayer と同じく、利用者の音量 × 小さくして止めるときの音量（[fadeOutGain]）。
 */
internal class SimDeck(
    private val clock: () -> Long,
    private val device: SimDevice,
    private val random: Random,
) : VoicePlayerPort {
    val commands = mutableListOf<String>()
    var asset: String? = null
        private set

    /** 読み込んでから最初に鳴らしたときの位置。鳴らしていなければ null。 */
    var firstPlayPosition: Long? = null
        private set

    private var idle = true
    private var readyAt = 0L
    private var playWhenReady = false
    private var base = 0L
    private var presentFrom: Long? = null
    private var speed = 1f
    private var fadeEnd: Long? = null
    private var fadeStart = 0L
    private var fadeLength = 0L

    /** 渡された利用者の音量。作ったばかりの再生器は 1。 */
    var userVolume = 1f
        private set

    private val now: Long get() = clock()
    private val duration: Long get() = asset?.let(CLIP_DURATIONS::getValue) ?: 0L

    private fun pick(range: IntRange): Long =
        if (range.first == range.last) range.first.toLong() else random.nextInt(range.first, range.last + 1).toLong()

    private fun settleFade() {
        val end = fadeEnd ?: return
        if (now >= end) {
            fadeEnd = null
            idle = true
            playWhenReady = false
            presentFrom = null
        }
    }

    /** いま聞こえている（鳴っていれば）位置。 */
    val heardMillis: Long
        get() {
            settleFade()
            if (idle) return 0L
            val from = presentFrom ?: return base
            if (now < from) return base
            return (base + ((now - from) * speed.toDouble()).roundToLong()).coerceAtMost(duration)
        }

    /** いま音を出しているか（鳴らしていて、音が出始めていて、まだ終わっていない）。 */
    val presenting: Boolean
        get() {
            settleFade()
            val from = presentFrom ?: return false
            return !idle && now >= from && heardMillis < duration
        }

    /** 鳴らし終えるまでの残り（クリップの ms）。 */
    val remainingMillis: Long get() = duration - heardMillis

    /** 小さくして止めている最中か。 */
    val fading: Boolean
        get() {
            settleFade()
            return fadeEnd != null
        }

    /** いま聞こえている音量（音を出していなければ 0）。 */
    val heardGain: Float
        get() {
            if (!presenting) return 0f
            val fade = if (fading) fadeOutGain(now - fadeStart, fadeLength) else 1f
            return userVolume * fade
        }

    private val ended: Boolean get() = !idle && presentFrom != null && heardMillis >= duration

    override fun prepare(asset: String) {
        commands += "prepare"
        this.asset = asset
        idle = false
        fadeEnd = null
        base = 0L
        presentFrom = null
        playWhenReady = false
        firstPlayPosition = null
        readyAt = now + pick(device.prepare)
    }

    override fun play() {
        commands += "play"
        settleFade()
        if (playWhenReady || idle) return
        if (firstPlayPosition == null) firstPlayPosition = base
        playWhenReady = true
        presentFrom = max(now, readyAt) + pick(device.startLatency)
    }

    override fun pause() {
        commands += "pause"
        base = heardMillis
        presentFrom = null
        playWhenReady = false
    }

    override fun seekTo(positionMillis: Long) {
        commands += "seek"
        base = positionMillis.coerceIn(0L, duration)
        readyAt = now + pick(device.seekBuffer)
        presentFrom = if (playWhenReady) readyAt + pick(device.seekLatency) else null
    }

    override fun setSpeed(speed: Float) {
        commands += "speed"
        this.speed = speed
    }

    override fun setVolume(volume: Float) {
        commands += "volume"
        userVolume = volume
    }

    override fun stop(fadeOutMillis: Long) {
        commands += if (presenting && fadeOutMillis > 0L) "stop-fade" else "stop"
        if (isPlaying && fadeOutMillis > 0L) {
            fadeStart = now
            fadeLength = fadeOutMillis
            fadeEnd = now + fadeOutMillis
        } else {
            idle = true
            playWhenReady = false
            presentFrom = null
        }
    }

    override val isReady: Boolean get() = !idle && now >= readyAt && !ended

    override val isPlaying: Boolean get() = playWhenReady && isReady

    override val positionMillis: Long get() = heardMillis + if (presenting) device.reportedOffset else 0L
}

/**
 * 序歌と [reads] 枚の読み（競技かるた式、偽の音声。CPU は下の句の後に取る）を、[device] の再生器と [ticks] の刻みで鳴らす。
 * 読み終えて音声がなくなったら「次へ」を押す。利用者の音量 [volume] は、対戦の初めに（鳴らす前に）渡す。
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun simulatePlayback(
    device: SimDevice = SimDevice(),
    ticks: SimTicks = SimTicks(),
    speed: Float = 1f,
    seed: Int = 1,
    reads: Int = 3,
    volume: Float = 1f,
): PlaybackRecord {
    val scheduler = TestCoroutineScheduler()
    Dispatchers.setMain(StandardTestDispatcher(scheduler))
    try {
        val random = Random(seed)
        // 実時間 = 仮想時間 + 刻みの遅れ（+ 再生側が動くまでの遅れ）
        var lag = 0L
        var collectorLag = 0L
        val realTime = { scheduler.currentTime + lag + collectorLag }
        val decks = List(2) { SimDeck(realTime, device, random) }
        val follower = VoiceCueFollower(decks, wallClock = realTime)
        // 対戦を始めるときに音量を渡す（MatchVoicePlayback と同じ）
        follower.setVolume(volume)
        val order = listOf(1, 6, 2).take(reads)
        val vm = MatchViewModel(
            engineFactory = { layoutEngine(order = order, timing = voiceTiming(playbackSpeed = speed), profile = SLOW_CPU) },
            clock = { scheduler.currentTime + lag },
            tickMillis = 20L,
        )
        // 鳴っている間は、エンジンの時計を聞こえている位置に合わせる
        vm.attachAudioClock(follower)

        val cueIds = mutableSetOf<Long>()
        val deckCue = mutableMapOf<SimDeck, Long?>()
        var lateStartSeeks = 0
        var driftSeeks = 0
        var maxDriftSeekJump = 0L
        var midClipCuts = 0
        var onsetSkips = 0
        var maxOnsetSkip = 0L
        var earlyStarts = 0
        var maxOutputs = 0
        var strayOutputs = 0
        var fadesWhileSounding = 0
        var maxAhead = 0L
        var maxBehind = 0L
        var volumeCallsWhileSounding = 0
        var steadyGainMismatches = 0
        var maxHeardGain = 0f

        vm.viewModelScope.launch {
            vm.uiState.collect { state ->
                val cue = state.audio
                // 表示（エンジンの時刻）と聞こえている音のずれは、再生側が手を出す前（刻みの時点）に測る
                for (d in decks) {
                    if (cue == null || deckCue[d] != cue.cueId || !d.presenting || !cue.hasStartedAt(state.engineTimeMillis)) continue
                    val diff = (cue.positionAt(state.engineTimeMillis) - d.heardMillis) / cue.playbackSpeed.toDouble()
                    maxAhead = max(maxAhead, diff.roundToLong())
                    maxBehind = max(maxBehind, (-diff).roundToLong())
                }
                // 聞こえている音量: ふつうに鳴っている間は利用者の音量のまま、小さくして止めるときもそれを超えない
                for (d in decks) {
                    if (!d.presenting) continue
                    val gain = d.heardGain
                    maxHeardGain = max(maxHeardGain, gain)
                    if (!d.fading && gain != volume) steadyGainMismatches++
                }
                collectorLag = if (ticks.collectorDelay.last > 0) random.nextInt(ticks.collectorDelay.first, ticks.collectorDelay.last + 1).toLong() else 0L
                val before = decks.associateWith { it.commands.size }
                val heardBefore = decks.associateWith { it.heardMillis }
                val presentingBefore = decks.associateWith { it.presenting }
                val remainingBefore = decks.associateWith { it.remainingMillis }

                follower.update(state.audio, state.engineTimeMillis, state.isPaused, state.nextAudio)

                val sounding = presentingBefore.values.any { it }
                for (d in decks) {
                    val issued = d.commands.drop(before.getValue(d))
                    if (issued.isEmpty()) continue
                    if (sounding) volumeCallsWhileSounding += issued.count { it == "volume" }
                    val stopped = "stop" in issued || "stop-fade" in issued
                    if (presentingBefore.getValue(d) && remainingBefore.getValue(d) > 200L && ("prepare" in issued || stopped)) {
                        midClipCuts++
                    }
                    if ("stop-fade" in issued) fadesWhileSounding++
                    if ("seek" in issued) {
                        if ("play" in issued) {
                            lateStartSeeks++
                        } else {
                            driftSeeks++
                            maxDriftSeekJump = max(maxDriftSeekJump, abs(d.heardMillis - heardBefore.getValue(d)))
                        }
                    }
                    if ("prepare" in issued) deckCue[d] = null
                    if ("play" in issued && cue != null) {
                        if (deckCue[d] == null) {
                            val skip = d.firstPlayPosition ?: 0L
                            if (skip > 0L) onsetSkips++
                            maxOnsetSkip = max(maxOnsetSkip, skip)
                        }
                        deckCue[d] = cue.cueId
                        if (!cue.hasStartedAt(state.engineTimeMillis)) earlyStarts++
                    }
                }
                maxOutputs = max(maxOutputs, decks.count { it.presenting })
                if (decks.any { it.presenting && (cue == null || deckCue[it] != cue.cueId) }) strayOutputs++
                cue?.let { cueIds += it.cueId }
                collectorLag = 0L
            }
        }

        var done = 0
        var nextStall = ticks.stallEvery
        while (done < order.size && scheduler.currentTime < 300_000L) {
            lag += if (ticks.jitter.last > 0) random.nextInt(ticks.jitter.first, ticks.jitter.last + 1).toLong() else 0L
            if (ticks.shortTickProbability > 0.0 && random.nextDouble() < ticks.shortTickProbability) lag -= 1L
            if (ticks.stallEvery > 0L && scheduler.currentTime >= nextStall) {
                lag += ticks.stall
                nextStall += ticks.stallEvery
            }
            scheduler.advanceTimeBy(20L)
            scheduler.runCurrent()
            val state = vm.uiState.value
            if (state.phase == MatchPhase.Result && state.audio == null) {
                done++
                vm.onNext()
            }
        }
        vm.viewModelScope.cancel()

        val commands = decks.flatMap { it.commands }
        return PlaybackRecord(
            seeks = commands.count { it == "seek" },
            lateStartSeeks = lateStartSeeks,
            driftSeeks = driftSeeks,
            maxDriftSeekJump = maxDriftSeekJump,
            prepares = commands.count { it == "prepare" },
            cues = cueIds.size,
            midClipCuts = midClipCuts,
            onsetSkips = onsetSkips,
            maxOnsetSkip = maxOnsetSkip,
            earlyStarts = earlyStarts,
            stops = commands.count { it == "stop" || it == "stop-fade" },
            pauses = commands.count { it == "pause" },
            speedCalls = commands.count { it == "speed" },
            maxOutputs = maxOutputs,
            strayOutputs = strayOutputs,
            fadesWhileSounding = fadesWhileSounding,
            maxDisplayAhead = maxAhead,
            maxDisplayBehind = maxBehind,
            reads = done,
            volumeCalls = commands.count { it == "volume" },
            volumeCallsWhileSounding = volumeCallsWhileSounding,
            steadyGainMismatches = steadyGainMismatches,
            maxHeardGain = maxHeardGain,
        )
    } finally {
        Dispatchers.resetMain()
    }
}

/** 端末とメインスレッドの組み合わせ。 */
internal object SimScenarios {
    val IDEAL = SimDevice() to SimTicks()

    /** 刻みの揺れ（0〜25ms）と、再生側が動くまでの遅れ（0〜16ms）。 */
    val JITTER = SimDevice() to SimTicks(jitter = 0..25, collectorDelay = 0..16)

    /** 3 割の刻みが 19ms と測れる（ViewModel は 20ms に切り上げる）。 */
    val SHORT_TICKS = SimDevice() to SimTicks(shortTickProbability = 0.3)

    /** 3 秒ごとにメインスレッドが 250ms 止まる（ViewModel は 1 刻み 100ms までしか進めない）。 */
    val STALLS = SimDevice() to SimTicks(stallEvery = 3_000L, stall = 250L)

    /** 再生器が、聞こえている位置より 150ms 先を報告する。 */
    val REPORTED_AHEAD = SimDevice(reportedOffset = 150L) to SimTicks()

    /** 鳴らしてから音が出るまで、位置を移してから音が出るまで、どちらも 200ms（Bluetooth くらい）。 */
    val OUTPUT_LATENCY = SimDevice(startLatency = 200..200, seekLatency = 200..200) to SimTicks()

    /** 鳴らし始めの遅れが毎回 40〜250ms でばらつく。 */
    val VARYING_LATENCY = SimDevice(startLatency = 40..250, seekLatency = 40..250) to SimTicks()

    /** 読み込みに 80ms かかる。 */
    val SLOW_PREPARE = SimDevice(prepare = 80..80) to SimTicks()

    /** スマートフォンのスピーカーくらい: 読み込み・鳴り始め・位置の移動の遅れ、刻みの揺れ、ときどき 180ms 止まる。 */
    val REALISTIC = SimDevice(prepare = 40..120, startLatency = 40..110, seekBuffer = 15..40, seekLatency = 30..80) to
        SimTicks(jitter = 0..8, collectorDelay = 0..16, stallEvery = 7_000L, stall = 180L)

    /** [REALISTIC] でメインスレッドがもっと混んでいる（デバッグ版など）。 */
    val BUSY = REALISTIC.first to SimTicks(jitter = 0..15, collectorDelay = 0..19, stallEvery = 4_000L, stall = 220L)
}

/** [scenario] を [seeds] の乱数でそれぞれ鳴らす。 */
internal fun simulateAll(
    scenario: Pair<SimDevice, SimTicks>,
    seeds: IntRange = 1..5,
    speed: Float = 1f,
    volume: Float = 1f,
): List<PlaybackRecord> = seeds.map { simulatePlayback(scenario.first, scenario.second, speed, seed = it, volume = volume) }
