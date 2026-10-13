package io.github.abonsan.hyakunin.audio

import io.github.abonsan.hyakunin.game.AudioCue
import io.github.abonsan.hyakunin.game.NextAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

/**
 * 偽の再生器。時刻 [now]（実時間、ms）はテストが進める。鳴らしてから [latencyMillis] たって音が出始め、そこから位置が
 * 再生速度で進む。[offset] で報告する位置をずらせる。[stuck] なら位置が進まない（再生器が止まった）。
 */
private class FakePort(var latencyMillis: Long = 0L, private val readyOnPrepare: Boolean = true) : VoicePlayerPort {
    val commands = mutableListOf<String>()
    var now = 0L
    var ready = false
    var offset = 0L
    override var underrunCount = 0

    /** 渡された利用者の音量（作ったばかりの再生器は 1）。 */
    var volume = 1f
        private set

    /** 止まった（位置が進まなくなった）ときの位置。 */
    private var frozen: Long? = null
    var stuck: Boolean
        get() = frozen != null
        set(value) {
            frozen = null
            frozen = if (value) positionMillis else null
        }
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
        if (frozen != null) frozen = positionMillis
    }

    override fun setSpeed(speed: Float) {
        commands += "speed $speed"
        this.speed = speed
    }

    override fun setVolume(volume: Float) {
        commands += "volume $volume"
        this.volume = volume
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
            frozen?.let { return it }
            val start = since ?: return base + offset
            val heard = (now - start - latencyMillis).coerceAtLeast(0L)
            return base + (heard * speed.toDouble()).roundToLong() + offset
        }

    /** これまでの命令を取り出して消す。 */
    fun take(): List<String> = commands.toList().also { commands.clear() }
}

/** 再生器を、エンジンの AudioCue に合わせて鳴らす決まり（VoiceCueFollower）。再生器は 2 つ（a で鳴らし、b に先読み）。 */
class VoiceCueFollowerTest {
    private val a = FakePort()
    private val b = FakePort()
    private val reports = mutableListOf<VoiceClipReport>()
    private val follower = VoiceCueFollower(listOf(a, b), wallClock = { a.now }, onClipFinished = { reports += it })

    private fun cue(id: Long, asset: String, start: Long, speed: Float = 1f) = AudioCue(asset, start, speed, id)

    private fun next(asset: String, speed: Float = 1f) = NextAudio(asset, speed)

    /** エンジンの時刻 [t]（実時間も同じとする）の状態を渡す。 */
    private fun at(t: Long, cue: AudioCue?, paused: Boolean = false, next: NextAudio? = null) {
        a.now = t
        b.now = t
        follower.update(cue, t, paused, next)
    }

    /** [from] から [to] まで 20ms ごとに状態を渡す（ViewModel の刻み）。 */
    private fun run(from: Long, to: Long, cue: AudioCue?, paused: Boolean = false, next: NextAudio? = null) {
        var t = from
        while (t <= to) {
            at(t, cue, paused, next)
            t += 20L
        }
    }

    @Test
    fun aCueAnnouncedAheadIsPreparedAndStartsExactlyAtItsStart() {
        val shimo = cue(2, "voice/001_shimo.ogg", start = 1_000L)
        at(500L, shimo)
        assertEquals(listOf("speed 1.0", "prepare voice/001_shimo.ogg"), a.take())
        // 始まる前は鳴らさない（早めには鳴らさない）
        run(520L, 980L, shimo)
        assertEquals(emptyList<String>(), a.take())
        // 始まりの時刻ちょうどに、先頭から鳴らす（位置は移さない）
        at(1_000L, shimo)
        assertEquals(listOf("play"), a.take())
        run(1_020L, 4_000L, shimo)
        assertEquals(emptyList<String>(), a.take() + b.take())
    }

    @Test
    fun theNextClipIsPreloadedOnTheOtherPlayerAndUsedWithoutReloading() {
        // 待ちの間に、待ちが終わって最初に鳴るクリップを読み込んでおく
        run(0L, 1_000L, null, next = next("voice/joka_kami.ogg"))
        assertEquals(listOf("speed 1.0", "prepare voice/joka_kami.ogg"), a.take())
        // 前置きが始まると（始まりの時刻に予告される）、読み込んだものをすぐ鳴らす
        val kami = cue(1, "voice/joka_kami.ogg", start = 1_020L)
        at(1_020L, kami, next = next("voice/joka_shimo.ogg"))
        assertEquals(listOf("play"), a.take())
        // 鳴っている間に、次のクリップをもう一つの再生器に読み込む
        assertEquals(listOf("speed 1.0", "prepare voice/joka_shimo.ogg"), b.take())
        run(1_040L, 5_000L, kami, next = next("voice/joka_shimo.ogg"))
        assertEquals(emptyList<String>(), a.take() + b.take())
        // 次の cue に移ると、鳴り終えた方はすぐ止め、読み込んであった方で鳴らす（読み直さない）
        val shimo = cue(2, "voice/joka_shimo.ogg", start = 5_020L)
        at(5_020L, shimo, next = next("voice/joka_shimo.ogg"))
        assertEquals(listOf("stop 0", "prepare voice/joka_shimo.ogg"), a.take())
        assertEquals(listOf("play"), b.take())
        // 同じファイルをもう一度読む（序歌の下の句の2回目）: 空いた方に先に読み込んである
        val again = cue(3, "voice/joka_shimo.ogg", start = 9_420L)
        run(5_040L, 9_400L, shimo, next = next("voice/joka_shimo.ogg"))
        at(9_420L, again)
        assertEquals(listOf("play"), a.take())
        assertEquals(listOf("stop 0"), b.take())
    }

