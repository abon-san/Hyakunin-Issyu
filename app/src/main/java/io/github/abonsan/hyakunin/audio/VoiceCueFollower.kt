package io.github.abonsan.hyakunin.audio

import io.github.abonsan.hyakunin.game.AudioClock
import io.github.abonsan.hyakunin.game.AudioCue
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.NextAudio
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * 再生器（[VoicePlayerPort]、ふつうは2つ）で、エンジンが出す今鳴らすべき音声（[AudioCue]）を鳴らす。純粋な Kotlin。
 * 時刻はエンジンの時計（ms）で、対戦の状態が変わるたびに [update] を呼ぶ（ViewModel の刻みごと、約 20ms）。
 *
 * 鳴っている間は音声が時計になる（[AudioClock]）: MatchViewModel が [heardPositionMillis] を読み、エンジンの時刻を
 * 聞こえている位置に合わせる。だから鳴っている最中に位置を直すことはなく、刻みの遅れ・揺れで音が途切れない。
 *
 * - 次に鳴る見込みのクリップ（[NextAudio]）を、空いている再生器に先に読み込んでおく。その cue が来たら読み直さずに使う。
 *   cue ごとに読み込むのは多くても1回
 * - 始まりの時刻（[AudioCue.hasStartedAt]）になったら鳴らす。早めには鳴らさない（出力の遅れは見積もらない。
 *   鳴り始めの遅れは、聞こえている位置にエンジンが合わせて吸収する）
 * - 位置を移す（seek する）のは次の場合だけで、どれも1回きり:
 *   - 遅れて鳴らし始める（途中から受け取った・読み込みが大きく遅れた）: [LATE_START_MILLIS] より遅れていれば、
 *     鳴っているべき位置へ移してから鳴らす。それより小さい遅れは先頭から鳴らし、エンジンが音を待つ（出だしの音を削らない）
 *   - 再開: 止めた位置が鳴っているべき位置から [LATE_START_MILLIS] より離れていれば移してから鳴らす
 *   - 大きなずれ: 音がエンジンより [DISCONTINUITY_MILLIS] より遅れた（再生器が止まっていた）ら移す。音が追いつくまで、もう一度は移さない
 * - 一時停止中は止め、再開したら続きから鳴らす
 * - 再生速度は [AudioCue.playbackSpeed] に合わせる（音の高さは変えない）。読み込む前に合わせる
 * - 音を出すのはいつも1つの再生器だけ。次の cue に移ったら前の再生器はすぐ止め（クリップは鳴り終えている）、
 *   鳴らし始める前にほかの再生器が鳴っていれば止める。先に読み込んだ再生器は、その cue が始まるまで鳴らさない
 * - 鳴っている間は音量を変えない。cue がなくなったら（次へ・やり直し）、[FADE_OUT_MILLIS] かけて小さくしてから止める
 * - 利用者の音量（[setVolume]、設定の「音量」、0〜2 倍）は、変わったときだけすべての再生器に渡す（ふつうは対戦の初めに1回、
 *   鳴らす前）。再生器が出す音量は、これに小さくして止めるときの音量を掛けたもの
 *
 * @param wallClock 診断用の実時間（ms）。鳴らしてから音が出始めるまでの時間を測る
 * @param onClipFinished クリップ1つを受け持ち終えた（別の cue に移った・止めた）ときに、その記録を渡す（診断用）
 */
