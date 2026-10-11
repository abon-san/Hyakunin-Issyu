package io.github.abonsan.hyakunin.game

import io.github.abonsan.hyakunin.game.voice.fakeVoiceBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 今鳴らすべき音声（AudioCue）、札を取ったあとの音声、音声で読む空札。fakeVoiceBank の時刻は VoiceFixtures.kt の先頭を参照。 */
class VoiceAudioTest {
    private val memorize = MemorizeTime.SEC_10.millis
    private val interval = ReadInterval.SEC_2.millis

    /** 最初の読みの上の句が始まる時刻（札を覚える時間 + 序歌の前置き）。 */
    private val firstRead = memorize + FIRST_LEAD_IN_MILLIS

    private fun voiceEngine(
        order: List<Int> = listOf(1, 2),
        timing: ReadTiming = voiceTiming(),
        profile: CpuProfile = SLOW_CPU,
        player: List<Int> = listOf(1, 2, 3, 4, 5),
        readingStyle: ReadingStyle = ReadingStyle.COMPETITIVE,
    ) = layoutEngine(
        order = order,
        timing = timing,
        profile = profile,
        player = player,
        readingStyle = readingStyle,
    )

    private fun MatchEngine.cueAt(t: Long): AudioCue? {
        advanceTo(t)
        return state.audio
    }

    private fun AudioCue?.assertCue(asset: String, start: Long, id: Long, message: String) {
        assertNotNull(message, this)
        assertEquals(message, asset, this!!.asset)
        assertEquals(message, start, startedAtMillis)
        assertEquals(message, id, cueId)
        assertEquals(message, 1.0f, playbackSpeed)
    }

    @Test
    fun cuesFollowTheLeadInThenTheKamiAndShimoNoKu() {
        val e = voiceEngine()
        // 待ちのカウントダウンの間は何も鳴らさない
        assertNull(e.state.audio)
        assertNull(e.cueAt(memorize - 1))

        val t0 = memorize
        // 序歌の上の句 [0, 1900]
        e.cueAt(t0).assertCue("voice/joka_kami.ogg", t0, 1, "joka kami")
        e.cueAt(t0 + 1899).assertCue("voice/joka_kami.ogg", t0, 1, "joka kami end")
        // 序歌の下の句 1 回目は 2400 から。前のクリップが終わると、次のクリップが（先の時刻で）予告される
        val upcoming = e.cueAt(t0 + 1900)
        upcoming.assertCue("voice/joka_shimo.ogg", t0 + 2400, 2, "joka shimo 1 announced")
        assertFalse(upcoming!!.hasStartedAt(e.nowMillis))
        assertEquals(-500L, upcoming.positionAt(e.nowMillis))
        e.cueAt(t0 + 2400).assertCue("voice/joka_shimo.ogg", t0 + 2400, 2, "joka shimo 1")
        assertEquals(0L, e.state.audio!!.positionAt(e.state.engineTimeMillis))
        e.cueAt(t0 + 6799).assertCue("voice/joka_shimo.ogg", t0 + 2400, 2, "joka shimo 1 end")
        assertEquals(4399L, e.state.audio!!.positionAt(e.state.engineTimeMillis))
        // 同じファイルをもう一度読むが、cueId は別
        e.cueAt(t0 + 6800).assertCue("voice/joka_shimo.ogg", t0 + 6800, 3, "joka shimo 2")
        e.cueAt(t0 + 11199).assertCue("voice/joka_shimo.ogg", t0 + 6800, 3, "joka shimo 2 end")
        // 間合いの間は、次に鳴る上の句が先の時刻で予告される
        val read = t0 + FIRST_LEAD_IN_MILLIS
        e.cueAt(t0 + 11200).assertCue("voice/p001_kami.ogg", read, 4, "kami announced in ma")
        e.cueAt(read - 1).assertCue("voice/p001_kami.ogg", read, 4, "kami announced")
        assertFalse(e.state.audio!!.hasStartedAt(e.nowMillis))

        // 読みの始まり。同じ cue のまま、鳴り始める
        e.cueAt(read).assertCue("voice/p001_kami.ogg", read, 4, "kami")
        assertEquals(MatchPhase.Reading, e.state.phase)
        assertTrue(e.state.audio!!.hasStartedAt(e.nowMillis))
        e.cueAt(read + 3499).assertCue("voice/p001_kami.ogg", read, 4, "kami end")
        // 下の句のクリップは上の句の 3500 + 間 500 のあと
        e.cueAt(read + 3500).assertCue("voice/p001_shimo.ogg", read + 4000, 5, "shimo announced")
        e.cueAt(read + 4000).assertCue("voice/p001_shimo.ogg", read + 4000, 5, "shimo")
        e.cueAt(read + 9799).assertCue("voice/p001_shimo.ogg", read + 4000, 5, "shimo end (yoin)")
        // 余韻まで鳴り終えたら何も鳴らさない
        assertNull(e.cueAt(read + 9800))
        assertNull(e.cueAt(read + 60_000))
    }