    @Test
    fun eachCueIsPreparedAtMostOnce() {
        val cues = (1L..6L).map { cue(it, if (it % 2L == 0L) "s.ogg" else "k.ogg", start = it * 1_000L) }
        var t = 0L
        for ((i, c) in cues.withIndex()) {
            val n = cues.getOrNull(i + 1)?.let { next(it.asset) }
            while (t < c.startedAtMillis + 1_000L) {
                at(t, c, next = n)
                t += 20L
            }
        }
        val prepares = (a.commands + b.commands).count { it.startsWith("prepare") }
        assertEquals(cues.size, prepares)
    }

    @Test
    fun aSlightlyLateStartPlaysFromTheTopAndTheEngineWaitsForTheVoice() {
        // 読み込みが少し遅れた（200ms 以内）: 出だしを削らないよう先頭から鳴らす（エンジンが音に追いつくのを待つ）
        val slow = FakePort(readyOnPrepare = false)
        val follower = VoiceCueFollower(listOf(slow))
        val shimo = cue(3, "voice/joka_shimo.ogg", start = 1_000L)
        slow.now = 1_000L
        follower.update(shimo, 1_000L, paused = false)
        slow.now = 1_100L
        follower.update(shimo, 1_100L, paused = false)
        assertEquals(listOf("speed 1.0", "prepare voice/joka_shimo.ogg"), slow.take())
        slow.ready = true
        slow.now = 1_180L
        follower.update(shimo, 1_180L, paused = false)
        assertEquals(listOf("play"), slow.take())
    }

    @Test
    fun aVeryLateStartSeeksOnceThenPlays() {
        // 鳴っている最中の cue を途中から受け取った: 鳴っているべき位置へ移してから鳴らす
        val kami = cue(4, "voice/017_kami.ogg", start = 10_000L)
        at(13_000L, kami)
        assertEquals(listOf("speed 1.0", "prepare voice/017_kami.ogg", "seek 3000", "play"), a.take())
        run(13_020L, 15_000L, kami)
        assertEquals(emptyList<String>(), a.take())
    }

    @Test
    fun aDriftDuringPlaybackIsNeverCorrected() {
        // 音が先でも遅れていても（400ms まで）位置を移さない。エンジンの方が聞こえている位置に合わせて進む
        val kami = cue(5, "voice/061_kami.ogg", start = 0L)
        run(0L, 1_000L, kami)
        a.take()
        for (drift in listOf(150L, 300L, -150L, -380L)) {
            a.offset = drift
            run(a.now + 20L, a.now + 1_000L, kami)
            assertEquals("drift=$drift", emptyList<String>(), a.take())
        }
    }

    @Test
    fun aLargeDiscontinuityIsCorrectedOnlyOnce() {
        // 再生器が止まって音がエンジンより 400ms 超遅れた: 1回だけ鳴っているべき位置へ移す
        val shimo = cue(6, "voice/061_shimo.ogg", start = 0L)
        run(0L, 1_000L, shimo)
        a.take()
        a.stuck = true
        run(1_020L, 1_400L, shimo)
        assertEquals(emptyList<String>(), a.take())
        at(1_420L, shimo)
        assertEquals(listOf("seek 1420"), a.take())
        // 止まったままなら、もう移さない
        run(1_440L, 4_000L, shimo)
        assertEquals(emptyList<String>(), a.take())
    }

    @Test
    fun pausingStopsThePlayerAndResumingPlaysOnFromTheSamePlace() {
        val kami = cue(7, "voice/033_kami.ogg", start = 0L)
        run(0L, 1_500L, kami)
        a.take()
        at(1_520L, kami, paused = true)
        assertEquals(listOf("pause"), a.take())
        repeat(10) { at(1_520L, kami, paused = true) }
        assertEquals(emptyList<String>(), a.take())
        at(1_520L, kami)
        assertEquals(listOf("play"), a.take())
        run(1_540L, 3_000L, kami)
        assertEquals(emptyList<String>(), a.take())
    }