class VoiceCueFollower(
    ports: List<VoicePlayerPort>,
    private val wallClock: () -> Long = { 0L },
    private val onClipFinished: (VoiceClipReport) -> Unit = {},
) : AudioClock {
    init {
        require(ports.isNotEmpty()) { "再生器がない" }
    }

    private val decks = ports.mapIndexed { index, port -> Deck(index, port) }

    /** 今の cue を受け持つ再生器。なければ null。 */
    private var current: Deck? = null

    /** 利用者の音量。初めは 1（作ったばかりの再生器と同じ）。 */
    private var volume = 1f

    fun update(cue: AudioCue?, engineTimeMillis: Long, paused: Boolean, next: NextAudio? = null) {
        if (cue == null) {
            current?.let { finish(it, FADE_OUT_MILLIS) }
            current = null
        } else {
            val deck = current?.takeIf { it.cue?.cueId == cue.cueId } ?: take(cue)
            when {
                paused -> deck.pause()
                !deck.started -> deck.startIfDue(cue, engineTimeMillis)
                !deck.playing -> deck.resume(cue, engineTimeMillis)
                else -> deck.watch(cue, engineTimeMillis)
            }
        }
        preload(next)
    }

    /**
     * 利用者の音量を [volume]（クリップそのままの大きさを 1 とした倍率、0〜[GameConfig.MAX_VOICE_VOLUME]。範囲の外の値は
     * 収め、数でない値は無視する）にする。変わったときだけ、すべての再生器に渡す（刻みごとには渡さない）。
     * 鳴っている最中に変えても、位置は移さずに音量だけが変わる。
     */
    fun setVolume(volume: Float) {
        if (volume.isNaN()) return
        val v = volume.coerceIn(0f, GameConfig.MAX_VOICE_VOLUME)
        if (v == this.volume) return
        this.volume = v
        for (deck in decks) deck.port.setVolume(v)
    }

    override fun heardPositionMillis(cueId: Long): Long? {
        val deck = current ?: return null
        if (deck.cue?.cueId != cueId || !deck.playing || !deck.port.isPlaying) return null
        return deck.port.positionMillis
    }

    /** 鳴らしている cue と読み込んだクリップを忘れる（再生器を手放すとき）。 */
    fun reset() {
        current?.report()?.let(onClipFinished)
        current = null
        for (deck in decks) {
            deck.clear()
            deck.speed = null
        }
    }

    /** [cue] を受け持つ再生器を決める。先に読み込んであればそれを使い、なければ空いているものに読み込む。 */
    private fun take(cue: AudioCue): Deck {
        val previous = current
        // 前の cue のクリップは鳴り終えている（エンジンは音に合わせて進む）ので、小さくせずに止める
        previous?.let { finish(it, fadeOutMillis = 0L) }
        val deck = decks.firstOrNull { it !== previous && it.isPreloaded(cue.asset) }
            ?: decks.firstOrNull { it !== previous && it.isFree }
            ?: decks.firstOrNull { it !== previous }
            ?: decks.first()
        deck.assign(cue)
        current = deck
        return deck
    }

    /** cue を受け持ち終えた。鳴っていれば [fadeOutMillis] かけて小さくしてから止める。 */
    private fun finish(deck: Deck, fadeOutMillis: Long) {
        deck.report()?.let(onClipFinished)
        deck.port.stop(fadeOutMillis)
        deck.clear()
    }

    /** [deck] が鳴らし始める前に、ほかに鳴っている（小さくしている最中の）再生器を止める。2つ同時に音を出さない。 */
    private fun silenceOthers(deck: Deck) {
        for (other in decks) if (other !== deck && other.port.isPlaying) other.port.stop(0L)
    }

    /** 次に鳴る見込みのクリップを、今の cue を受け持っていない空いた再生器に読み込んでおく。 */
    private fun preload(next: NextAudio?) {
        if (next == null || decks.any { it.isPreloaded(next.asset) }) return
        val deck = decks.firstOrNull { it !== current && it.isFree } ?: return
        deck.load(next.asset, next.playbackSpeed)
    }

    private inner class Deck(val index: Int, val port: VoicePlayerPort) {
        /** 読み込んだ（読み込み中の）クリップ。受け持ちを終えたら null。 */
        var asset: String? = null

        /** 受け持っている cue。先に読み込んだだけなら null。 */
        var cue: AudioCue? = null

        /** 合わせた再生速度。 */
        var speed: Float? = null

        /** この cue を鳴らし始めたか（一時停止しても true のまま）。 */
        var started = false

        /** いま鳴らしている（一時停止していない）か。 */
        var playing = false

        /** 大きなずれで位置を移した先。音がそこから進み、エンジンに追いつくまでは、もう一度は移さない。 */
        private var discontinuitySeek: Long? = null
        private var stats = ClipStats()

        /** cue を受け持たず、鳴ってもいない（音を小さくしている最中でもない）。 */
        val isFree: Boolean get() = cue == null && !port.isPlaying

        /** [asset] を先に読み込んであり、まだどの cue にも使っていない。 */
        fun isPreloaded(asset: String): Boolean = cue == null && this.asset == asset

        fun load(asset: String, speed: Float) {
            if (this.speed != speed) {
                port.setSpeed(speed)
                this.speed = speed
            }
            port.prepare(asset)
            this.asset = asset
        }

        fun assign(cue: AudioCue) {
            val preloaded = isPreloaded(cue.asset) && speed == cue.playbackSpeed
            if (!preloaded) load(cue.asset, cue.playbackSpeed)
            this.cue = cue
            started = false
            playing = false
            discontinuitySeek = null
            stats = ClipStats(preloaded = preloaded, underrunsAtStart = port.underrunCount)
        }

        fun clear() {
            asset = null
            cue = null
            started = false
            playing = false
        }

        fun startIfDue(cue: AudioCue, now: Long) {
            // まだ始まらないか、まだ読み込み中（読み込めたら、遅れた分だけ先の位置から鳴らす）
            if (!cue.hasStartedAt(now) || !port.isReady) return
            val late = now - cue.startedAtMillis
            if (late > LATE_START_MILLIS) seekTo(cue.positionAt(now))
            stats.startLateMillis = late
            play()
            started = true
        }

        fun resume(cue: AudioCue, now: Long) {
            val target = cue.positionAt(now)
            val apart = abs(port.positionMillis - target) / cue.playbackSpeed.toDouble()
            if (apart > LATE_START_MILLIS) seekTo(target)
            play()
        }

        fun pause() {
            if (!playing) return
            port.pause()
            playing = false
        }

        /** 鳴っている間。位置は直さない（エンジンが音に合わせる）。再生器が止まっていたなどの大きなずれだけ1回直す。 */
        fun watch(cue: AudioCue, now: Long) {
            if (!port.isPlaying) return
            val position = port.positionMillis
            if (stats.outputDelayMillis == null && position > stats.playedFrom) {
                stats.outputDelayMillis = wallClock() - stats.playedAt
            }
            // 正なら音がエンジンより遅れている（エンジンの ms）
            val behind = ((cue.positionAt(now) - position) / cue.playbackSpeed.toDouble()).roundToLong()
            if (stats.outputDelayMillis != null) stats.maxDriftMillis = max(stats.maxDriftMillis, abs(behind))
            discontinuitySeek?.let { target ->
                // 移した先から音が進み、エンジンに追いついたら、次の大きなずれはまた直せる
                if (position > target && behind <= DISCONTINUITY_MILLIS / 2) discontinuitySeek = null
                return
            }
            if (behind > DISCONTINUITY_MILLIS) {
                val target = cue.positionAt(now)
                seekTo(target)
                discontinuitySeek = target
            }
        }

        fun report(): VoiceClipReport? {
            val c = cue ?: return null
            return VoiceClipReport(
                asset = c.asset,
                cueId = c.cueId,
                deck = index,
                prepareMillis = port.prepareMillis,
                preloaded = stats.preloaded,
                startLateMillis = stats.startLateMillis,
                outputDelayMillis = stats.outputDelayMillis,
                seeks = stats.seeks,
                maxDriftMillis = stats.maxDriftMillis,
                underruns = port.underrunCount - stats.underrunsAtStart,
            )
        }

        private fun play() {
            silenceOthers(this)
            port.play()
            playing = true
            discontinuitySeek = null
            stats.playedAt = wallClock()
            stats.playedFrom = port.positionMillis
        }

        private fun seekTo(target: Long) {
            port.seekTo(target)
            stats.seeks++
        }
    }

    /** 1つのクリップを鳴らした間の記録。 */
    private class ClipStats(
        val preloaded: Boolean = false,
        val underrunsAtStart: Int = 0,
    ) {
        var startLateMillis: Long? = null
        var playedAt = 0L
        var playedFrom = 0L
        var outputDelayMillis: Long? = null
        var seeks = 0
        var maxDriftMillis = 0L
    }

    companion object {
        /** cue がなくなったときに音を小さくしていく時間（ms）。 */
        const val FADE_OUT_MILLIS = 150L

        /**
         * 鳴らし始め・再開で、鳴っているべき位置がこれより離れていれば位置を移してから鳴らす（エンジンの ms）。
         * これ以下なら移さずに鳴らし、エンジンが音を待つ（MatchViewModel は音を 500ms まで待つ。出力の遅れの分を残す）。
         */
        const val LATE_START_MILLIS = 200L

        /** 鳴っている間に、音がエンジンよりこれより遅れたら（再生器が止まっていたなど）1回だけ位置を移す（エンジンの ms）。 */
        const val DISCONTINUITY_MILLIS = 400L
    }
}