    @Test
    fun cueIdsOfASequenceAreDistinctAndIncreasing() {
        val e = voiceEngine()
        val seen = mutableListOf<AudioCue>()
        var t = 0L
        while (t < firstRead + 10_000L) {
            e.advanceTo(t)
            e.state.audio?.let { if (seen.isEmpty() || seen.last().cueId != it.cueId) seen += it }
            t += 50L
        }
        assertEquals(listOf<Long>(1, 2, 3, 4, 5), seen.map { it.cueId })
        assertEquals(
            listOf(
                "voice/joka_kami.ogg", "voice/joka_shimo.ogg", "voice/joka_shimo.ogg",
                "voice/p001_kami.ogg", "voice/p001_shimo.ogg",
            ),
            seen.map { it.asset },
        )
        // 始まる時刻も鳴る順に増える
        assertEquals(seen.map { it.startedAtMillis }.sorted(), seen.map { it.startedAtMillis })
    }

    @Test
    fun theCueCarriesThePlaybackSpeedAndTheClipTimesAreScaled() {
        val e = voiceEngine(timing = voiceTiming(playbackSpeed = 2.0f))
        val t0 = memorize
        val cue = e.cueAt(t0)!!
        assertEquals(2.0f, cue.playbackSpeed)
        assertEquals(t0, cue.startedAtMillis)
        // エンジンの 100ms はクリップの 200ms
        assertEquals(200L, e.cueAt(t0 + 100)!!.positionAt(e.nowMillis))
        // 前置きの長さ: 序歌の上の句 1900/2、間 500/2、下の句 4400/2 を 2 回、間合い 1000（一定）
        val li = e.state.leadIn!!
        assertEquals(1_200L + 2_200L + 2_200L + 1_000L, li.totalMillis)
        // 序歌の下の句 1 回目は (1900 + 500) / 2 = 1200 から
        val shimo = e.cueAt(t0 + 1200)!!
        assertEquals("voice/joka_shimo.ogg", shimo.asset)
        assertEquals(t0 + 1200L, shimo.startedAtMillis)
        assertEquals(2.0f, shimo.playbackSpeed)
    }

    // ---- 札を取ったあと ----

    @Test
    fun theAudioKeepsPlayingAfterTheCardIsTakenUntilTheEndOfTheShimoNoKu() {
        val e = voiceEngine()
        val s = e.beginReadThroughLeadIn()
        val r = s.startMillis
        e.advanceToOffset(s, 500L)
        e.tapCard(Side.PLAYER, 1)
        val taken = e.state
        // 結果にはすぐ移り、表示は止まる
        assertEquals(MatchPhase.Result, taken.phase)
        assertEquals(3, taken.reading!!.revealedKana)
        val cue = taken.audio
        cue.assertCue("voice/p001_kami.ogg", r, 4, "still the kami")

        // 上の句が終わり、下の句が鳴る。Result のまま、表示は 3 文字で止まっている
        e.advanceTo(r + 4000)
        assertEquals(MatchPhase.Result, e.state.phase)
        assertEquals(3, e.state.reading!!.revealedKana)
        e.state.audio.assertCue("voice/p001_shimo.ogg", r + 4000, 5, "shimo after the take")
        // 余韻の終わり（r + 9800）まで鳴る
        e.advanceTo(r + 9799)
        e.state.audio.assertCue("voice/p001_shimo.ogg", r + 4000, 5, "yoin after the take")
        e.advanceTo(r + 9800)
        assertNull(e.state.audio)
        assertEquals(MatchPhase.Result, e.state.phase)
    }

