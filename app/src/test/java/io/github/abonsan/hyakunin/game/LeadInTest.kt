package io.github.abonsan.hyakunin.game

import io.github.abonsan.hyakunin.data.Joka
import io.github.abonsan.hyakunin.game.voice.fakeVoiceBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 競技かるた式の前置き（序歌・前の歌の下の句）。fakeVoiceBank の時刻は VoiceFixtures.kt の先頭を参照。 */
class LeadInTest {
    private val memorize = MemorizeTime.SEC_10.millis
    private val interval = ReadInterval.SEC_2.millis

    private fun voiceEngine(
        order: List<Int> = listOf(1, 2),
        timing: ReadTiming = voiceTiming(),
        profile: CpuProfile = SLOW_CPU,
        readingStyle: ReadingStyle = ReadingStyle.COMPETITIVE,
        voiceEnabled: Boolean = true,
        readStartMode: ReadStartMode = ReadStartMode.AUTO,
    ) = layoutEngine(
        order = order,
        timing = timing,
        profile = profile,
        readingStyle = readingStyle,
        voiceEnabled = voiceEnabled,
        readStartMode = readStartMode,
    )

    /** 前置きの状態。[t] はエンジンの時刻。 */
    private fun MatchEngine.leadInAt(t: Long): LeadInState {
        advanceTo(t)
        assertEquals("t=$t", MatchPhase.Waiting, state.phase)
        return state.leadIn ?: error("no lead-in at t=$t")
    }

    private fun LeadInState.assertAt(verse: LeadInVerse, pass: Int, revealed: Int, count: Int, message: String) {
        assertEquals(message, verse, this.verse)
        assertEquals(message, pass, this.pass)
        assertEquals(message, revealed, revealedKana)
        assertEquals(message, count, verseKanaCount)
    }

    // ---- 最初の読み: 序歌 ----

    @Test
    fun firstReadStartsWithTheJokaSequence() {
        val e = voiceEngine()
        // 札を覚える時間の間は前置きではない
        e.advanceTo(memorize - 1)
        assertNull(e.state.leadIn)
        assertEquals(1L, e.state.waitRemainingMillis)

        val t0 = memorize
        var li = e.leadInAt(t0)
        assertEquals(LeadInKind.JOKA, li.kind)
        assertEquals(Joka.poem, li.poem)
        li.assertAt(LeadInVerse.KAMI, 1, 0, 17, "start")
        assertEquals(FIRST_LEAD_IN_MILLIS, li.remainingMillis)
        assertEquals(FIRST_LEAD_IN_MILLIS, li.totalMillis)
        assertFalse(li.inMa)
        val st = e.state
        assertNull(st.waitRemainingMillis)
        assertFalse(st.isMemorizing)
        assertNull(st.reading)
        assertEquals(0, st.readsDone)

        // 序歌の上の句 [0, 1900]: 仮名は 100 + 100 i
        e.leadInAt(t0 + 100).assertAt(LeadInVerse.KAMI, 1, 1, 17, "kami first kana")
        e.leadInAt(t0 + 1599).assertAt(LeadInVerse.KAMI, 1, 15, 17, "kami 15")
        e.leadInAt(t0 + 1700).assertAt(LeadInVerse.KAMI, 1, 17, 17, "kami all")
        // 上の句のあと下の句までの間（1900〜2400）は、上の句を表示し終えたまま
        e.leadInAt(t0 + 2399).assertAt(LeadInVerse.KAMI, 1, 17, 17, "gap")
        li = e.leadInAt(t0 + 2399)
        assertEquals(FIRST_LEAD_IN_MILLIS - 2399, li.remainingMillis)

        // 序歌の下の句 1 回目 [2400, 6800]
        e.leadInAt(t0 + 2400).assertAt(LeadInVerse.SHIMO, 1, 0, 14, "shimo 1 start")
        e.leadInAt(t0 + 2500).assertAt(LeadInVerse.SHIMO, 1, 1, 14, "shimo 1 first")
        e.leadInAt(t0 + 3800).assertAt(LeadInVerse.SHIMO, 1, 14, 14, "shimo 1 all")
        // 余韻の間は表示し終えたまま
        e.leadInAt(t0 + 6799).assertAt(LeadInVerse.SHIMO, 1, 14, 14, "shimo 1 yoin")
        assertEquals(LeadInKind.JOKA, e.state.leadIn!!.kind)

        // 序歌の下の句 2 回目 [6800, 11200]
        e.leadInAt(t0 + 6800).assertAt(LeadInVerse.SHIMO, 2, 0, 14, "shimo 2 start")
        e.leadInAt(t0 + 6900).assertAt(LeadInVerse.SHIMO, 2, 1, 14, "shimo 2 first")
        li = e.leadInAt(t0 + 11199)
        li.assertAt(LeadInVerse.SHIMO, 2, 14, 14, "shimo 2 yoin")
        assertFalse(li.inMa)

        // 間合い [11200, 12200]
        li = e.leadInAt(t0 + 11200)
        li.assertAt(LeadInVerse.SHIMO, 2, 14, 14, "ma")
        assertTrue(li.inMa)
        assertEquals(1000L, li.remainingMillis)
        assertEquals(1L, e.leadInAt(t0 + 12199).remainingMillis)

        // 上の句が始まる
        e.advanceTo(t0 + 12200)
        val reading = e.state
        assertEquals(MatchPhase.Reading, reading.phase)
        assertNull(reading.leadIn)
        assertEquals(t0 + 12200, e.session!!.startMillis)
        assertEquals(1, reading.reading!!.poem.no)
        assertEquals(0, reading.reading!!.revealedKana)
    }