/**
 * クリップ1つを鳴らした記録（診断用。デバッグ版だけ logcat に出す）。時間はすべて ms。
 *
 * @property deck 鳴らした再生器の番号
 * @property prepareMillis 読み込みにかかった時間。分からなければ null
 * @property preloaded 先に読み込んでおいたクリップを使ったか
 * @property startLateMillis 鳴らし始めが、クリップの始まりの時刻からどれだけ遅れたか（エンジンの ms）。鳴らさなかったら null
 * @property outputDelayMillis 鳴らし始めてから音が出始める（位置が進み始める）までの実時間。分からなければ null
 * @property seeks 位置を移した回数（遅れた鳴らし始め・再開・大きなずれ）
 * @property maxDriftMillis 音が出始めてからの、聞こえている位置とエンジンの時刻のずれの最大
 * @property underruns 受け持っている間に音の出力が途切れた（アンダーラン）回数
 */
data class VoiceClipReport(
    val asset: String,
    val cueId: Long,
    val deck: Int,
    val prepareMillis: Long?,
    val preloaded: Boolean,
    val startLateMillis: Long?,
    val outputDelayMillis: Long?,
    val seeks: Int,
    val maxDriftMillis: Long,
    val underruns: Int,
) {
    /** logcat に出す1行。 */
    fun toLogLine(): String =
        "clip=${asset.substringAfterLast('/')} cue=$cueId deck=$deck prepare=${prepareMillis.ms()} preloaded=$preloaded " +
            "late=${startLateMillis.ms()} outputDelay=${outputDelayMillis.ms()} seeks=$seeks maxDrift=${maxDriftMillis}ms " +
            "underruns=$underruns"

    private fun Long?.ms(): String = if (this == null) "-" else "${this}ms"
}
