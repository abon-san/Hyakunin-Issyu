package io.github.abonsan.hyakunin.audio

import io.github.abonsan.hyakunin.game.AudioCue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * 偽の再生器。時刻 [now]（実時間、ms）はテストが進める。鳴らし始め・位置の移動から [latencyMillis] たって
 * 音が聞こえ始め、そこから位置が再生速度で進む。[offset] で位置をずらせる（再生器の遅れ・進みを起こす）。
 */
private class FakePort(var latencyMillis: Long = 0L, private val readyOnPrepare: Boolean = true) : VoicePlayerPort {
    val commands = mutableListOf<String>()
    var now = 0L
    var ready = false
    var offset = 0L
    private var speed = 1f
    private var base = 0L
    private var since: Long? = null

    override fun prepare(asset: String) {
        commands += "prepare $asset"
        base = 0L
        since = null
        offset = 0L
        ready = readyOnPrepare
    }

    override fun play() {
        commands += "play"
        if (since == null) since = now
    }

    override fun pause() {
        commands += "pause"
        base = positionMillis
        offset = 0L
        since = null
    }

    override fun seekTo(positionMillis: Long) {
        commands += "seek $positionMillis"
        base = positionMillis
        offset = 0L
        if (since != null) since = now
    }

    override fun setSpeed(speed: Float) {
        commands += "speed $speed"
        this.speed = speed
    }

    override fun stop(fadeOutMillis: Long) {
        commands += "stop $fadeOutMillis"
        since = null
        ready = false
    }

    override val isReady: Boolean get() = ready

    override val isPlaying: Boolean get() = since != null

    override val positionMillis: Long
        get() {
            val start = since ?: return base + offset
            val heard = (now - start - latencyMillis).coerceAtLeast(0L)
            return base + (heard * speed.toDouble()).roundToLong() + offset
        }

    /** これまでの命令を取り出して消す。 */
    fun take(): List<String> = commands.toList().also { commands.clear() }
}

/** 再生器を、エンジンの AudioCue に合わせる決まり（VoiceCueFollower）。 */
class VoiceCueFollowerTest {
    private val port = FakePort()
    private val follower = VoiceCueFollower(port)

    private fun cue(id: Long, asset: String, start: Long, speed: Float = 1f) = AudioCue(asset, start, speed, id)

    /** エンジンの時刻 [t]（実時間も同じとする）の状態を渡す。 */
    private fun at(t: Long, cue: AudioCue?, paused: Boolean = false) {
        port.now = t
        follower.update(cue, t, paused)
    }

    /** [from] から [to] まで 20ms ごとに状態を渡す（ViewModel の刻み）。 */
    private fun run(from: Long, to: Long, cue: AudioCue?, paused: Boolean = false) {
        var t = from
        while (t <= to) {
            at(t, cue, paused)
            t += 20L
        }
    }

    @Test
    fun aCueAnnouncedAheadIsPreparedAndStartsExactlyAtItsStart() {
        val shimo = cue(2, "voice/001_shimo.ogg", start = 1_000L)
        at(500L, shimo)
        assertEquals(listOf("prepare voice/001_shimo.ogg", "speed 1.0"), port.take())
        // 始まる前は鳴らさない
        run(520L, 980L, shimo)
        assertEquals(emptyList<String>(), port.take())
        // 始まりの時刻ちょうどに、先頭から鳴らす（位置は移さない）
        at(1_000L, shimo)
        assertEquals(listOf("play"), port.take())
        // そのあとは同じ cue のまま鳴り続け、ずれがなければ何もしない
        run(1_020L, 4_000L, shimo)
        assertEquals(emptyList<String>(), port.take())
    }

    @Test
    fun aLateStartSeeksToThePositionAtThenPlays() {
        // 鳴っている最中の cue を途中から受け取った（画面を作り直したときなど）
        val kami = cue(4, "voice/017_kami.ogg", start = 10_000L)
        at(13_000L, kami)
        assertEquals(listOf("prepare voice/017_kami.ogg", "speed 1.0", "seek 3000", "play"), port.take())
    }

    @Test
    fun aSlightlyLateStartPlaysFromTheTopWithoutSeeking() {
        // 刻みの分（20ms）遅れたくらいでは位置を移さない
        val kami = cue(1, "voice/joka_kami.ogg", start = 980L)
        at(1_000L, kami)
        assertEquals(listOf("prepare voice/joka_kami.ogg", "speed 1.0", "play"), port.take())
    }

    @Test
    fun theStartWaitsUntilThePlayerIsReadyThenCatchesUp() {
        val slowPort = FakePort(readyOnPrepare = false)
        val slow = VoiceCueFollower(slowPort)
        val shimo = cue(3, "voice/joka_shimo.ogg", start = 1_000L)
        slowPort.now = 1_000L
        slow.update(shimo, 1_000L, paused = false)
        // 読み込みが終わるまでは鳴らさない
        slowPort.now = 1_040L
        slow.update(shimo, 1_040L, paused = false)
        assertEquals(listOf("prepare voice/joka_shimo.ogg", "speed 1.0"), slowPort.take())
        // 読み込めたら、その時刻に鳴っているべき位置へ移して鳴らす
        slowPort.ready = true
        slowPort.now = 1_080L
        slow.update(shimo, 1_080L, paused = false)
        assertEquals(listOf("seek 80", "play"), slowPort.take())
    }

