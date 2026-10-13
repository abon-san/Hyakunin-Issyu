package io.github.abonsan.hyakunin.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 読み上げ音声の鳴らし方を、端末の遅れとメインスレッドの混み具合を変えて再現する（[simulatePlayback]）。
 *
 * v0.4.0 では、エンジンは実時間（1 刻み 20〜100ms に丸める）で進み、再生側は 400ms ごとに位置を測って 120ms を超える
 * ずれを seek で直し、鳴り始めの遅れを学習して早めに鳴らしていた。ExoPlayer の seek は AudioTrack を作り直す
 * （DefaultAudioSink.flush）ので、seek のたびに音が一瞬途切れて小さくなってから戻る（音量が揺れて聞こえる）。
 * 同じ再現で v0.4.0 は次のとおりだった（5 通りの乱数、序歌と 3 枚の読みの間）:
 * 刻みの揺れだけ 0 回、止まり（250ms ごと 3 秒）13 回、位置が 150ms 先 118 回、出力の遅れ 200ms 4 回（7 クリップを早めに鳴らし、
 * 3 クリップの出だしを 200ms 削る）、読み込み 80ms 4 回、スマートフォンくらい 4〜9 回、混んだメインスレッド 7〜13 回の seek。
 * 読み直し・途中で小さくする・音声フォーカスの出し入れ・再生速度の設定し直し・2つ同時に鳴ることは、どの場合も起きていなかった。
 *
 * 今は、鳴っている間は音声が時計になり（エンジンが聞こえている位置に合わせて進む）、次のクリップを先に読み込むので、
 * ふつうに鳴っている間は位置を移さず、音量も変えず、音を出す再生器はいつも1つ。
 * 利用者の音量（設定の「音量」、0〜200%）は対戦の初めに再生器ごとに1回だけ渡して PCM に掛け、ExoPlayer の音量は
 * 小さくして止めるときだけ下げる（1 を超えない）。聞こえる音量は、その2つを掛けたもの。
 */
class PlaybackReproductionTest {
    private val all = listOf(
        "ideal" to SimScenarios.IDEAL,
        "jitter" to SimScenarios.JITTER,
        "short ticks" to SimScenarios.SHORT_TICKS,
        "stalls" to SimScenarios.STALLS,
        "output latency" to SimScenarios.OUTPUT_LATENCY,
        "varying latency" to SimScenarios.VARYING_LATENCY,
        "slow prepare" to SimScenarios.SLOW_PREPARE,
        "realistic" to SimScenarios.REALISTIC,
        "busy" to SimScenarios.BUSY,
    )

    private fun records(
        name: String,
        scenario: Pair<SimDevice, SimTicks>,
        speed: Float = 1f,
        volume: Float = 1f,
    ): List<PlaybackRecord> =
        simulateAll(scenario, speed = speed, volume = volume).onEach { println("$name x$speed vol=$volume: $it") }

    /**
     * ふつうに鳴っている間: 位置を移さない・音量を変えない（利用者の音量のまま）・音を出すのは今の cue の再生器1つだけ・
     * 各クリップを1回だけ読み込む。
     */
    private fun assertSteady(r: PlaybackRecord) {
        assertEquals(r.toString(), 3, r.reads)
        assertEquals(r.toString(), 0, r.seeks)
        assertEquals(r.toString(), 0, r.fadesWhileSounding)
        assertEquals(r.toString(), 0, r.midClipCuts)
        assertEquals(r.toString(), 1, r.maxOutputs)
        assertEquals(r.toString(), 0, r.strayOutputs)
        assertEquals(r.toString(), r.cues, r.prepares)
        assertEquals(r.toString(), 0, r.onsetSkips)
        assertEquals(r.toString(), 0, r.earlyStarts)
        assertEquals(r.toString(), 0, r.pauses)
        assertEquals(r.toString(), 0, r.volumeCallsWhileSounding)
        assertEquals(r.toString(), 0, r.steadyGainMismatches)
    }

    @Test
    fun nothingIsCorrectedOrRampedDuringPlaybackWhateverTheTiming() {
        for (speed in listOf(0.85f, 1f, 1.2f)) {
            for ((name, scenario) in all) {
                for (r in records(name, scenario, speed)) assertSteady(r)
            }
        }
    }