    @Test
    fun theLeadInStartsWhenTheWaitEndsEvenWhenSkipped() {
        val e = voiceEngine()
        e.advanceTo(3_000L)
        e.startReading()
        // 待ちを飛ばしても、読みの前に前置きが入る
        assertEquals(MatchPhase.Waiting, e.state.phase)
        assertEquals(LeadInKind.JOKA, e.state.leadIn!!.kind)
        assertEquals(FIRST_LEAD_IN_MILLIS, e.state.leadIn!!.remainingMillis)
        // 前置きの最中に読み始めを指示しても無視される
        e.advanceTo(5_000L)
        val before = e.state.leadIn
        e.startReading()
        assertEquals(before, e.state.leadIn)
        e.advanceTo(3_000L + FIRST_LEAD_IN_MILLIS)
        assertEquals(MatchPhase.Reading, e.state.phase)
        assertEquals(3_000L + FIRST_LEAD_IN_MILLIS, e.session!!.startMillis)
    }

    @Test
    fun tapModeStartsTheLeadInAtTheTap() {
        val e = voiceEngine(readStartMode = ReadStartMode.TAP)
        e.advanceTo(10 * 60 * 1000L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        assertNull(e.state.leadIn)
        assertNull(e.state.waitRemainingMillis)

        e.startReading()
        val st = e.state
        assertEquals(MatchPhase.Waiting, st.phase)
        assertEquals(LeadInKind.JOKA, st.leadIn!!.kind)
        assertNull(st.waitRemainingMillis)
        assertFalse(st.isMemorizing)

        e.advanceBy(FIRST_LEAD_IN_MILLIS)
        assertEquals(MatchPhase.Reading, e.state.phase)
    }

    // ---- 2枚目以降: 前の歌の下の句 ----

    @Test
    fun laterReadsStartWithThePreviousShimoNoKu() {
        val e = voiceEngine(order = listOf(1, 2))
        val s = e.beginReadThroughLeadIn()
        e.advanceToOffset(s, 500L)
        e.tapCard(Side.PLAYER, 1)
        e.next()
        val wait = e.nowMillis
        // 読みの間（2秒）の間は前置きではない
        e.advanceTo(wait + interval - 1)
        assertNull(e.state.leadIn)

        val w = wait + interval
        var li = e.leadInAt(w)
        assertEquals(LeadInKind.PREVIOUS_SHIMO, li.kind)
        assertEquals(1, li.poem.no)
        li.assertAt(LeadInVerse.SHIMO, 1, 0, 10, "start")
        assertEquals(LATER_LEAD_IN_MILLIS, li.remainingMillis)
        assertEquals(LATER_LEAD_IN_MILLIS, li.totalMillis)
        // 直前の読みは、前置きの間も残っている
        assertEquals(1, e.state.reading!!.poem.no)

        // 下の句 [0, 5800]: 仮名は 100 + 300 j
        e.leadInAt(w + 100).assertAt(LeadInVerse.SHIMO, 1, 1, 10, "first")
        e.leadInAt(w + 2800).assertAt(LeadInVerse.SHIMO, 1, 10, 10, "all")
        li = e.leadInAt(w + 5799)
        assertFalse(li.inMa)
        // 間合い [5800, 6800]
        li = e.leadInAt(w + 5800)
        assertTrue(li.inMa)
        assertEquals(1000L, li.remainingMillis)

        e.advanceTo(w + LATER_LEAD_IN_MILLIS)
        assertEquals(MatchPhase.Reading, e.state.phase)
        assertNull(e.state.leadIn)
        assertEquals(2, e.state.reading!!.poem.no)
        assertEquals(w + LATER_LEAD_IN_MILLIS, e.session!!.startMillis)
    }

    @Test
    fun laterReadsAfterAnEmptyCardReadItsShimoNoKuAgain() {
        // 11 は空札。音声で下の句まで読み終えて自動で進んだあとも、前置きで 11 の下の句を読む
        val e = voiceEngine(order = listOf(11, 2))
        val s = e.beginReadThroughLeadIn()
        assertTrue(s.isEmpty)
        e.advanceToOffset(s, 9_800L + 1_200L)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        assertNull(e.state.leadIn)

        val w = e.nowMillis + interval
        val li = e.leadInAt(w)
        assertEquals(LeadInKind.PREVIOUS_SHIMO, li.kind)
        assertEquals(11, li.poem.no)
        assertEquals(LATER_LEAD_IN_MILLIS, li.totalMillis)
        e.advanceTo(w + LATER_LEAD_IN_MILLIS)
        assertEquals(MatchPhase.Reading, e.state.phase)
        assertEquals(2, e.state.reading!!.poem.no)
    }

    @Test
    fun laterReadsAfterATakeInTheMiddleOfTheShimoNoKu() {
        // 下の句の途中で取られて、next() で音声が切られても、前置きは下の句を最初から読む
        val e = voiceEngine(order = listOf(1, 2))
        val s = e.beginReadThroughLeadIn()
        e.advanceToOffset(s, 5_000L)
        e.tapCard(Side.PLAYER, 1)
        assertEquals(Side.PLAYER, e.state.lastResult!!.takenBy)
        e.next()
        e.advanceBy(interval)
        val li = e.state.leadIn!!
        assertEquals(LeadInKind.PREVIOUS_SHIMO, li.kind)
        assertEquals(1, li.poem.no)
        li.assertAt(LeadInVerse.SHIMO, 1, 0, 10, "from the beginning")
    }

    // ---- 前置きの間の操作 ----

    @Test
    fun tapsAreIgnoredAndTheCpuDoesNothingDuringTheLeadIn() {
        val e = voiceEngine(order = listOf(1, 2), profile = FAULTY_CPU)
        e.advanceTo(memorize)
        for (offset in listOf(0L, 500L, 3_000L, 11_000L, FIRST_LEAD_IN_MILLIS - 1)) {
            e.advanceTo(memorize + offset)
            // 読まれる札・同じ陣の別の札・敵陣の札のどれに触れても何も起きない
            e.tapCard(Side.PLAYER, 1)
            e.tapCard(Side.PLAYER, 2)
            e.tapCard(Side.CPU, 6)
            e.tapCard(Side.PLAYER, 99)
            // 次へ・読み始めも効かない
            e.next()
            e.startReading()
            val st = e.state
            assertEquals("offset=$offset", MatchPhase.Waiting, st.phase)
            assertNotNull("offset=$offset", st.leadIn)
            assertEquals(0, st.playerTakenCount)
            assertEquals(0, st.playerFaultCount)
            assertEquals(0, st.cpuTakenCount)
            assertEquals(0, st.cpuFaultCount)
            assertFalse(st.playerLockedOut)
            assertNull(st.lastResult)
            assertEquals(listOf(1, 2, 3, 4, 5), st.playerNos)
            assertEquals(listOf(6, 7, 8, 9, 10), st.cpuNos)
        }
        // CPU は前置きの間は何も計画せず、読みが始まってから動く（友札へ 100ms で触れる）
        assertNull(e.session)
        e.advanceTo(memorize + FIRST_LEAD_IN_MILLIS)
        val s = e.session!!
        assertTrue(s.cpuPlan is CpuPlan.Fault)
        assertEquals(s.startMillis + 100L, s.cpuPlan!!.atMillis)
        e.advanceToOffset(s, 100L)
        assertTrue(s.cpuLocked)

        // 前置きが終われば、普通に取れる
        e.tapCard(Side.PLAYER, 1)
        assertEquals(1, e.state.playerTakenCount)
    }

    @Test
    fun theCpuDoesNotTakeDuringTheLeadInEvenWithZeroReactionTime() {
        val e = voiceEngine(order = listOf(6, 1), profile = steadyCpu(0L))
        e.advanceTo(memorize + FIRST_LEAD_IN_MILLIS - 1)
        assertEquals(0, e.state.cpuTakenCount)
        assertNull(e.session)
        e.advanceTo(memorize + FIRST_LEAD_IN_MILLIS)
        // 決まり字（2文字目 = 300ms）で CPU が取る
        e.advanceTo(memorize + FIRST_LEAD_IN_MILLIS + 299)
        assertEquals(MatchPhase.Reading, e.state.phase)
        e.advanceTo(memorize + FIRST_LEAD_IN_MILLIS + 300)
        assertEquals(Side.CPU, e.state.lastResult!!.takenBy)
    }

    // ---- 前置きがない場合 ----

    @Test
    fun simpleStyleHasNoLeadIn() {
        val e = voiceEngine(order = listOf(1, 2), readingStyle = ReadingStyle.SIMPLE)
        e.advanceTo(memorize - 1)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        e.advanceTo(memorize)
        // 待ちが終われば直ちに上の句。音声は上の句から
        var st = e.state
        assertEquals(MatchPhase.Reading, st.phase)
        assertNull(st.leadIn)
        assertEquals(memorize, e.session!!.startMillis)
        assertEquals("voice/p001_kami.ogg", st.audio!!.asset)
        assertEquals(memorize, st.audio!!.startedAtMillis)

        // 2枚目以降も同じ
        e.tapCard(Side.PLAYER, 1)
        e.next()
        assertNull(e.state.audio)
        e.advanceBy(interval - 1)
        assertEquals(MatchPhase.Waiting, e.state.phase)
        assertNull(e.state.leadIn)
        e.advanceBy(1)
        st = e.state
        assertEquals(MatchPhase.Reading, st.phase)
        assertEquals(2, st.reading!!.poem.no)
        assertEquals("voice/p002_kami.ogg", st.audio!!.asset)
    }

    @Test
    fun noLeadInWithoutVoiceUnlessTextLeadInIsOn() {
        // 音声なし
        val none = voiceEngine(timing = voiceTiming(voice = null))
        none.advanceTo(memorize)
        assertEquals(MatchPhase.Reading, none.state.phase)
        assertNull(none.state.leadIn)

        // 音声を無効にした（設定）
        val off = voiceEngine(voiceEnabled = false)
        off.advanceTo(memorize)
        assertEquals(MatchPhase.Reading, off.state.phase)
        assertNull(off.state.leadIn)
        assertNull(off.state.audio)
    }

    // ---- 文字だけの前置き ----

    @Test
    fun textOnlyLeadInUsesCharMillisThenYoinThenMa() {
        val timing = voiceTiming(voice = null, textLeadIn = true)
        val e = voiceEngine(timing = timing)
        val t0 = memorize
        var li = e.leadInAt(t0)
        assertEquals(LeadInKind.JOKA, li.kind)
        li.assertAt(LeadInVerse.KAMI, 1, 0, 17, "start")
        assertNull(e.state.audio)

        // 上の句: 5文字 100..500、句の区切りで 100 の間、7文字 700..1300、5文字 1500..1900
        e.leadInAt(t0 + 100).assertAt(LeadInVerse.KAMI, 1, 1, 17, "first")
        e.leadInAt(t0 + 500).assertAt(LeadInVerse.KAMI, 1, 5, 17, "5")
        e.leadInAt(t0 + 699).assertAt(LeadInVerse.KAMI, 1, 5, 17, "phrase gap")
        e.leadInAt(t0 + 700).assertAt(LeadInVerse.KAMI, 1, 6, 17, "6")
        e.leadInAt(t0 + 1900).assertAt(LeadInVerse.KAMI, 1, 17, 17, "all")
        // 上の句と下の句の間（300）
        e.leadInAt(t0 + 2199).assertAt(LeadInVerse.KAMI, 1, 17, 17, "gap")
        // 下の句 1 回目: 2200 から。7文字 2300..2900、区切り、7文字 3100..3700。そのあと余韻 3000 → 6700
        e.leadInAt(t0 + 2200).assertAt(LeadInVerse.SHIMO, 1, 0, 14, "shimo start")
        e.leadInAt(t0 + 2300).assertAt(LeadInVerse.SHIMO, 1, 1, 14, "shimo first")
        e.leadInAt(t0 + 3700).assertAt(LeadInVerse.SHIMO, 1, 14, 14, "shimo all")
        e.leadInAt(t0 + 6699).assertAt(LeadInVerse.SHIMO, 1, 14, 14, "yoin")
        // 下の句 2 回目: 6700 から（間なし）。8200 までに表示し終え、余韻のあと 11200
        e.leadInAt(t0 + 6700).assertAt(LeadInVerse.SHIMO, 2, 0, 14, "shimo 2 start")
        e.leadInAt(t0 + 6800).assertAt(LeadInVerse.SHIMO, 2, 1, 14, "shimo 2 first")
        e.leadInAt(t0 + 8200).assertAt(LeadInVerse.SHIMO, 2, 14, 14, "shimo 2 all")
        assertFalse(e.leadInAt(t0 + 11199).inMa)
        // 間合い（1000）
        li = e.leadInAt(t0 + 11200)
        assertTrue(li.inMa)
        assertEquals(1000L, li.remainingMillis)
        assertEquals(12_200L, li.totalMillis)
        assertNull(e.state.audio)

        e.advanceTo(t0 + 12_200L)
        assertEquals(MatchPhase.Reading, e.state.phase)
        // 上の句は従来どおりの文字だけの時刻表
        assertEquals(100L, e.session!!.kanaAt[0] - e.session!!.startMillis)
    }

    @Test
    fun textOnlyLeadInForLaterReads() {
        val timing = voiceTiming(voice = null, textLeadIn = true)
        val e = voiceEngine(timing = timing)
        val s = e.beginReadThroughLeadIn()
        e.tapCard(Side.PLAYER, 1)
        e.next()
        e.advanceBy(interval)
        val w = e.nowMillis
        val li = e.state.leadIn!!
        assertEquals(LeadInKind.PREVIOUS_SHIMO, li.kind)
        assertEquals(1, li.poem.no)
        // 下の句は「しものくの　ひかりかな」: 100..500、区切り、700..1100。余韻 3000 → 4100、間合い 1000 → 5100
        assertEquals(5_100L, li.totalMillis)
        e.leadInAt(w + 1100).assertAt(LeadInVerse.SHIMO, 1, 10, 10, "all")
        assertFalse(e.leadInAt(w + 4099).inMa)
        assertTrue(e.leadInAt(w + 4100).inMa)
        e.advanceTo(w + 5_100L)
        assertEquals(MatchPhase.Reading, e.state.phase)
        assertEquals(2, e.state.reading!!.poem.no)
        assertTrue(s.startMillis < e.session!!.startMillis)
    }

    @Test
    fun missingJokaClipsFallBackToTextButThePoemKeepsItsVoice() {
        val bank = fakeVoiceBank(joka = false)
        val e = voiceEngine(timing = voiceTiming(voice = bank))
        val t0 = memorize
        // 序歌は文字だけ（上の句 1900、間 300、下の句 2200+..）。音声の予告は札の上の句から
        e.leadInAt(t0 + 2300).assertAt(LeadInVerse.SHIMO, 1, 1, 14, "text joka")
        val cue = e.state.audio!!
        assertEquals("voice/p001_kami.ogg", cue.asset)
        assertEquals(t0 + 12_200L, cue.startedAtMillis)
        assertFalse(cue.hasStartedAt(e.nowMillis))
        e.advanceTo(t0 + 12_200L)
        assertEquals(cue, e.state.audio)
        assertEquals(MatchPhase.Reading, e.state.phase)
    }
}