    @Test
    fun driftBeyond120MillisIsCorrectedWithASeek() {
        val kami = cue(5, "voice/061_kami.ogg", start = 0L)
        at(0L, kami)
        port.take()
        run(20L, 1_000L, kami)
        // 100ms 先に進んでいるくらいなら直さない
        port.offset = 100L
        run(1_020L, 1_500L, kami)
        assertEquals(emptyList<String>(), port.take())
        // 150ms 先に進んだら、鳴っているべき位置へ戻す
        port.offset = 150L
        at(1_520L, kami)
        assertEquals(listOf("seek 1520"), port.take())
        // 遅れたときも直す
        run(1_540L, 2_000L, kami)
        port.offset = -130L
        at(2_020L, kami)
        assertEquals(listOf("seek 2020"), port.take())
    }

    @Test
    fun aCorrectionIsNotRepeatedBeforeThePositionSettles() {
        val kami = cue(5, "voice/061_kami.ogg", start = 0L)
        at(0L, kami)
        run(20L, 1_000L, kami)
        port.take()
        port.offset = 300L
        at(1_020L, kami)
        assertEquals(listOf("seek 1020"), port.take())
        // 直した直後にまたずれても、位置が落ち着くまで（400ms）は測らない
        port.offset = 300L
        run(1_040L, 1_400L, kami)
        assertEquals(emptyList<String>(), port.take())
        at(1_420L, kami)
        assertEquals(listOf("seek 1420"), port.take())
    }

    @Test
    fun theDriftUsesThePlaybackSpeed() {
        // 1.2 倍速: エンジンの 1000ms はクリップの 1200ms
        val kami = cue(6, "voice/002_kami.ogg", start = 0L, speed = 1.2f)
        at(0L, kami)
        assertEquals(listOf("prepare voice/002_kami.ogg", "speed 1.2", "play"), port.take())
        run(20L, 2_000L, kami)
        assertEquals(emptyList<String>(), port.take())
        port.offset = -200L
        at(2_020L, kami)
        assertEquals(listOf("seek ${(2_020 * 1.2).roundToLong()}"), port.take())
    }

    @Test
    fun pausingStopsThePlayerAndResumingPlaysOnFromTheSamePlace() {
        val kami = cue(7, "voice/033_kami.ogg", start = 0L)
        run(0L, 1_500L, kami)
        port.take()
        // 一時停止中はエンジンの時計が止まる
        at(1_520L, kami, paused = true)
        assertEquals(listOf("pause"), port.take())
        repeat(10) { at(1_520L, kami, paused = true) }
        assertEquals(emptyList<String>(), port.take())
        // 再開すると、止めた位置からそのまま鳴らす（位置は合っているので移さない）
        at(1_520L, kami)
        assertEquals(listOf("play"), port.take())
        run(1_540L, 3_000L, kami)
        assertEquals(emptyList<String>(), port.take())
    }

    @Test
    fun resumingSeeksWhenThePlayerLostItsPlace() {
        val kami = cue(7, "voice/033_kami.ogg", start = 0L)
        run(0L, 1_500L, kami)
        at(1_520L, kami, paused = true)
        port.take()
        port.offset = -800L
        at(1_520L, kami)
        assertEquals(listOf("seek 1520", "play"), port.take())
    }

    @Test
    fun aCueThatStartsWhilePausedWaitsForTheResume() {
        val shimo = cue(8, "voice/033_shimo.ogg", start = 2_000L)
        at(1_500L, shimo)
        port.take()
        at(1_500L, shimo, paused = true)
        assertEquals(emptyList<String>(), port.take())
        at(1_500L, shimo)
        run(1_520L, 1_980L, shimo)
        assertEquals(emptyList<String>(), port.take())
        at(2_000L, shimo)
        assertEquals(listOf("play"), port.take())
    }

    @Test
    fun whenTheCueDisappearsTheVoiceFadesOutAndStops() {
        val shimo = cue(9, "voice/087_shimo.ogg", start = 0L)
        run(0L, 2_000L, shimo)
        port.take()
        // 次へ進んだ（Waiting）: 150ms かけて小さくして止める
        at(2_020L, null)
        assertEquals(listOf("stop ${VoiceCueFollower.FADE_OUT_MILLIS}"), port.take())
        assertEquals(150L, VoiceCueFollower.FADE_OUT_MILLIS)
        // 止めたあとは何もしない
        run(2_040L, 3_000L, null)
        assertEquals(emptyList<String>(), port.take())
    }

    @Test
    fun noCueAtAllDoesNothing() {
        run(0L, 10_000L, null)
        assertEquals(emptyList<String>(), port.take())
    }

