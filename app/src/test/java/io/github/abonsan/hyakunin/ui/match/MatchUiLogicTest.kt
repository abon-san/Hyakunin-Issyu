package io.github.abonsan.hyakunin.ui.match

import androidx.compose.ui.unit.dp
import io.github.abonsan.hyakunin.data.Joka
import io.github.abonsan.hyakunin.data.Poems
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.LeadInVerse
import io.github.abonsan.hyakunin.game.MatchPhase
import io.github.abonsan.hyakunin.game.ReadingSpeed
import io.github.abonsan.hyakunin.game.ReadingStyle
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.game.Side
import io.github.abonsan.hyakunin.game.Transfer
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.game.kanaCount
import io.github.abonsan.hyakunin.game.kimarijiLength
import io.github.abonsan.hyakunin.game.voice.fakeVoiceBank
import io.github.abonsan.hyakunin.ui.Fixtures
import io.github.abonsan.hyakunin.ui.card.TORIFUDA_ASPECT_RATIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun String.withoutJoiners() = replace("\u2060", "")

/** 対戦画面の表示用の計算（Compose を使わない部分）。 */
class MatchUiLogicTest {
    @Test
    fun leadInTitlesNameWhatIsBeingRead() {
        assertEquals("序歌", leadInTitle(Scenes.jokaLeadIn().leadIn!!))
        assertEquals("前の歌の下の句（61番）", leadInTitle(Scenes.previousShimoLeadIn().leadIn!!))
    }

    @Test
    fun theLeadInShowsTheVerseUpToTheKanaBeingRead() {
        val jokaKami = kanaCount(Joka.kamiYomi)
        val jokaShimo = kanaCount(Joka.shimoYomi)
        // 序歌の上の句: 読んだところまで
        assertEquals(9, leadInRevealedKana(Scenes.jokaLeadIn(verse = LeadInVerse.KAMI, revealed = 9).leadIn!!))
        // 序歌の下の句: 上の句をすべてと、下の句の読んだところまで。2回目は下の句を初めから見せ直す
        assertEquals(jokaKami + 7, leadInRevealedKana(Scenes.jokaLeadIn(revealed = 7).leadIn!!))
        assertEquals(jokaKami + 3, leadInRevealedKana(Scenes.jokaLeadIn(pass = 2, revealed = 3).leadIn!!))
        // 間合いではすべて
        assertEquals(
            jokaKami + jokaShimo,
            leadInRevealedKana(Scenes.jokaLeadIn(pass = 2, revealed = jokaShimo, inMa = true).leadIn!!),
        )
        // 前の歌の下の句: 上の句の仮名の後ろから数える（上の句は書かない）
        val previous = Scenes.previousShimoLeadIn(revealed = 6).leadIn!!
        assertEquals(kanaCount(previous.poem.kamiYomi) + 6, leadInRevealedKana(previous))
    }

    @Test
    fun theShimoOnlyVerseKeepsTheNumbering() {
        val verse = verseOf(Poems.byNo(61), VerseStyle.KANA)
        val shimo = verse.shimoOnly()
        assertEquals(emptyList<VerseLine>(), shimo.kami)
        assertEquals(verse.shimo, shimo.shimo)
        assertEquals(17, verse.kamiLength)
        assertEquals(17, shimo.shimo.first().firstKana)
        assertEquals("けふここのへににほひぬるかな", shimo.text)
        // 序歌も読みと歴史的仮名遣いを対応づけられる（なにわず → なにはづ）
        val joka = verseOf(Joka.poem, VerseStyle.KANA)
        assertEquals(listOf("なにはづに", "さくやこのはな", "ふゆごもり"), joka.kami.map { it.text })
        assertEquals(5, joka.displayCount(5))
    }

    @Test
    fun theMatchTimingUsesTheVoiceAndTheCompetitiveLeadIn() {
        val bank = fakeVoiceBank(poems = Poems.all)
        val competitive = matchTiming(
            GameConfig(readingStyle = ReadingStyle.COMPETITIVE, readingSpeed = ReadingSpeed.FAST),
            bank,
        )
        assertSame(bank, competitive.voice)
        // 競技かるた式は、音声がないときも前置きを文字で出す
        assertTrue(competitive.textLeadIn)
        assertEquals(ReadingSpeed.FAST.playbackSpeed, competitive.playbackSpeed)
        assertEquals(ReadingSpeed.FAST.charMillis, competitive.charMillis)
        assertTrue(matchTiming(GameConfig(readingStyle = ReadingStyle.COMPETITIVE), null).textLeadIn)
        val simple = matchTiming(GameConfig(readingStyle = ReadingStyle.SIMPLE), null)
        assertNull(simple.voice)
        assertFalse(simple.textLeadIn)
    }

