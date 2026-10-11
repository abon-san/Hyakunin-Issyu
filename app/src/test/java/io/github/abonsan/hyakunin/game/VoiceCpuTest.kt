package io.github.abonsan.hyakunin.game

import io.github.abonsan.hyakunin.game.voice.fakeVoiceBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 音声の時刻表に CPU の行動とプレイヤーの反応時間が従うこと（CpuBehaviorTest の音声版）。
 * 合成の100首の最初の決まり字は 2 文字で、上の句の 2 文字目は音声で 300ms（再生速度 1.0 のとき）。
 */
class VoiceCpuTest {
    private fun voiceEngine(
        order: List<Int> = listOf(6, 11),
        profile: CpuProfile,
        timing: ReadTiming = voiceTiming(),
        random: Random = ZeroRandom,
    ) = layoutEngine(
        order = order,
        profile = profile,
        timing = timing,
        random = random,
        readingStyle = ReadingStyle.SIMPLE,
    )

    @Test
    fun cpuTakesAtKimarijiPlusReactionUnderVoiceTimings() {
        val e = voiceEngine(profile = steadyCpu(300L))
        val s = e.beginRead()
        assertEquals(2, s.kimarijiLength)
        // 決まり字の 2 文字目は音声で 300ms、反応 300ms なので 600ms
        assertEquals(s.startMillis + 300L, s.kanaAt[s.kimarijiLength - 1])
        assertEquals(CpuPlan.Take(s.kanaAt[s.kimarijiLength - 1] + 300L), s.cpuPlan)
        assertEquals(CpuPlan.Take(s.startMillis + 600L), s.cpuPlan)

        e.advanceToOffset(s, 599L)
        assertEquals(MatchPhase.Reading, e.state.phase)
        e.advanceToOffset(s, 600L)
        val st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        assertEquals(Side.CPU, st.lastResult!!.takenBy)
        // 100, 300, 500 の 3 文字が鳴ったところで取った
        assertEquals(3, st.lastResult!!.revealedAtTake)
    }

    @Test
    fun cpuTakeTimeScalesWithThePlaybackSpeedButTheReactionDoesNot() {
        // 決まり字が鳴る時刻は 300 / 速度。反応時間（300）はそのまま足す
        val expectedKimariji = mapOf(0.5f to 600L, 1.0f to 300L, 2.0f to 150L)
        for ((speed, kimarijiOffset) in expectedKimariji) {
            val e = voiceEngine(profile = steadyCpu(300L), timing = voiceTiming(playbackSpeed = speed))
            val s = e.beginRead()
            val plan = s.cpuPlan as CpuPlan.Take
            assertEquals("speed=$speed", kimarijiOffset, s.kanaAt[s.kimarijiLength - 1] - s.startMillis)
            assertEquals("speed=$speed", 300L, plan.atMillis - s.kanaAt[s.kimarijiLength - 1])
            assertEquals("speed=$speed", kimarijiOffset + 300L, plan.atMillis - s.startMillis)

            e.advanceToOffset(s, kimarijiOffset + 299L)
            assertEquals("speed=$speed", MatchPhase.Reading, e.state.phase)
            e.advanceToOffset(s, kimarijiOffset + 300L)
            assertEquals("speed=$speed", Side.CPU, e.state.lastResult!!.takenBy)
        }
    }

    @Test
    fun cpuTakeTimeUsesTheReadingSpeedMapping() {
        // ReadingSpeed が音声の再生速度になり、速いほど CPU も早く取る
        val offsets = ReadingSpeed.entries.map { speed ->
            val e = voiceEngine(profile = steadyCpu(300L), timing = ReadTiming.of(speed, fakeVoiceBank()))
            val s = e.beginRead()
            val kimariji = s.kanaAt[s.kimarijiLength - 1] - s.startMillis
            assertEquals(speed.toString(), kimariji + 300L, (s.cpuPlan as CpuPlan.Take).atMillis - s.startMillis)
            kimariji
        }
        // SLOW > NORMAL > FAST
        assertEquals(offsets.sortedDescending(), offsets)
        assertEquals(300L, offsets[ReadingSpeed.entries.indexOf(ReadingSpeed.NORMAL)])
    }

