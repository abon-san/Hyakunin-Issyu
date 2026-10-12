package io.github.abonsan.hyakunin.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 読み上げ音声の鳴らし方を、端末の遅れとメインスレッドの混み具合を変えて再現する（[simulatePlayback]）。
 *
 * v0.4.0 の追従の記録: エンジンは実時間（1 刻み 20〜100ms に丸める）で進み、再生側は 400ms ごとに位置を測って
 * 120ms を超えるずれを seek で直し、鳴り始めの遅れを学習して早めに鳴らす。ExoPlayer の seek は AudioTrack を
 * 作り直す（DefaultAudioSink.flush）ので、seek のたびに音が一瞬途切れ、音量が落ちてから戻る。
 */
class PlaybackReproductionTest {
    private fun records(name: String, scenario: Pair<SimDevice, SimTicks>, speed: Float = 1f): List<PlaybackRecord> =
        simulateAll(scenario, speed = speed).onEach { println("$name x$speed: $it") }

    @Test
    fun idealTimingNeedsNoCorrection() {
        for (r in records("ideal", SimScenarios.IDEAL)) {
            assertEquals(r.toString(), 3, r.reads)
            assertEquals(r.toString(), 0, r.seeks)
            assertEquals(r.toString(), 0L, r.maxDisplayAhead)
            assertEquals(r.toString(), 0L, r.maxDisplayBehind)
        }
    }

    @Test
    fun tickJitterAloneDoesNotSeek() {
        // 刻みが揺れても、エンジンは測った実時間で進むので位置は直さない（表示が刻みの分だけ前後するだけ）
        for (r in records("jitter", SimScenarios.JITTER)) {
            assertEquals(r.toString(), 0, r.seeks)
            assertTrue(r.toString(), r.maxDisplayAhead <= 60L && r.maxDisplayBehind <= 60L)
        }
    }

    @Test
    fun ticksMeasuredShortMakeTheEngineRunAheadOfTheVoice() {
        // 19ms と測れた刻みを 20ms に切り上げるので、エンジンが音より速く進む。下の句 1 つの間に 80ms 以上先へずれる
        for (r in records("short ticks", SimScenarios.SHORT_TICKS)) {
            assertTrue(r.toString(), r.maxDisplayAhead >= 80L)
        }
    }

    @Test
    fun mainThreadStallsMakeTheVoiceJumpBack() {
        // 250ms 止まると、エンジンは 100ms しか進まず音より 150ms 以上遅れる。再生側は音を 150ms 以上巻き戻す
        for (r in records("stalls", SimScenarios.STALLS)) {
            assertTrue(r.toString(), r.driftSeeks >= 10)
            assertTrue(r.toString(), r.maxDriftSeekJump >= 150L)
        }
    }

    @Test
    fun aPositionReportedAheadMakesThePlayerSeekEvery400Millis() {
        // 報告される位置が 150ms 先だと、学習した遅れ（0 より小さくならない）では吸収できず、落ち着くたびに直し続ける
        for (r in records("reported ahead", SimScenarios.REPORTED_AHEAD)) {
            assertTrue(r.toString(), r.driftSeeks >= 100)
        }
    }

    @Test
    fun outputLatencyIsLearnedBySeekingAndCutsTheStartOfClips() {
        // ExoPlayer の位置は出力の遅れを含めて聞こえている位置なのに、その遅れを学習して足す:
        // 最初は seek で直し、その後のクリップは早めに鳴らし、始まりの時刻に予告されたクリップは学習した分だけ途中から鳴らす
        for (r in records("output latency", SimScenarios.OUTPUT_LATENCY)) {
            assertTrue(r.toString(), r.driftSeeks >= 1)
            assertTrue(r.toString(), r.earlyStarts >= 5)
            // 本物のクリップの最初の仮名は 107ms から。それより先から鳴らすと出だしの音が欠ける
            assertTrue(r.toString(), r.onsetSkips >= 3 && r.maxOnsetSkip >= 150L)
        }
    }

    @Test
    fun varyingStartLatencyKeepsSeeking() {
        for (r in records("varying latency", SimScenarios.VARYING_LATENCY)) {
            assertTrue(r.toString(), r.seeks >= 3)
        }
    }

    @Test
    fun slowLoadingMakesTheFirstClipOfEachReadStartLate() {
        // 読み（前置き）の最初のクリップと序歌の下の句の 2 回目は、始まりの時刻に予告されてから読み込むので、
        // 読み込みの分だけ遅れ、途中から鳴らす
        for (r in records("slow prepare", SimScenarios.SLOW_PREPARE)) {
            assertEquals(r.toString(), 4, r.lateStartSeeks)
            assertEquals(r.toString(), 4, r.onsetSkips)
        }
    }

    @Test
    fun aRealisticPhoneSeeksSeveralTimesPerRead() {
        for (speed in listOf(0.85f, 1f, 1.2f)) {
            for (r in records("realistic", SimScenarios.REALISTIC, speed)) {
                assertTrue(r.toString(), r.seeks >= 4)
            }
        }
        for (r in records("busy", SimScenarios.BUSY)) {
            assertTrue(r.toString(), r.driftSeeks >= 2)
            assertTrue(r.toString(), r.maxDisplayAhead + r.maxDisplayBehind >= 120L)
        }
    }

    @Test
    fun noReloadFadeFocusChangeSpeedChangeOrSecondOutputDuringPlayback() {
        // ほかの疑い（読み直し・途中で小さくする・音声フォーカスの出し入れ・再生速度の設定し直し・2つ同時に鳴る）は起きていない
        val all = listOf(
            SimScenarios.IDEAL, SimScenarios.JITTER, SimScenarios.SHORT_TICKS, SimScenarios.STALLS,
            SimScenarios.REPORTED_AHEAD, SimScenarios.OUTPUT_LATENCY, SimScenarios.VARYING_LATENCY,
            SimScenarios.SLOW_PREPARE, SimScenarios.REALISTIC, SimScenarios.BUSY,
        )
        for (scenario in all) {
            for (r in simulateAll(scenario)) {
                assertEquals(r.toString(), r.cues, r.prepares)
                assertEquals(r.toString(), 0, r.midClipCuts)
                // 止める（小さくしていく）のは、読みの音声が終わったときだけ
                assertEquals(r.toString(), r.reads, r.stops)
                // 一時停止しない（音声フォーカスを手放さない・求め直さない）
                assertEquals(r.toString(), 0, r.pauses)
                assertEquals(r.toString(), 1, r.speedCalls)
                assertEquals(r.toString(), 1, r.maxOutputs)
            }
        }
    }
}