    @Test
    fun verseNumbersKanaThroughBothVerses() {
        val verse = verseOf(Poems.byNo(1), VerseStyle.READING)
        assertEquals(
            listOf(VerseLine("あきのたの", 0), VerseLine("かりおのいおの", 5), VerseLine("とまをあらみ", 12)),
            verse.kami,
        )
        assertEquals(listOf(VerseLine("わがころもでは", 18), VerseLine("つゆにぬれつつ", 25)), verse.shimo)
    }

    @Test
    fun historicalKanaVerseIsNumberedByTheShownCharacters() {
        val one = verseOf(Poems.byNo(1), VerseStyle.KANA)
        assertEquals(
            listOf(VerseLine("あきのたの", 0), VerseLine("かりほのいほの", 5), VerseLine("とまをあらみ", 12)),
            one.kami,
        )
        assertEquals(listOf(VerseLine("わがころもでは", 18), VerseLine("つゆにぬれつつ", 25)), one.shimo)
        // 2番は「ころもほすちょう」（8文字）が「ころもほすてふ」（7文字）になるので、続く句の番号も1つ前になる
        val two = verseOf(Poems.byNo(2), VerseStyle.KANA)
        assertEquals(listOf(VerseLine("ころもほすてふ", 17), VerseLine("あまのかぐやま", 24)), two.shimo)
    }

    @Test
    fun everyPoemHasThreeUpperAndTwoLowerPhrasesThatFitTheLayout() {
        for (style in VerseStyle.entries) {
            for (poem in Poems.all) {
                val label = "${poem.no} $style"
                val (kami, shimo) = when (style) {
                    VerseStyle.READING -> poem.kamiYomi to poem.shimoYomi
                    VerseStyle.KANA -> poem.kamiKana to poem.shimoKana
                }
                val verse = verseOf(poem, style)
                // 歴史的仮名遣いでも読みに戻さず（対応づけできて）、表記どおりの句になる
                assertEquals(label, kami.split('　'), verse.kami.map { it.text })
                assertEquals(label, shimo.split('　'), verse.shimo.map { it.text })
                assertTrue(label, (verse.kami + verse.shimo).all { it.text.length <= MAX_PHRASE_KANA })
                assertEquals(label, kanaCount(kami) + kanaCount(shimo), verse.text.length)
                // 句を横に並べるときの見積もり（上の句・下の句の最長）に収まる
                assertTrue(label, kanaCount(kami) <= MAX_KAMI_KANA)
                assertTrue(label, kanaCount(shimo) <= MAX_SHIMO_KANA)
                // 読み終えたら（エンジンの仮名の総数）、表示の文字をすべて見せる
                val total = kanaCount(poem.kamiYomi) + kanaCount(poem.shimoYomi)
                assertEquals(label, verse.text.length, verse.displayCount(total))
                assertEquals(label, 0, verse.displayCount(0))
            }
        }
    }

    @Test
    fun readingShowsTheHistoricalKanaForTheReadKana() {
        // 2番の下の句「ころもほすちょう」は「ころもほすてふ」（上の句の17文字は1文字ずつ対応する）
        val kana = verseOf(Poems.byNo(2), VerseStyle.KANA)
        fun shown(revealed: Int) = kana.text.take(kana.displayCount(revealed))
        assertEquals("はるすぎてなつきにけらししろたへのころもほす", shown(22))
        // 「ち」を読んだら「てふ」まで見せ、「ちょう」を読み終えるまでそのまま
        assertEquals("はるすぎてなつきにけらししろたへのころもほすてふ", shown(23))
        assertEquals(shown(23), shown(24))
        assertEquals(shown(23), shown(25))
        assertEquals("はるすぎてなつきにけらししろたへのころもほすてふあ", shown(26))
        // 読み終えたら全文（読みは32文字、歴史的仮名遣いは31文字）
        assertEquals("はるすぎてなつきにけらししろたへのころもほすてふあまのかぐやま", shown(32))
        // 範囲の外は端にそろえる
        assertEquals(0, kana.displayCount(-1))
        assertEquals(31, kana.displayCount(40))

        // 読みで表示するときは、エンジンの数がそのまま表示の文字数
        val reading = verseOf(Poems.byNo(2), VerseStyle.READING)
        assertEquals("はるすぎてなつきにけらししろたえのころもほすち", reading.text.take(reading.displayCount(23)))
        assertEquals((0..32).toList(), (0..32).map(reading::displayCount))
    }

