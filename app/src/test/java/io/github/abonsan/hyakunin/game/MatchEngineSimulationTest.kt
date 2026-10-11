package io.github.abonsan.hyakunin.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** ランダムに操作する試合を最後まで進め、どの経路でも破綻しないことを確かめる。 */
class MatchEngineSimulationTest {
    private fun checkInvariants(e: MatchEngine, config: GameConfig, emptyNos: Set<Int>) {
        val st = e.state
        val nos = st.playerNos + st.cpuNos
        assertEquals("duplicate cards", nos.size, nos.toSet().size)
        assertTrue(nos.size <= 2 * GameConfig.CARDS_PER_SIDE)
        assertTrue(st.pendingPlayerSends in 0..st.playerCards.size)
        assertTrue(st.readsDone <= st.totalReads)
        assertTrue(st.playerTakenCount + st.cpuTakenCount <= st.readsDone)
        if (config.ruleMode == RuleMode.SIMPLE) {
            // 簡易ルールでは札は移動しない
            assertTrue(st.playerCards.size <= GameConfig.CARDS_PER_SIDE)
            assertTrue(st.cpuCards.size <= GameConfig.CARDS_PER_SIDE)
        }
        if (st.phase is MatchPhase.Result || st.phase is MatchPhase.Waiting) {
            // 場の札は、読み終えた札のうち場にあったものの数だけ減っている
            val emptyReads = e.readOrder.take(st.readsDone).count { it in emptyNos }
            val fieldReads = st.readsDone - emptyReads
            assertEquals(2 * GameConfig.CARDS_PER_SIDE - fieldReads, nos.size)
        }
        if (st.phase !is MatchPhase.Reading) assertTrue(!st.playerLockedOut)
        // 待ちの残り時間は、自動で読み始める Waiting の間だけ。タップ待ちでは出ない
        if (st.phase == MatchPhase.Waiting && config.readStartMode == ReadStartMode.AUTO) {
            val wait = if (st.readsDone == 0) config.memorizeTime.millis else config.readInterval.millis
            assertTrue(st.waitRemainingMillis!! in 0..wait)
        } else {
            assertNull(st.waitRemainingMillis)
        }
        assertEquals(st.phase == MatchPhase.Waiting && st.reading == null, st.isMemorizing)
        st.reading?.let {
            assertTrue(it.revealedKana in 0..it.totalKanaCount)
            assertTrue(it.kamiKanaCount <= it.totalKanaCount)
        }
    }

    private fun checkWinner(e: MatchEngine, config: GameConfig) {
        val st = e.state
        val winner = (st.phase as MatchPhase.Finished).winner
        when (config.ruleMode) {
            RuleMode.COMPETITIVE -> {
                val p = st.playerCards.size
                val c = st.cpuCards.size
                when (winner) {
                    Side.PLAYER -> assertTrue("p=$p c=$c", p == 0 || p < c)
                    Side.CPU -> assertTrue("p=$p c=$c", c == 0 || c < p)
                    null -> assertEquals("p=$p c=$c", p, c)
                }
            }
            RuleMode.SIMPLE -> when (winner) {
                Side.PLAYER -> assertTrue(st.playerTakenCount > st.cpuTakenCount)
                Side.CPU -> assertTrue(st.cpuTakenCount > st.playerTakenCount)
                null -> assertEquals(st.playerTakenCount, st.cpuTakenCount)
            }
        }
    }

    private fun play(config: GameConfig, seed: Int) {
        val e = MatchEngine.create(config, Random(seed), SYNTHETIC_POEMS)
        val field = (e.state.playerNos + e.state.cpuNos).toSet()
        val emptyNos = e.readOrder.filter { it !in field }.toSet()
        val player = Random(seed * 7919 + 13)
        var t = 0L
        var steps = 0
        while (e.state.phase !is MatchPhase.Finished) {
            check(++steps < 50_000) { "game does not finish: seed=$seed config=$config" }
            t += player.nextLong(20L, 500L)
            e.advanceTo(t)

            val st = e.state
            when (st.phase) {
                MatchPhase.Reading -> when (player.nextInt(10)) {
                    0, 1, 2 -> st.reading?.let { r ->
                        // 出札を取りに行く（空札なら場にないので無視される）
                        val side = if (r.poem.no in st.playerNos) Side.PLAYER else Side.CPU
                        e.tapCard(side, r.poem.no)
                    }
                    3 -> {
                        val any = st.playerCards.map { Side.PLAYER to it.poemNo } +
                            st.cpuCards.map { Side.CPU to it.poemNo }
                        if (any.isNotEmpty()) {
                            val (side, no) = any[player.nextInt(any.size)]
                            e.tapCard(side, no)
                        }
                    }
                    else -> Unit
                }
                MatchPhase.Result -> {
                    if (st.pendingPlayerSends > 0) {
                        e.tapCard(Side.PLAYER, st.playerCards[player.nextInt(st.playerCards.size)].poemNo)
                    } else if (player.nextInt(3) == 0) {
                        e.next()
                    }
                }
                MatchPhase.Waiting -> when {
                    // タップで読み始めるモードは、プレイヤーが押さないと進まない
                    config.readStartMode == ReadStartMode.TAP -> if (player.nextInt(3) == 0) e.startReading()
                    player.nextInt(20) == 0 -> e.startReading()
                    else -> e.tapCard(Side.PLAYER, 1)
                }
                else -> e.tapCard(Side.PLAYER, 1)
            }
            checkInvariants(e, config, emptyNos)
        }
        checkWinner(e, config)
    }

    @Test
    fun randomGamesAlwaysFinishWithConsistentState() {
        for (mode in RuleMode.entries) {
            for (level in CpuLevel.entries) {
                for (seed in 0 until 12) {
                    val rnd = Random(seed)
                    val config = GameConfig(
                        ruleMode = mode,
                        emptyCardCount = rnd.nextInt(0, GameConfig.MAX_EMPTY_CARDS + 1),
                        cpuLevel = level,
                        readingSpeed = ReadingSpeed.entries[seed % 3],
                        readStartMode = ReadStartMode.entries[seed % 2],
                        memorizeTime = MemorizeTime.entries[seed % MemorizeTime.entries.size],
                        readInterval = ReadInterval.entries[seed % ReadInterval.entries.size],
                    )
                    play(config, seed)
                }
            }
        }
    }

    @Test
    fun gamesWithoutEmptyCardsEnd() {
        for (mode in RuleMode.entries) {
            for (seed in 100 until 130) {
                play(GameConfig(ruleMode = mode, emptyCardCount = 0, cpuLevel = CpuLevel.MASTER), seed)
            }
        }
    }
}