    @Test
    fun resumingSeeksOnlyWhenThePlayerLostItsPlace() {
        val kami = cue(7, "voice/033_kami.ogg", start = 0L)
        run(0L, 1_500L, kami)
        at(1_520L, kami, paused = true)
        a.take()
        a.offset = -800L
        at(1_520L, kami)
        assertEquals(listOf("seek 1520", "play"), a.take())
    }

    @Test
    fun theVoiceFadesOutOnlyWhenTheCueDisappears() {
        val shimo = cue(9, "voice/087_shimo.ogg", start = 0L)
        run(0L, 2_000L, shimo)
        a.take()
        at(2_020L, null)
        assertEquals(listOf("stop ${VoiceCueFollower.FADE_OUT_MILLIS}"), a.take())
        assertEquals(150L, VoiceCueFollower.FADE_OUT_MILLIS)
        run(2_040L, 3_000L, null)
        assertEquals(emptyList<String>(), a.take() + b.take())
    }

    @Test
    fun anotherPlayerStillSoundingIsSilencedBeforeANewClipStarts() {
        // 次へ（フェード中）のすぐ後に新しい読みが始まっても、2つ同時には鳴らさない
        val shimo = cue(1, "s.ogg", start = 0L)
        run(0L, 1_000L, shimo, next = next("s.ogg"))
        a.take()
        b.take()
        at(1_020L, null, next = next("s.ogg"))
        assertEquals(listOf("stop 150"), a.take())
        val lead = cue(2, "s.ogg", start = 1_040L)
        // 偽の再生器はすぐ止まるので、フェード中のふりをする
        a.play()
        a.take()
        at(1_040L, lead)
        assertEquals(listOf("stop 0"), a.take())
        assertEquals(listOf("play"), b.take())
    }

    @Test
    fun theSpeedIsSetBeforeLoadingAndOnlyWhenItChanges() {
        run(0L, 1_000L, cue(1, "a.ogg", start = 0L, speed = 0.85f), next = next("b.ogg", 0.85f))
        run(1_020L, 2_000L, cue(2, "b.ogg", start = 1_020L, speed = 0.85f), next = next("c.ogg", 0.85f))
        run(2_020L, 3_000L, cue(3, "c.ogg", start = 2_020L, speed = 0.85f))
        assertEquals(listOf("speed 0.85", "prepare a.ogg"), a.commands.take(2))
        assertEquals(listOf("speed 0.85", "prepare b.ogg"), b.commands.take(2))
        assertEquals(1, a.commands.count { it.startsWith("speed") })
        assertEquals(1, b.commands.count { it.startsWith("speed") })
    }

    @Test
    fun theHeardPositionIsTheClockOnlyWhileTheCueIsPlaying() {
        val kami = cue(1, "k.ogg", start = 1_000L)
        at(500L, kami)
        assertNull(follower.heardPositionMillis(1L))
        a.latencyMillis = 60L
        at(1_000L, kami)
        // 鳴らし始めても、音が出るまでは位置が進まない（エンジンはここで待つ）
        assertEquals(0L, follower.heardPositionMillis(1L))
        run(1_020L, 2_000L, kami)
        assertEquals(940L, follower.heardPositionMillis(1L))
        assertNull(follower.heardPositionMillis(2L))
        at(2_020L, kami, paused = true)
        assertNull(follower.heardPositionMillis(1L))
    }

    @Test
    fun eachClipIsReportedForDiagnostics() {
        a.latencyMillis = 40L
        val kami = cue(1, "voice/himari/001_kami.ogg", start = 0L)
        run(0L, 2_000L, kami)
        a.underrunCount = 2
        at(2_020L, null)
        val report = reports.single()
        assertEquals("voice/himari/001_kami.ogg", report.asset)
        assertEquals(0L, report.startLateMillis)
        assertEquals(60L, report.outputDelayMillis)
        assertEquals(0, report.seeks)
        assertEquals(2, report.underruns)
        assertTrue(report.maxDriftMillis <= 40L)
        assertEquals(
            "clip=001_kami.ogg cue=1 deck=0 prepare=- preloaded=false late=0ms outputDelay=60ms seeks=0 " +
                "maxDrift=${report.maxDriftMillis}ms underruns=2",
            report.toLogLine(),
        )
    }