    @Test
    fun resultMarksMapKimarijiAndOverReadToTheShownVerse() {
        // 61番を読みの21文字目（下の句の「きょうこ」）で取った。決まり字は「いに」
        val result = Fixtures.result(
            61, kimarijiLength = 2, revealedAtTake = 21, takenBy = Side.PLAYER, takenFrom = Side.PLAYER, message = "",
        )
        val kana = verseOf(result.poem, VerseStyle.KANA)
        val marks = takeMarks(result, kana)
        // 「きょう」は「けふ」なので、歴史的仮名遣いでは20文字目
        assertEquals(TakeMarks(kimariji = 2, taken = 20), marks)
        assertEquals(
            List(2) { KanaTone.Kimariji } + List(18) { KanaTone.OverRead } + List(11) { KanaTone.Unread },
            kana.text.indices.map(marks::toneOf),
        )
        assertEquals("しへのならのみやこのやへざくらけふこ", kana.text.substring(2, 20))
        assertEquals("このへににほひぬるかな", kana.text.substring(20))
        assertEquals("いに", kimarijiText(kana, marks))
        assertEquals("決まり字は 2 字・20 字目で取りました", takeSummary(marks).withoutJoiners())

        // 読みで表示するときは、エンジンの数のまま
        val reading = verseOf(result.poem, VerseStyle.READING)
        assertEquals(TakeMarks(kimariji = 2, taken = 21), takeMarks(result, reading))
        assertEquals("いにしえのならのみやこのやえざくらきょうこ", reading.text.take(21))

        // 上の句に「てふ」がある41番を「こいすち」まで読んで取った: 赤は「てふ」
        val early = Fixtures.result(
            41, kimarijiLength = 3, revealedAtTake = 4, takenBy = Side.CPU, takenFrom = Side.CPU, message = "",
        )
        val kana41 = verseOf(early.poem, VerseStyle.KANA)
        val marks41 = takeMarks(early, kana41)
        assertEquals(TakeMarks(kimariji = 3, taken = 5), marks41)
        assertEquals("こひす", kimarijiText(kana41, marks41))
        assertEquals(listOf(KanaTone.OverRead, KanaTone.OverRead, KanaTone.Unread), (3..5).map(marks41::toneOf))

        // 取られなかった札（空札など）は決まり字だけを直す
        val empty = Fixtures.result(
            61, kimarijiLength = 2, revealedAtTake = null, takenBy = null, takenFrom = null, isEmptyCard = true,
            message = "",
        )
        assertEquals(TakeMarks(kimariji = 2, taken = null), takeMarks(empty, kana))
    }

    @Test
    fun flowedVerseFontSizeFitsTheLongestUpperVerseOnOneLine() {
        // 上の句（最長19文字と句の間2つ）が1行に、上の句・下の句の2行が高さに収まる
        val size = fittingFlowedVerseFontSize(width = 393.dp, height = 90.dp, max = 34.dp, min = 17.dp)
        assertTrue("$size", size.value * (MAX_KAMI_KANA * 1.04f + 1.2f) <= 393f)
        assertTrue("$size", size.value * 1.42f * 2 <= 90f)
        assertEquals(34f, fittingFlowedVerseFontSize(width = 900.dp, height = 300.dp, max = 34.dp, min = 17.dp).value, 0.01f)
        assertEquals(17f, fittingFlowedVerseFontSize(width = 100.dp, height = 30.dp, max = 34.dp, min = 17.dp).value, 0.01f)
        // 1句1行の読みの高さ
        assertEquals(17f * (5 * 1.42f + 0.36f), verseHeight(17.dp).value, 0.01f)
    }