    @Test
    fun nextStopsTheAudioThatIsStillPlaying() {
        val e = voiceEngine()
        val s = e.beginReadThroughLeadIn()
        val r = s.startMillis
        e.advanceToOffset(s, 500L)
        e.tapCard(Side.PLAYER, 1)
        assertNotNull(e.state.audio)

        // 下の句を鳴らしている最中に次へ進むと止まる
        e.advanceTo(r + 5000)
        e.state.audio.assertCue("voice/p001_shimo.ogg", r + 4000, 5, "playing")
        e.next()
        assertEquals(MatchPhase.Waiting, e.state.phase)
        assertNull(e.state.audio)
        // 時間が進んでも（前置きが始まるまでは）鳴らない
        e.advanceBy(interval - 1)
        assertNull(e.state.audio)
        assertNull(e.state.leadIn)

        // 次の前置きでは、前の歌の下の句が新しい cue で鳴る
        e.advanceBy(1)
        val w = e.nowMillis
        e.state.audio.assertCue("voice/p001_shimo.ogg", w, 6, "previous shimo no ku")
        assertEquals(LeadInKind.PREVIOUS_SHIMO, e.state.leadIn!!.kind)
        // 前の歌の下の句 [0, 5800] のあと間合い。次の歌の上の句と下の句が続けて予告される
        e.cueAt(w + 5800).assertCue("voice/p002_kami.ogg", w + LATER_LEAD_IN_MILLIS, 7, "next kami announced")
        e.cueAt(w + LATER_LEAD_IN_MILLIS + 3500)
            .assertCue("voice/p002_shimo.ogg", w + LATER_LEAD_IN_MILLIS + 4000, 8, "next shimo announced")
    }

    @Test
    fun nextBeforeTheShimoNoKuStartsAlsoStopsTheAudio() {
        val e = voiceEngine()
        val s = e.beginReadThroughLeadIn()
        e.advanceToOffset(s, 500L)
        e.tapCard(Side.PLAYER, 1)
        e.next()
        assertNull(e.state.audio)
        // 取られた歌の下の句は、この読みの音声としては鳴らない（次の前置きで読む）
        e.advanceBy(interval - 1)
        assertNull(e.state.audio)
        assertEquals(MatchPhase.Waiting, e.state.phase)
    }

    @Test
    fun theAudioKeepsPlayingInFinishedUntilTheEnd() {
        // プレイヤーの札が 1 枚だけ。取ると勝ちで、最後の札の下の句は読み切る
        val e = voiceEngine(order = listOf(1), player = listOf(1))
        val s = e.beginReadThroughLeadIn()
        val r = s.startMillis
        e.advanceToOffset(s, 500L)
        e.tapCard(Side.PLAYER, 1)
        assertEquals(MatchPhase.Result, e.state.phase)
        e.next()
        assertEquals(MatchPhase.Finished(Side.PLAYER), e.state.phase)
        e.state.audio.assertCue("voice/p001_kami.ogg", r, 4, "finished, kami")

        e.advanceTo(r + 6000)
        assertEquals(MatchPhase.Finished(Side.PLAYER), e.state.phase)
        e.state.audio.assertCue("voice/p001_shimo.ogg", r + 4000, 5, "finished, shimo")
        e.advanceTo(r + 9799)
        assertNotNull(e.state.audio)
        e.advanceTo(r + 9800)
        assertNull(e.state.audio)
    }

