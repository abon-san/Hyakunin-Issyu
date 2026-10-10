package io.github.abonsan.hyakunin.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CpuBehaviorTest {
    @Test
    fun profilesMatchTheSpecification() {
        assertEquals(CpuProfile(1400L, 2600L, 0.10), CpuProfile.of(CpuLevel.BEGINNER))
        assertEquals(CpuProfile(750L, 1500L, 0.05), CpuProfile.of(CpuLevel.INTERMEDIATE))
        assertEquals(CpuProfile(350L, 800L, 0.02), CpuProfile.of(CpuLevel.ADVANCED))
        assertEquals(CpuProfile(150L, 400L, 0.0), CpuProfile.of(CpuLevel.MASTER))
    }

    @Test
    fun reactionTimeIsWithinTheLevelsRange() {
        for (level in CpuLevel.entries) {
            val profile = CpuProfile.of(level)
            var takes = 0
            var min = Long.MAX_VALUE
            var max = Long.MIN_VALUE
            for (seed in 0 until 300) {
                val e = layoutEngine(order = listOf(1), profile = profile, random = Random(seed))
                val s = e.beginRead()
                val plan = s.cpuPlan
                if (plan !is CpuPlan.Take) continue
                takes++
                // 決まり字（2文字）が表示された時刻 + 反応時間
                val kimarijiAt = s.kanaAt[s.kimarijiLength - 1]
                val reaction = plan.atMillis - kimarijiAt
                assertTrue("$level reaction=$reaction", reaction in profile.reactionMinMillis..profile.reactionMaxMillis)
                min = minOf(min, reaction)
                max = maxOf(max, reaction)
            }
            assertTrue("$level takes=$takes", takes > 200)
            // 範囲全体を使っている（定数ではない）
            assertTrue("$level min=$min", min < profile.reactionMinMillis + (profile.reactionMaxMillis - profile.reactionMinMillis) / 4)
            assertTrue("$level max=$max", max > profile.reactionMaxMillis - (profile.reactionMaxMillis - profile.reactionMinMillis) / 4)
        }
    }

    @Test
    fun cpuTakesAtKimarijiPlusReaction() {
        val e = layoutEngine(order = listOf(6, 11), profile = steadyCpu(300L))
        val s = e.beginRead()
        // 決まり字は2文字（200ms）、反応 300ms なので 500ms で取る
        assertEquals(CpuPlan.Take(s.startMillis + 500L), s.cpuPlan)
        e.advanceToOffset(s, 499L)
        assertEquals(MatchPhase.Reading, e.state.phase)
        e.advanceToOffset(s, 500L)
        val st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        assertEquals(Side.CPU, st.lastResult!!.takenBy)
        assertEquals(5, st.lastResult!!.revealedAtTake)
    }

    @Test
    fun cpuTakesAtLatestHalfSecondAfterLowerPhrase() {
        // 決まり字が上の句の最後にあり、下の句が短いと、遅い CPU でも下の句の後 500ms で取る
        val poems = listOf(testPoem(1, "あかい", "ひ"), testPoem(2, "あかう", "ふ"))
        for (seed in 0 until 50) {
            val e = layoutEngine(
                order = listOf(1),
                player = listOf(1),
                cpu = listOf(2),
                profile = CpuProfile(1400L, 2600L, 0.0),
                random = Random(seed),
                poems = poems,
            )
            val s = e.beginRead()
            assertEquals(3, s.kimarijiLength)
            // 上の句 3文字 = 300ms、下の句は 300ms の間 + 1文字 = 700ms
            assertEquals(700L, s.shimoEnd - s.startMillis)
            assertEquals(CpuPlan.Take(s.shimoEnd + 500L), s.cpuPlan)
        }
        val e = layoutEngine(
            order = listOf(1),
            player = listOf(1),
            cpu = listOf(2),
            profile = CpuProfile(1400L, 2600L, 0.0),
            poems = poems,
        )
        val s = e.beginRead()
        e.advanceToOffset(s, 1199L)
        assertEquals(MatchPhase.Reading, e.state.phase)
        e.advanceToOffset(s, 1200L)
        assertEquals(Side.CPU, e.state.lastResult!!.takenBy)
    }

    @Test
    fun masterNeverFaults() {
        for (seed in 0 until 500) {
            val e = layoutEngine(
                order = listOf(1),
                profile = CpuProfile.of(CpuLevel.MASTER),
                random = Random(seed),
            )
            assertTrue(e.beginRead().cpuPlan is CpuPlan.Take)
        }
        // 空札でも何もしない
        for (seed in 0 until 100) {
            val e = layoutEngine(
                order = listOf(11),
                profile = CpuProfile.of(CpuLevel.MASTER),
                random = Random(seed),
            )
            assertNull(e.beginRead().cpuPlan)
        }
    }

    @Test
    fun faultRateFollowsTheLevel() {
        val trials = 4000
        for (level in listOf(CpuLevel.BEGINNER, CpuLevel.INTERMEDIATE, CpuLevel.ADVANCED)) {
            val profile = CpuProfile.of(level)
            var faults = 0
            for (seed in 0 until trials) {
                val e = layoutEngine(order = listOf(1), profile = profile, random = Random(seed))
                if (e.beginRead().cpuPlan is CpuPlan.Fault) faults++
            }
            val rate = faults.toDouble() / trials
            assertTrue("$level rate=$rate", Math.abs(rate - profile.faultProbability) < 0.02)
        }
    }

    @Test
    fun faultTargetsTheCardWithTheLongestCommonPrefix() {
        // 読まれる札 1（空札）の友札: 2 は5文字、3 は2文字、4 は共通なし
        val poems = listOf(
            testPoem(1, "あさぼらけ　ありあけの"),
            testPoem(2, "あさぼらけ　うじのかわぎり"),
            testPoem(3, "あさじうの　のべのしのはら"),
            testPoem(4, "はるすぎて　なつきにけらし"),
        )
        val e = layoutEngine(
            order = listOf(1),
            player = listOf(3),
            cpu = listOf(2, 4),
            profile = CpuProfile(800L, 800L, 1.0),
            poems = poems,
        )
        val s = e.beginRead()
        assertTrue(s.isEmpty)
        // 共通部分（5文字）を表示し終えた時刻 + 反応時間の半分
        assertEquals(CpuPlan.Fault(s.startMillis + 500L + 400L, 2, Side.CPU), s.cpuPlan)

        e.advanceToOffset(s, 899L)
        assertEquals(0, e.state.cpuFaultCount)
        e.advanceToOffset(s, 900L)
        assertEquals(1, e.state.cpuFaultCount)
        // 触れた後は取らない（空札なので元から取れないが、お手つきは1回だけ）
        e.advanceToOffset(s, 10_000L)
        assertEquals(1, e.state.cpuFaultCount)
        assertEquals(0, e.state.cpuTakenCount)
    }

    @Test
    fun faultTargetIsChosenAtRandomAmongEqualPrefixes() {
        // 札 1 と 2〜10 はどれも先頭1文字だけ共通 → 友札の候補は9枚
        val targets = (0 until 100).map { seed ->
            val e = layoutEngine(order = listOf(11), player = listOf(2, 3, 4, 5, 6), cpu = listOf(7, 8, 9, 10, 1),
                profile = FAULTY_CPU, random = Random(seed))
            // 11 は「いか…」。友札は先頭が「い」の歌だが、場にはないので友札なし
            (e.beginRead().cpuPlan as? CpuPlan.Fault)?.poemNo
        }
        assertTrue(targets.all { it == null })

        val picked = (0 until 100).map { seed ->
            val e = layoutEngine(order = listOf(1), player = listOf(2, 3, 4, 5, 6), cpu = listOf(7, 8, 9, 10, 11),
                profile = FAULTY_CPU, random = Random(seed))
            (e.beginRead().cpuPlan as CpuPlan.Fault).poemNo
        }
        assertTrue(picked.all { it in 2..10 })
        assertTrue(picked.toSet().size > 3)
    }

    @Test
    fun noFaultWithoutAFriendCard() {
        val poems = listOf(
            testPoem(1, "あきのたの"),
            testPoem(2, "はるすぎて"),
            testPoem(3, "ふゆのよの"),
        )
        // 場の札は 2 と 3 だけ。1 と先頭が同じ札がないので、必ずお手つきするはずの CPU も何もしない
        val e = layoutEngine(
            order = listOf(1),
            player = listOf(2),
            cpu = listOf(3),
            profile = FAULTY_CPU,
            poems = poems,
        )
        assertNull(e.beginRead().cpuPlan)

        // 出札が場にあれば普通に取る
        val e2 = layoutEngine(
            order = listOf(2),
            player = listOf(2),
            cpu = listOf(3),
            profile = FAULTY_CPU,
            poems = poems,
        )
        assertTrue(e2.beginRead().cpuPlan is CpuPlan.Take)
    }

    @Test
    fun cpuFaultOnSameSideIsSafeAndCpuDoesNotTakeAfterwards() {
        // 出札 1 と同じ自陣の友札 2 に CPU が触れる（セーフ）。その読みでは取らない
        val e = layoutEngine(order = listOf(1), profile = FAULTY_CPU)
        val s = e.beginRead()
        e.advanceToOffset(s, 10_000L)
        val st = e.state
        assertEquals(MatchPhase.Reading, st.phase)
        assertEquals(0, st.cpuFaultCount)
        assertEquals(0, st.cpuTakenCount)
        assertTrue(s.cpuLocked)
        assertNull(s.cpuPlan)
        assertEquals(setOf(Side.CPU), s.safeMisses)
    }

    @Test
    fun cpuFaultOnOtherSideCountsAsFault() {
        // 出札 6 は敵陣。CPU は自陣（プレイヤー側）の友札に触れるのでお手つき
        val e = layoutEngine(order = listOf(6), profile = FAULTY_CPU)
        val s = e.beginRead()
        e.advanceToOffset(s, 200L)
        assertEquals(1, e.state.cpuFaultCount)
        assertEquals(setOf(Side.CPU), s.faults)
    }

    @Test
    fun playerWinsTheRaceByTakingEarlier() {
        val e = layoutEngine(order = listOf(1, 11), profile = steadyCpu(300L))
        val s = e.beginRead()
        // CPU は 500ms で取る。その1ms前にプレイヤーが取る
        e.advanceToOffset(s, 499L)
        e.tapCard(Side.PLAYER, 1)
        val st = e.state
        assertEquals(Side.PLAYER, st.lastResult!!.takenBy)
        assertEquals(4, st.lastResult!!.revealedAtTake)
        assertNull(s.cpuPlan)

        // CPU の予定時刻を過ぎても何も起きない
        e.advanceToOffset(s, 5_000L)
        assertEquals(1, e.state.playerTakenCount)
        assertEquals(0, e.state.cpuTakenCount)
        assertEquals(MatchPhase.Result, e.state.phase)
    }

    @Test
    fun cpuWinsTheRaceByTakingEarlier() {
        val e = layoutEngine(order = listOf(1, 11), profile = steadyCpu(300L))
        val s = e.beginRead()
        e.advanceToOffset(s, 500L)
        assertEquals(Side.CPU, e.state.lastResult!!.takenBy)
        // プレイヤーが遅れて触れても、取ることもお手つきになることもない
        e.tapCard(Side.PLAYER, 1)
        e.tapCard(Side.CPU, 7)
        val st = e.state
        assertEquals(0, st.playerTakenCount)
        assertEquals(0, st.playerFaultCount)
        assertEquals(1, st.cpuTakenCount)
    }

    @Test
    fun cpuFaultIsCancelledWhenPlayerTakesFirst() {
        // 友札へ触れるのは 1100ms の予定。その前にプレイヤーが取る
        val e = layoutEngine(order = listOf(1, 11), profile = CpuProfile(2000L, 2000L, 1.0))
        val s = e.beginRead()
        assertNotNull(s.cpuPlan as? CpuPlan.Fault)
        e.advanceToOffset(s, 1000L)
        e.tapCard(Side.PLAYER, 1)
        e.advanceToOffset(s, 5_000L)
        val st = e.state
        assertEquals(0, st.cpuFaultCount)
        assertEquals(setOf<Side>(), st.lastResult!!.faults)
        assertEquals(setOf<Side>(), st.lastResult!!.safeMisses)
    }

    @Test
    fun lowerLevelCpuIsSlowerOnAverage() {
        fun averageTakeOffset(level: CpuLevel): Double {
            var sum = 0L
            var n = 0
            for (seed in 0 until 200) {
                val e = layoutEngine(order = listOf(1), profile = CpuProfile.of(level), random = Random(seed))
                val s = e.beginRead()
                val plan = s.cpuPlan as? CpuPlan.Take ?: continue
                sum += plan.atMillis - s.startMillis
                n++
            }
            return sum.toDouble() / n
        }
        val averages = CpuLevel.entries.map { averageTakeOffset(it) }
        assertEquals(averages.sortedDescending(), averages)
    }
}