    @Test
    fun kimarijiIsTheHeadOfTheShownVerse() {
        fun kimariji(no: Int, length: Int, style: VerseStyle): String {
            val verse = verseOf(Poems.byNo(no), style)
            val result = Fixtures.result(no, length, null, takenBy = null, takenFrom = null, message = "")
            return kimarijiText(verse, takeMarks(result, verse))
        }
        assertEquals("あきの", kimariji(1, 3, VerseStyle.READING))
        assertEquals("む", kimariji(87, 1, VerseStyle.READING))
        assertEquals("あさぼらけあ", kimariji(31, 6, VerseStyle.READING))
        assertEquals("おうこ", kimariji(44, 3, VerseStyle.READING))
        // 歴史的仮名遣いでは、表示している表記の先頭
        assertEquals("あきの", kimariji(1, 3, VerseStyle.KANA))
        assertEquals("あふこ", kimariji(44, 3, VerseStyle.KANA))
        assertEquals("をぐ", kimariji(26, 2, VerseStyle.KANA))
        // 既に読まれた札が多く「お」の1文字で決まるときも、「お」にあたる「あふ」まで
        assertEquals("あふ", kimariji(44, 1, VerseStyle.KANA))
    }

    @Test
    fun standardKimarijiKeepsItsLengthInHistoricalKana() {
        // 100首そろった状態の決まり字は、歴史的仮名遣いでも同じ文字数（決まり字の中で文字数の変わる表記は無い）
        for (poem in Poems.all) {
            val length = kimarijiLength(poem, Poems.all)
            val verse = verseOf(poem, VerseStyle.KANA)
            assertEquals("${poem.no}", length, verse.displayCount(length))
        }
    }

    @Test
    fun kanaToneMarksKimarijiAndOverRead() {
        // 決まり字3文字、7文字目で取った
        val tones = (0 until 9).map { kanaTone(it, kimarijiLength = 3, revealedAtTake = 7) }
        assertEquals(List(3) { KanaTone.Kimariji } + List(4) { KanaTone.OverRead } + List(2) { KanaTone.Unread }, tones)
        // 決まり字が出る前に取った: 赤は無い
        assertEquals(
            listOf(KanaTone.Kimariji, KanaTone.Kimariji, KanaTone.Unread),
            (0 until 3).map { kanaTone(it, kimarijiLength = 2, revealedAtTake = 1) },
        )
        // 取られなかった札は決まり字だけ緑で、残りは墨
        assertEquals(
            listOf(KanaTone.Kimariji, KanaTone.Plain, KanaTone.Plain),
            (0 until 3).map { kanaTone(it, kimarijiLength = 1, revealedAtTake = null) },
        )
    }

    @Test
    fun takeSummaryDescribesWhenTheCardWasTaken() {
        fun summary(kimariji: Int, taken: Int?) = takeSummary(TakeMarks(kimariji, taken)).withoutJoiners()
        assertEquals("決まり字は 3 字・7 字目で取りました", summary(3, 7))
        assertEquals("決まり字は 3 字・3 字目で取りました", summary(3, 3))
        assertEquals("決まり字が出る前に取りました（2 字目）", summary(3, 2))
        assertEquals("決まり字は 3 字", summary(3, null))
        // 数と「字」「字目」は行をまたいで分けない（文字の間に WORD JOINER）
        val joined = takeSummary(TakeMarks(2, 20))
        assertTrue(joined, "2\u2060 \u2060字・" in joined)
        assertTrue(joined, "2\u20600\u2060 \u2060字\u2060目で" in joined)
    }

    @Test
    fun resultMessageIsSplitIntoHeadlineAndDetail() {
        assertEquals(
            ResultMessageParts("敵陣の札を取りました", "自陣から1枚送ってください"),
            splitResultMessage("敵陣の札を取りました。自陣から1枚送ってください"),
        )
        assertEquals(
            ResultMessageParts("誰も取れませんでした", "お手つき。相手のお手つき。お互いのお手つきなので札は動きません"),
            splitResultMessage("誰も取れませんでした。お手つき。相手のお手つき。お互いのお手つきなので札は動きません"),
        )
        assertEquals(ResultMessageParts("空札でした", null), splitResultMessage("空札でした"))
        assertEquals(ResultMessageParts("空札でした", null), splitResultMessage("空札でした。"))
    }