    @Test
    fun theCpuTakeAlsoLetsTheAudioFinish() {
        val e = voiceEngine(order = listOf(6, 2), profile = steadyCpu(300L))
        val s = e.beginReadThroughLeadIn()
        val r = s.startMillis
        e.advanceToOffset(s, 600L)
        assertEquals(Side.CPU, e.state.lastResult!!.takenBy)
        e.state.audio.assertCue("voice/p006_kami.ogg", r, 4, "cpu take, kami")
        e.advanceTo(r + 9799)
        assertNotNull(e.state.audio)
        e.advanceTo(r + 9800)
        assertNull(e.state.audio)
    }

    // ---- 空札 ----

    @Test
    fun anEmptyCardWithVoiceRevealsBothVersesAndEndsAfterTheYoin() {
        val e = voiceEngine(order = listOf(11, 2))
        val s = e.beginReadThroughLeadIn()
        val r = s.startMillis
        assertTrue(s.isEmpty)
        assertTrue(s.hasVoice)

        // 上の句も下の句も表示する
        e.advanceToOffset(s, 3100L)
        assertEquals(16, e.state.reading!!.revealedKana)
        e.advanceToOffset(s, 6800L)
        assertEquals(26, e.state.reading!!.revealedKana)
        // 下の句を読み終えて余韻が鳴っている間は、まだ読みの途中
        e.advanceToOffset(s, 9799L)
        assertEquals(MatchPhase.Reading, e.state.phase)
        e.state.audio.assertCue("voice/p011_shimo.ogg", r + 4000, 5, "yoin of the empty card")

        e.advanceToOffset(s, 9800L)
        val st = e.state
        assertEquals(MatchPhase.Result, st.phase)
        assertEquals(26, st.reading!!.revealedKana)
        assertTrue(st.lastResult!!.isEmptyCard)
        assertEquals("空札でした", st.lastResult!!.message)
        assertNull(st.audio)
        assertEquals(1, st.readsDone)

        // 従来どおり、結果を見せてから自動で進む
        e.advanceToOffset(s, 9800L + 1199L)
        assertEquals(MatchPhase.Result, e.state.phase)
        e.advanceToOffset(s, 9800L + 1200L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        assertNull(e.state.leadIn)
    }

    @Test
    fun anEmptyCardWithoutVoiceKeepsTheTextBehaviour() {
        // 11 番だけ音声がない。上の句までを表示し、上の句の後 1200ms で終わる
        val bank = fakeVoiceBank(only = (1..10).toSet())
        val e = voiceEngine(order = listOf(11, 2), timing = voiceTiming(voice = bank))
        val s = e.beginReadThroughLeadIn()
        assertTrue(s.isEmpty)
        assertFalse(s.hasVoice)
        assertNull(e.state.audio)

        e.advanceToOffset(s, 1800L + 1199L)
        assertEquals(MatchPhase.Reading, e.state.phase)
        assertEquals(16, e.state.reading!!.revealedKana)
        e.advanceToOffset(s, 1800L + 1200L)
        assertEquals(MatchPhase.Result, e.state.phase)
        assertEquals(16, e.state.reading!!.revealedKana)
    }

    @Test
    fun anEmptyCardThatIsTouchedIsNotAdvancedAutomatically() {
        // お手つきがあれば、従来どおり自動では進まない
        val e = voiceEngine(order = listOf(11, 2))
        val s = e.beginReadThroughLeadIn()
        e.advanceToOffset(s, 500L)
        e.tapCard(Side.PLAYER, 1)
        e.advanceToOffset(s, 9800L)
        assertEquals(MatchPhase.Result, e.state.phase)
        assertEquals(1, e.state.playerFaultCount)
        e.advanceToOffset(s, 9800L + 5_000L)
        assertEquals(MatchPhase.Result, e.state.phase)
    }
}
