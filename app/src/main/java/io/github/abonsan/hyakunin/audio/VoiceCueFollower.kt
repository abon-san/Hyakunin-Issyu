package io.github.abonsan.hyakunin.audio

import io.github.abonsan.hyakunin.game.AudioCue
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 再生器（[VoicePlayerPort]）を、エンジンが出す今鳴らすべき音声（[AudioCue]）に追従させる。純粋な Kotlin。
 * 時刻はエンジンの時計（ms）で、対戦の状態が変わるたびに [update] を呼ぶ（ViewModel の刻みごと、約 20ms）。
 *
 * - 新しい cue（[AudioCue.cueId] が変わった）: そのクリップを読み込む。始まりが先の時刻なら、読み込んで待つ
 * - 始まりの時刻（[AudioCue.hasStartedAt]）になったら鳴らす。遅れて始めるときは [AudioCue.positionAt] へ移してから鳴らす
 * - 鳴っている間は、再生位置と [AudioCue.positionAt] のずれが [DRIFT_LIMIT_MILLIS] を超えたら位置を直す
 * - 一時停止中は止め、再開したら続きから鳴らす
 * - 再生速度は [AudioCue.playbackSpeed] に合わせる（音の高さは変えない）
 * - cue がなくなったら、[FADE_OUT_MILLIS] かけて小さくしてから止める
 *
 * 鳴らし始めてから音が聞こえるまでの出力の遅れ（Bluetooth では 0.2 秒ほど）は、鳴らし始め・位置を直した後の
 * 最初のずれから見積もり（[latencyMillis]）、次からはその分だけ早めに鳴らし始め、その分だけ先の位置へ移す。
 * 見積もりが 0 のうち（遅れのない再生器）は、ちょうど始まりの時刻に鳴らし、ちょうど [AudioCue.positionAt] へ移す。
 */
class VoiceCueFollower(private val port: VoicePlayerPort) {
    /** 読み込んだ cue。なければ null。 */
    private var cueId: Long? = null
    private var speed: Float? = null

    /** この cue を鳴らし始めたか（一時停止しても true のまま）。 */
    private var started = false

    /** いま鳴らしている（一時停止していない）か。 */
    private var playing = false

    /** 最後に位置を合わせた（鳴らし始めた・再開した・位置を直した）エンジンの時刻。 */
    private var syncedAt = 0L

    /** 位置を合わせたときの、鳴らした位置と狙った位置の差（クリップの ms）。出力の遅れの見積もりで差し引く。 */
    private var syncOffset = 0L

    /** 位置を合わせた後の最初のずれを、まだ出力の遅れの見積もりに使っていないか。 */
    private var measuring = false
    private var latencySamples = 0

    /** 見積もった出力の遅れ（実時間の ms）。 */
    var latencyMillis: Long = 0L
        private set

    fun update(cue: AudioCue?, engineTimeMillis: Long, paused: Boolean) {
        if (cue == null) {
            stopCue()
            return
        }
        if (cue.cueId != cueId) loadCue(cue)
        if (cue.playbackSpeed != speed) {
            port.setSpeed(cue.playbackSpeed)
            speed = cue.playbackSpeed
        }
        when {
            paused -> pauseCue()
            !started -> startIfDue(cue, engineTimeMillis)
            !playing -> resumeCue(cue, engineTimeMillis)
            else -> correctDrift(cue, engineTimeMillis)
        }
    }

    /** 鳴らしている cue を忘れる（再生器を手放すとき）。出力の遅れの見積もりは残す。 */
    fun reset() {
        cueId = null
        speed = null
        started = false
        playing = false
        measuring = false
    }

    private fun loadCue(cue: AudioCue) {
        port.prepare(cue.asset)
        cueId = cue.cueId
        started = false
        playing = false
        measuring = false
    }

    private fun stopCue() {
        if (cueId == null) return
        port.stop(FADE_OUT_MILLIS)
        cueId = null
        started = false
        playing = false
        measuring = false
    }

    private fun pauseCue() {
        if (!playing) return
        port.pause()
        playing = false
        measuring = false
    }

    /** 出力の遅れの分だけ先の位置（クリップの ms）。 */
    private fun lead(cue: AudioCue): Long = (latencyMillis * cue.playbackSpeed.toDouble()).roundToLong()

    private fun startIfDue(cue: AudioCue, now: Long) {
        val target = cue.positionAt(now) + lead(cue)
        // まだ始まらない（出力の遅れの分だけ早めに鳴らし始める）か、まだ読み込み中
        if (target < 0L || !port.isReady) return
        play(target, seek = target > LATE_START_MILLIS, now)
        started = true
    }

    private fun resumeCue(cue: AudioCue, now: Long) {
        val target = cue.positionAt(now) + lead(cue)
        play(target, seek = abs(port.positionMillis - target) > LATE_START_MILLIS, now)
    }

    private fun play(target: Long, seek: Boolean, now: Long) {
        if (seek) port.seekTo(target)
        syncOffset = if (seek) 0L else port.positionMillis - target
        port.play()
        playing = true
        syncedAt = now
        measuring = true
    }

    private fun correctDrift(cue: AudioCue, now: Long) {
        // 位置を合わせた直後と、読み込み・位置の移動の途中は測らない
        if (!port.isPlaying || now - syncedAt < SETTLE_MILLIS) return
        val expected = cue.positionAt(now)
        // 正なら音が先、負なら音が遅れている
        val drift = port.positionMillis - expected
        if (measuring) {
            measuring = false
            learnLatency((syncOffset - drift) / cue.playbackSpeed.toDouble())
        }
        if (abs(drift) > DRIFT_LIMIT_MILLIS) {
            port.seekTo(expected + lead(cue))
            syncOffset = 0L
            syncedAt = now
            measuring = true
        }
    }

    /** 位置を合わせた後の音の遅れ（今の見積もりで足りなかった分、実時間の ms）を見積もりに取り込む。 */
    private fun learnLatency(shortfallMillis: Double) {
        val gain = if (latencySamples == 0) 1.0 else LATENCY_GAIN
        latencyMillis = (latencyMillis + shortfallMillis * gain).roundToLong().coerceIn(0L, MAX_LATENCY_MILLIS)
        latencySamples++
    }

    companion object {
        /** cue がなくなったときに音を小さくしていく時間（ms）。 */
        const val FADE_OUT_MILLIS = 150L

        /** 再生位置がこれより大きくずれたら位置を直す（クリップの ms）。 */
        const val DRIFT_LIMIT_MILLIS = 120L

        /** 鳴らし始め・再開で、狙った位置がこれより離れていれば位置を移してから鳴らす（クリップの ms）。 */
        const val LATE_START_MILLIS = 50L

        /** 位置を合わせてから、ずれを測り始めるまでの間（エンジンの ms）。鳴り始めの位置が落ち着くのを待つ。 */
        const val SETTLE_MILLIS = 400L

        /** 出力の遅れの見積もりの上限（ms）。 */
        const val MAX_LATENCY_MILLIS = 400L

        /** 2回目からの見積もりで、新しく測った遅れをどれだけ取り込むか。 */
        private const val LATENCY_GAIN = 0.5
    }
}