    @Test
    fun resultNotesListFaultsAndTransfers() {
        val result = Fixtures.result(
            no = 87,
            kimarijiLength = 1,
            revealedAtTake = 4,
            takenBy = Side.CPU,
            takenFrom = Side.PLAYER,
            faults = setOf(Side.PLAYER),
            transfers = listOf(
                Transfer(Side.CPU, Side.PLAYER, 12, Transfer.Reason.SEND_AFTER_TAKE),
                Transfer(Side.CPU, Side.PLAYER, 70, Transfer.Reason.FAULT_PENALTY),
            ),
            message = "",
        )
        assertEquals(
            listOf(
                ResultNote("お手つき", "あなた", isFault = true),
                ResultNote("送り札", "相手 → あなた（1枚）"),
                ResultNote("罰の送り札", "相手 → あなた（1枚）"),
            ),
            resultNotes(result, RuleMode.COMPETITIVE),
        )
    }

    @Test
    fun resultNotesForBothFaultsAndSafeMisses() {
        val both = Fixtures.result(
            61, 2, null, takenBy = null, takenFrom = null, faults = setOf(Side.CPU, Side.PLAYER), message = "",
        )
        assertEquals(
            listOf(ResultNote("お手つき", "あなた・相手（罰の送り札は相殺）", isFault = true)),
            resultNotes(both, RuleMode.COMPETITIVE),
        )
        // 簡易ルールでは札が動かないので相殺とは書かない
        assertEquals(
            listOf(ResultNote("お手つき", "あなた・相手", isFault = true)),
            resultNotes(both, RuleMode.SIMPLE),
        )
        val safe = Fixtures.result(
            61, 2, 5, takenBy = Side.CPU, takenFrom = Side.PLAYER, safeMisses = setOf(Side.PLAYER), message = "",
        )
        assertEquals(
            listOf(ResultNote("空振り", "あなたは同じ陣の別の札に触れました（お手つきなし）")),
            resultNotes(safe, RuleMode.COMPETITIVE),
        )
    }

    @Test
    fun lockoutNoticeTellsFaultFromSafeMiss() {
        // 自陣の61番を読んでいる
        val locked = Scenes.reading().copy(playerLockedOut = true)
        assertEquals("お手つき", lockoutNotice(locked, touchedSide = Side.CPU)?.stamp)
        val safe = lockoutNotice(locked, touchedSide = Side.PLAYER)!!
        assertNull(safe.stamp)
        assertEquals("同じ陣の札なのでお手つきにはなりません", safe.detail)
        // どちらに触れたか分からないときは、取れないことだけを伝える
        assertEquals(LockoutNotice(stamp = null, message = "この札はもう取れません"), lockoutNotice(locked, null))
        // 簡易ルールでは同じ陣でもお手つき
        val simple = locked.copy(config = GameConfig(ruleMode = RuleMode.SIMPLE))
        assertEquals("お手つき", lockoutNotice(simple, touchedSide = Side.PLAYER)?.stamp)
        // 空札（場にない35番）に触れた
        val empty = locked.copy(reading = Fixtures.reading(no = 35, readNumber = 4, totalReads = 20, revealed = 3))
        assertEquals("お手つき", lockoutNotice(empty, touchedSide = Side.PLAYER)?.stamp)
        // ロックアウトしていない・読み上げ中でないときは出さない
        assertNull(lockoutNotice(Scenes.reading(), Side.CPU))
        assertNull(lockoutNotice(locked.copy(phase = MatchPhase.Result), Side.CPU))
    }

    @Test
    fun readCountIncludesTheCardBeingRead() {
        assertEquals(4, readCount(Scenes.reading()))
        assertEquals(0, readCount(Scenes.memorizing()))
        assertEquals(5, readCount(Scenes.result()))
    }

    @Test
    fun fiveCardsAndGapsFitTheWidth() {
        // 411dp 幅: (411 - 24 - 24) / 5
        assertEquals(72.6f, fieldCardWidth(411.dp, 891.dp).value, 0.01f)
        // 低い画面では高さで抑える（札の高さが画面の 12%）
        val short = fieldCardWidth(360.dp, 640.dp)
        assertEquals(640f * 0.12f, (short / TORIFUDA_ASPECT_RATIO).value, 0.01f)
        // 広い画面でも上限で止める
        assertEquals(84f, fieldCardWidth(800.dp, 1280.dp).value, 0.01f)
    }