    @Test
    fun theUserVolumeIsGivenToEachPlayerOnceBeforeAnythingPlays() {
        // 対戦の初めに音量を渡す: どちらの再生器にも1回だけ。読み込み・鳴らし始め・鳴っている間・次のクリップでは渡さない
        follower.setVolume(0.6f)
        assertEquals(listOf("volume 0.6"), a.take())
        assertEquals(listOf("volume 0.6"), b.take())
        follower.setVolume(0.6f)
        run(0L, 980L, null, next = next("k.ogg"))
        assertEquals(listOf("speed 1.0", "prepare k.ogg"), a.take())
        val kami = cue(1, "k.ogg", start = 1_000L)
        at(1_000L, kami, next = next("s.ogg"))
        assertEquals(listOf("play"), a.take())
        assertEquals(listOf("speed 1.0", "prepare s.ogg"), b.take())
        run(1_020L, 4_000L, kami, next = next("s.ogg"))
        assertEquals(emptyList<String>(), a.take() + b.take())
        val shimo = cue(2, "s.ogg", start = 4_020L)
        at(4_020L, shimo)
        run(4_040L, 8_000L, shimo)
        assertEquals(listOf("stop 0"), a.take())
        assertEquals(listOf("play"), b.take())
        // どちらの再生器も、利用者の音量で鳴る
        assertEquals(0.6f, a.volume)
        assertEquals(0.6f, b.volume)
    }

    @Test
    fun atFullVolumeNoVolumeIsEverSet() {
        // 100%（既定）なら、作ったばかりの再生器の音量のままなので何も渡さない
        follower.setVolume(1f)
        val kami = cue(1, "k.ogg", start = 0L)
        run(0L, 3_000L, kami, next = next("s.ogg"))
        run(3_020L, 6_000L, cue(2, "s.ogg", start = 3_020L))
        at(6_020L, null)
        assertTrue((a.commands + b.commands).none { it.startsWith("volume") })
    }

    @Test
    fun changingTheVolumeWhilePlayingOnlyChangesTheVolume() {
        val kami = cue(3, "k.ogg", start = 0L)
        run(0L, 1_000L, kami)
        a.take()
        val heard = follower.heardPositionMillis(3L)
        // 鳴っている最中に変えても、位置は移さず止めもしない（音量だけ。聞こえている位置はそのまま進む）
        follower.setVolume(0.3f)
        assertEquals(listOf("volume 0.3"), a.take())
        assertEquals(listOf("volume 0.3"), b.take())
        assertEquals(heard, follower.heardPositionMillis(3L))
        run(1_020L, 2_000L, kami)
        assertEquals(emptyList<String>(), a.take() + b.take())
        assertEquals(2_000L, follower.heardPositionMillis(3L))
    }

    @Test
    fun twiceTheVolumeIsPassedOnceAndNothingChangesWhileItPlays() {
        // 200%（クリップの 2 倍）も、そのまま再生器に渡す。鳴っている間は何も渡さない
        follower.setVolume(2f)
        assertEquals(listOf("volume 2.0"), a.take())
        assertEquals(listOf("volume 2.0"), b.take())
        val kami = cue(1, "k.ogg", start = 0L)
        run(0L, 3_000L, kami)
        assertEquals(listOf("speed 1.0", "prepare k.ogg", "play"), a.take())
        assertEquals(emptyList<String>(), b.take())
        follower.setVolume(2f)
        run(3_020L, 4_000L, kami)
        assertEquals(emptyList<String>(), a.take() + b.take())
        assertEquals(2f, a.volume)
    }

    @Test
    fun theVolumeIsKeptInRange() {
        follower.setVolume(1.4f)
        assertEquals(listOf("volume 1.4"), a.take())
        follower.setVolume(2.6f)
        assertEquals(listOf("volume 2.0"), a.take())
        follower.setVolume(2f)
        assertEquals(emptyList<String>(), a.take())
        follower.setVolume(-0.5f)
        assertEquals(listOf("volume 0.0"), a.take())
        follower.setVolume(Float.NaN)
        assertEquals(emptyList<String>(), a.take())
        assertEquals(0f, a.volume)
    }

    @Test
    fun noCueAtAllDoesNothing() {
        run(0L, 10_000L, null)
        assertEquals(emptyList<String>(), a.take() + b.take())
    }

    @Test
    fun aRestartStopsTheVoiceAndTheNewMatchStartsFromItsOwnClock() {
        val old = cue(5, "voice/061_shimo.ogg", start = 20_000L)
        run(20_000L, 22_000L, old)
        a.take()
        // やり直し: 音声がなくなり、エンジンの時計は 0 に戻る
        at(0L, null)
        assertEquals(listOf("stop 150"), a.take())
        run(20L, 9_980L, null)
        val joka = cue(6, "voice/joka_kami.ogg", start = 10_000L)
        at(10_000L, joka)
        assertEquals(listOf("prepare voice/joka_kami.ogg", "play"), a.take())
        run(10_020L, 12_000L, joka)
        assertEquals(emptyList<String>(), a.take() + b.take())
    }
}