    @Test
    fun aSequenceOfCuesSwitchesClipsOnEachNewCueId() {
        // 序歌: 上の句 → 下の句 → 同じ下の句をもう一度（別の cue）→ 札の上の句（間合いのうちに予告）
        val kami = cue(1, "voice/joka_kami.ogg", start = 0L)
        val shimo1 = cue(2, "voice/joka_shimo.ogg", start = 4_981L)
        val shimo2 = cue(3, "voice/joka_shimo.ogg", start = 11_106L)
        val next = cue(4, "voice/012_kami.ogg", start = 18_231L)
        run(0L, 4_320L, kami)
        assertEquals(listOf("prepare voice/joka_kami.ogg", "speed 1.0", "play"), port.take())
        // 上の句が終わると、下の句が先の時刻で予告される
        run(4_340L, 4_980L, shimo1)
        assertEquals(listOf("prepare voice/joka_shimo.ogg"), port.take())
        run(5_000L, 11_100L, shimo1)
        assertEquals(listOf("play"), port.take())
        // 同じファイルでも cue が変われば読み直して頭から鳴らす
        run(11_120L, 17_220L, shimo2)
        assertEquals(listOf("prepare voice/joka_shimo.ogg", "play"), port.take())
        run(17_240L, 18_220L, next)
        assertEquals(listOf("prepare voice/012_kami.ogg"), port.take())
        at(18_240L, next)
        assertEquals(listOf("play"), port.take())
    }

    @Test
    fun theSpeedIsSetOnlyWhenItChanges() {
        run(0L, 1_000L, cue(1, "a.ogg", start = 0L, speed = 0.85f))
        run(1_020L, 2_000L, cue(2, "b.ogg", start = 1_020L, speed = 0.85f))
        assertEquals(1, port.commands.count { it.startsWith("speed") })
        assertTrue(port.commands.contains("speed 0.85"))
    }

    @Test
    fun aRestartStopsTheVoiceAndTheNewMatchStartsFromItsOwnClock() {
        val old = cue(5, "voice/061_shimo.ogg", start = 20_000L)
        run(20_000L, 22_000L, old)
        port.take()
        // やり直し: 音声がなくなり、エンジンの時計は 0 に戻る
        at(0L, null)
        assertEquals(listOf("stop 150"), port.take())
        run(20L, 9_980L, null)
        // 新しい試合の序歌（cueId は増え続ける）
        val joka = cue(6, "voice/joka_kami.ogg", start = 10_000L)
        at(10_000L, joka)
        assertEquals(listOf("prepare voice/joka_kami.ogg", "play"), port.take())
        run(10_020L, 12_000L, joka)
        assertEquals(emptyList<String>(), port.take())
    }

    @Test
    fun aPlayerWithoutOutputDelayNeedsNoLatencyCompensation() {
        val kami = cue(1, "voice/001_kami.ogg", start = 0L)
        run(0L, 4_000L, kami)
        assertEquals(0L, follower.latencyMillis)
    }

    @Test
    fun theOutputDelayIsLearnedAndLaterClipsStartEarlyByThatMuch() {
        // Bluetooth のように、鳴らしてから 200ms たって音が聞こえる再生器
        port.latencyMillis = 200L
        val kami = cue(1, "voice/001_kami.ogg", start = 0L)
        run(0L, 380L, kami)
        assertEquals(listOf("prepare voice/001_kami.ogg", "speed 1.0", "play"), port.take())
        // 落ち着いたところで 200ms の遅れを測り、その分だけ先の位置へ移す
        at(400L, kami)
        assertEquals(200L, follower.latencyMillis)
        assertEquals(listOf("seek 600"), port.take())
        // 移した後は、聞こえている位置が鳴っているべき位置と合う
        run(420L, 4_000L, kami)
        assertEquals(emptyList<String>(), port.take())
        assertTrue(abs(port.positionMillis - kami.positionAt(4_000L)) <= 20L)

        // 次のクリップは 200ms 早く鳴らし始め、始まりの時刻ちょうどに聞こえ始める
        val shimo = cue(2, "voice/001_shimo.ogg", start = 5_000L)
        run(4_020L, 4_780L, shimo)
        assertEquals(listOf("prepare voice/001_shimo.ogg"), port.take())
        at(4_800L, shimo)
        assertEquals(listOf("play"), port.take())
        run(4_820L, 9_000L, shimo)
        assertEquals(emptyList<String>(), port.take())
        assertTrue(abs(port.positionMillis - shimo.positionAt(9_000L)) <= 20L)
        assertEquals(200L, follower.latencyMillis)
    }

    @Test
    fun theLatencyEstimateFollowsAChangedOutput() {
        port.latencyMillis = 200L
        val first = cue(1, "a.ogg", start = 0L)
        run(0L, 3_000L, first)
        assertEquals(200L, follower.latencyMillis)
        // 端末のスピーカーに戻った（遅れがなくなった）: 早く鳴らしすぎたぶんを測って見積もりを下げる
        port.latencyMillis = 0L
        var start = 4_000L
        repeat(6) { i ->
            val c = cue(2L + i, "b.ogg", start = start)
            run(start - 1_000L, start + 3_000L, c)
            start += 5_000L
        }
        assertTrue("latency=${follower.latencyMillis}", follower.latencyMillis < 20L)
    }
}