    @Test
    fun fieldsTakeOneRowPerFiveCards() {
        assertEquals(1, fieldRows(0))
        assertEquals(1, fieldRows(5))
        assertEquals(2, fieldRows(6))
        assertEquals(2, fieldRows(10))
        val card = 60.dp
        assertEquals((card / TORIFUDA_ASPECT_RATIO).value + 24f, fieldHeight(3, card).value, 0.01f)
        assertEquals((card / TORIFUDA_ASPECT_RATIO).value * 2 + 6f + 24f, fieldHeight(9, card).value, 0.01f)
    }

    @Test
    fun landscapeBoardIsChosenByTheAvailableSpace() {
        assertTrue(useLandscapeBoard(891.dp, 411.dp))
        assertTrue(useLandscapeBoard(640.dp, 360.dp))
        // 小さめの横画面から、システムバー（上 24dp・横 48dp）を除いた広さ
        assertTrue(useLandscapeBoard(592.dp, 336.dp))
        assertTrue(useLandscapeBoard(1280.dp, 800.dp))
        assertFalse(useLandscapeBoard(411.dp, 891.dp))
        // 縦長の分割画面やタブレットの縦向きは縦の配置
        assertFalse(useLandscapeBoard(800.dp, 1280.dp))
        // 横画面を左右に分けた分割画面など、幅が高さより広くても読み上げの欄の幅が残らなければ縦の配置
        assertFalse(useLandscapeBoard(445.dp, 411.dp))
        assertFalse(useLandscapeBoard(411.dp, 390.dp))
    }

    @Test
    fun landscapeMetricsKeepCardsLargeAndFitTheWorstCase() {
        val sizes = listOf(891.dp to 411.dp, 640.dp to 360.dp, 592.dp to 336.dp, 780.dp to 360.dp, 1280.dp to 800.dp)
        for ((width, height) in sizes) {
            val m = landscapeMetrics(width, height)
            val label = "${width}x$height: $m"
            // 指で押しやすい大きさ
            assertTrue(label, m.cardWidth >= TouchCardWidth)
            // 帯・読み上げの欄・場で幅を使い切る
            assertEquals(label, width.value, (m.sidebarWidth + m.panelWidth + m.fieldWidth).value, 0.01f)
            assertTrue(label, m.panelWidth >= PanelFloorWidth)
            // 一方が9枚・他方が1枚（2段と1段）でも、両陣が高さに収まる
            val fields = fieldHeight(9, m.cardWidth) + fieldHeight(1, m.cardWidth) + FieldSeparator
            assertTrue(label, fields <= height)
            // 5枚の段が場の幅に収まる
            assertTrue(label, m.cardWidth * 5 + CardGap * 4 <= m.fieldWidth)
        }
        // 一般的な横画面では縦画面と同じくらいの大きさの札
        assertEquals(72f, landscapeMetrics(891.dp, 411.dp).cardWidth.value, 0.01f)
        // 幅に余裕がなければ、読み上げの欄を狭めてでも押しやすい大きさを保つ
        assertEquals(48f, landscapeMetrics(592.dp, 336.dp).cardWidth.value, 0.01f)
        // 読み上げの欄が広く余るタブレットでは、縦に並べる配置の上限まで大きくする
        assertEquals(84f, landscapeMetrics(1280.dp, 800.dp).cardWidth.value, 0.01f)
    }

    @Test
    fun waitingInfoForMemorizingIntervalAndTap() {
        val memorizing = waitingInfo(Scenes.memorizing())
        assertTrue(memorizing.memorizing)
        assertFalse(memorizing.tapToStart)
        assertEquals(24_200L, memorizing.remainingMillis)
        assertEquals(GameConfig().memorizeTime.millis, memorizing.totalMillis)
        assertEquals(1, memorizing.nextNumber)

        val interval = waitingInfo(Scenes.interval())
        assertFalse(interval.memorizing)
        assertEquals(GameConfig().readInterval.millis, interval.totalMillis)
        assertEquals(7, interval.nextNumber)
        assertEquals(2, interval.movedToPlayer)
        assertEquals(0, interval.movedToCpu)

        val tap = waitingInfo(Scenes.tapToStart())
        assertTrue(tap.tapToStart)
        assertEquals(null, tap.remainingMillis)
        assertEquals(1, tap.movedToCpu)

        // まだ1枚も読んでいなければ、札を覚える時間として扱う
        assertTrue(waitingInfo(Scenes.memorizing().copy(isMemorizing = false)).memorizing)
    }