    @Test
    fun theUserVolumeScalesTheVoiceWithoutTouchingSteadyPlayback() {
        // 50%・100%・200% のどれでも、鳴らし方は同じ（位置を移さない・途中で止めない・音を出すのは1つだけ・cue ごとに1回だけ読み込む）。
        // 音量は鳴らす前に再生器ごとに1回だけ渡し（100% なら渡さない）、鳴っている間は渡さない。
        // 聞こえる音量は最初から最後までそれを超えず、ExoPlayer の音量は 1 を超えない（200% は PCM に掛ける）
        for (volume in listOf(0.5f, 1f, 2f)) {
            for ((name, scenario) in all) {
                for (r in records(name, scenario, volume = volume)) {
                    assertSteady(r)
                    assertEquals(r.toString(), if (volume == 1f) 0 else 2, r.volumeCalls)
                    assertEquals(r.toString(), volume, r.maxHeardGain)
                    assertEquals(r.toString(), 1f, r.maxPlayerVolume)
                }
            }
        }
        for (r in records("ideal", SimScenarios.IDEAL, volume = 0.2f)) {
            assertSteady(r)
            assertEquals(r.toString(), 0.2f, r.maxHeardGain)
        }
    }

    @Test
    fun aMutedVoiceStillDrivesTheMatch() {
        // 0% でも音声は鳴らしていて（聞こえないだけ）、表示は音声に合わせて進む
        for ((name, scenario) in listOf("ideal" to SimScenarios.IDEAL, "realistic" to SimScenarios.REALISTIC)) {
            for (r in records(name, scenario, volume = 0f)) {
                assertSteady(r)
                assertEquals(r.toString(), 0f, r.maxHeardGain)
                assertTrue(r.toString(), r.maxDisplayAhead <= 45L)
            }
        }
    }

    @Test
    fun theDisplayFollowsWhatIsHeard() {
        // 表示（と CPU）はエンジンの時刻で動く。刻みの時点で、聞こえている音と 1 刻み分しかずれない。
        // 鳴り始めの遅れ（Bluetooth くらいの 200ms も）は、エンジンが音を待つので先走らない
        for ((name, scenario) in all) {
            for (r in records(name, scenario)) {
                assertTrue(r.toString(), r.maxDisplayAhead <= 45L)
                assertTrue(r.toString(), r.maxDisplayBehind <= 5L)
            }
        }
    }

    @Test
    fun aPlayerReportingAheadOfWhatItPlaysIsFollowedWithoutCorrections() {
        // 再生器の報告する位置が、実際に聞こえる位置より 150ms 先（報告が狂っている端末）。エンジンは報告に合わせるので
        // 表示はその分だけ先になり、読みの終わりで余韻の最後の 150ms を小さくして止めるが、鳴っている間に位置は移さない
        for (r in records("reported ahead", SimScenarios.REPORTED_AHEAD, volume = 0.5f)) {
            assertEquals(r.toString(), 0, r.seeks)
            assertEquals(r.toString(), 0, r.midClipCuts)
            assertEquals(r.toString(), 1, r.maxOutputs)
            assertEquals(r.toString(), r.cues, r.prepares)
            assertTrue(r.toString(), r.maxDisplayAhead in 150L..195L)
            assertTrue(r.toString(), r.fadesWhileSounding <= r.reads)
            // 余韻の最後を小さくして止めるときも、利用者の音量から下げる（いったん大きくならない）
            assertEquals(r.toString(), 0.5f, r.maxHeardGain)
            assertEquals(r.toString(), 0, r.volumeCallsWhileSounding)
        }
    }

    @Test
    fun theSpeedIsSetOncePerPlayerAndTheVoiceStopsOnlyAfterEachRead() {
        for ((name, scenario) in all) {
            for (r in records(name, scenario)) {
                assertEquals(r.toString(), 2, r.speedCalls)
                // 止めるのは、次のクリップに移ったとき（鳴り終えた再生器）と読みの音声が終わったときだけ
                assertTrue(r.toString(), r.stops <= r.cues + r.reads)
            }
        }
    }
}