    @Test
    fun reactionTimeIsWithinTheLevelsRangeUnderVoiceTimings() {
        for (level in CpuLevel.entries) {
            val profile = CpuProfile.of(level)
            for (speed in listOf(0.85f, 1.0f, 1.2f)) {
                var takes = 0
                for (seed in 0 until 100) {
                    val e = voiceEngine(
                        order = listOf(1),
                        profile = profile,
                        timing = voiceTiming(playbackSpeed = speed),
                        random = Random(seed),
                    )
                    val s = e.beginRead()
                    val plan = s.cpuPlan as? CpuPlan.Take ?: continue
                    takes++
                    val reaction = plan.atMillis - s.kanaAt[s.kimarijiLength - 1]
                    assertTrue(
                        "$level speed=$speed reaction=$reaction",
                        reaction in profile.reactionMinMillis..profile.reactionMaxMillis,
                    )
                }
                assertTrue("$level speed=$speed takes=$takes", takes > 70)
            }
        }
    }

    @Test
    fun cpuTakesAtLatestHalfASecondAfterTheLastKanaOfTheShimoNoKu() {
        // 反応が極端に遅い CPU でも、下の句の最後の仮名（余韻の前）の 500ms 後には取る
        val e = voiceEngine(order = listOf(1), profile = SLOW_CPU)
        val s = e.beginRead()
        assertEquals(6800L, s.shimoEnd - s.startMillis)
        assertEquals(CpuPlan.Take(s.shimoEnd + 500L), s.cpuPlan)
        e.advanceToOffset(s, 7299L)
        assertEquals(MatchPhase.Reading, e.state.phase)
        e.advanceToOffset(s, 7300L)
        assertEquals(Side.CPU, e.state.lastResult!!.takenBy)

        // 2倍速では下の句の最後の仮名が 3400ms なので、3900ms
        val fast = voiceEngine(order = listOf(1), profile = SLOW_CPU, timing = voiceTiming(playbackSpeed = 2.0f))
        val fs = fast.beginRead()
        assertEquals(CpuPlan.Take(fs.startMillis + 3400L + 500L), fs.cpuPlan)
    }

    @Test
    fun cpuFaultFollowsTheVoiceTimeline() {
        // 友札は先頭 1 文字が共通。反応時間 0 なので、1 文字目が鳴る時刻に触れる
        val e = voiceEngine(order = listOf(1), profile = FAULTY_CPU)
        val s = e.beginRead()
        val plan = s.cpuPlan as CpuPlan.Fault
        assertEquals(s.startMillis + 100L, plan.atMillis)
        assertEquals(s.kanaAt[0], plan.atMillis)

        val fast = voiceEngine(order = listOf(1), profile = FAULTY_CPU, timing = voiceTiming(playbackSpeed = 2.0f))
        val fs = fast.beginRead()
        assertEquals(fs.startMillis + 50L, fs.cpuPlan!!.atMillis)
    }

    @Test
    fun playersReactionWindowFollowsTheVoiceTimeline() {
        // 決まり字（2 文字目）は 300ms から。その前に取ると 1 文字、直前（299ms）でもまだ 2 文字目は鳴っていない
        val early = voiceEngine(order = listOf(1), profile = SLOW_CPU)
        val es = early.beginRead()
        early.advanceToOffset(es, 299L)
        early.tapCard(Side.PLAYER, 1)
        assertEquals(1, early.state.lastResult!!.revealedAtTake)

        val onTime = voiceEngine(order = listOf(1), profile = SLOW_CPU)
        val os = onTime.beginRead()
        onTime.advanceToOffset(os, 300L)
        onTime.tapCard(Side.PLAYER, 1)
        assertEquals(os.kimarijiLength, onTime.state.lastResult!!.revealedAtTake)

        // 2倍速なら決まり字は 150ms から
        val fast = voiceEngine(order = listOf(1), profile = SLOW_CPU, timing = voiceTiming(playbackSpeed = 2.0f))
        val fs = fast.beginRead()
        fast.advanceToOffset(fs, 149L)
        fast.tapCard(Side.PLAYER, 1)
        assertEquals(1, fast.state.lastResult!!.revealedAtTake)
    }

    @Test
    fun cpuIsAnchoredToTheReadStartAfterTheLeadIn() {
        // 競技かるた式: CPU の予定は前置きではなく、上の句の開始から数える
        val e = layoutEngine(
            order = listOf(6, 11),
            profile = steadyCpu(300L),
            timing = voiceTiming(),
            readingStyle = ReadingStyle.COMPETITIVE,
        )
        val s = e.beginReadThroughLeadIn()
        assertEquals(FIRST_LEAD_IN_MILLIS, s.startMillis)
        assertEquals(CpuPlan.Take(s.startMillis + 600L), s.cpuPlan)
        assertNotNull(s.cpuPlan)
        e.advanceToOffset(s, 600L)
        assertEquals(Side.CPU, e.state.lastResult!!.takenBy)
    }
}