    @Test
    fun remainingTimeIsRoundedUpToSeconds() {
        assertEquals("30秒", remainingText(30_000L))
        assertEquals("25秒", remainingText(24_001L))
        assertEquals("1秒", remainingText(1L))
        assertEquals("0秒", remainingText(0L))
        assertEquals("0秒", remainingText(-5L))
        assertEquals("59秒", remainingText(59_000L))
        assertEquals("1分00秒", remainingText(59_001L))
        assertEquals("1分05秒", remainingText(64_200L))
        assertEquals("2分00秒", remainingText(120_000L))
    }

    @Test
    fun movedCardsNoteTellsWhereTheGoldEdgedCardsCameFrom() {
        assertEquals(null, movedCardsNote(0, 0))
        // 送られてきた札が1枚なら枚数は添えず、2枚以上なら添える
        assertEquals("金の縁は相手から送られてきた札です", movedCardsNote(1, 0))
        // 枚数の部分は行をまたいで分けない（文字の間に WORD JOINER）
        assertEquals("金の縁は相手から送られてきた札です（\u20602\u2060枚\u2060）", movedCardsNote(2, 0))
        assertEquals("金の縁は相手へ送った札です（1枚）", movedCardsNote(0, 1)!!.withoutJoiners())
        assertEquals("金の縁は送り札で動いた札です（自陣へ1枚・敵陣へ2枚）", movedCardsNote(1, 2)!!.withoutJoiners())
    }

    @Test
    fun guidesPointToWhereTheOwnFieldIs() {
        assertTrue(boardGuide(fieldsAside = false).startsWith("下が自陣"))
        assertTrue(boardGuide(fieldsAside = true).startsWith("右下が自陣"))
        assertEquals("送る札を選んでください（あと 2 枚）", sendPrompt(2).withoutJoiners())
        assertEquals("下の自陣の札に触れると相手へ送ります", sendGuide(fieldsAside = false))
        assertEquals("右下の自陣の札に触れると相手へ送ります", sendGuide(fieldsAside = true))
    }

    @Test
    fun verseFontSizeFitsTheLongestPhraseAndAllLines() {
        val wide = fittingVerseFontSize(width = 380.dp, height = 600.dp, max = 34.dp, min = 17.dp)
        assertEquals(34f, wide.value, 0.01f)
        val narrow = fittingVerseFontSize(width = 200.dp, height = 600.dp, max = 34.dp, min = 17.dp)
        assertTrue(narrow.value * MAX_PHRASE_KANA * 1.04f <= 200f)
        val low = fittingVerseFontSize(width = 380.dp, height = 150.dp, max = 34.dp, min = 17.dp)
        assertTrue(low.value * 1.42f * 5 <= 150f)
        assertEquals(17f, fittingVerseFontSize(width = 50.dp, height = 50.dp, max = 34.dp, min = 17.dp).value, 0.01f)
    }

    @Test
    fun finishReasonExplainsTheOutcome() {
        assertEquals("自陣の札が先になくなりました", finishReason(Scenes.finished(), Side.PLAYER))
        val cpuWon = Scenes.finished(Side.CPU).copy(playerCards = Fixtures.cards(23, 46), cpuCards = emptyList())
        assertEquals("相手の陣の札が先になくなりました", finishReason(cpuWon, Side.CPU))
        val exhausted = Scenes.finished(Side.PLAYER).copy(playerCards = Fixtures.cards(23), cpuCards = Fixtures.cards(46, 99))
        assertEquals("すべての札を読み終えました", finishReason(exhausted, Side.PLAYER))
        assertEquals("取った札　あなた 5 枚 ・ 相手 5 枚", finishReason(Scenes.draw(), null))
    }
}
