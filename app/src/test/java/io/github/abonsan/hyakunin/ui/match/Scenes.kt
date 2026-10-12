package io.github.abonsan.hyakunin.ui.match

import io.github.abonsan.hyakunin.data.Joka
import io.github.abonsan.hyakunin.game.CpuLevel
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.LeadInKind
import io.github.abonsan.hyakunin.game.LeadInState
import io.github.abonsan.hyakunin.game.LeadInVerse
import io.github.abonsan.hyakunin.game.MatchPhase
import io.github.abonsan.hyakunin.game.MatchUiState
import io.github.abonsan.hyakunin.game.ReadStartMode
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.game.Side
import io.github.abonsan.hyakunin.game.Transfer
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.game.kanaCount
import io.github.abonsan.hyakunin.ui.Fixtures
import io.github.abonsan.hyakunin.ui.Fixtures.card
import io.github.abonsan.hyakunin.ui.Fixtures.cards

/**
 * スクリーンショットに使う対戦の場面（偽の [MatchUiState]）。
 * 自陣は 1・17・33・61・87番、敵陣は 12・23・46・70・99番から始まる。
 *
 * 句の表示は、これまでのスクリーンショットと見比べられるよう読み（[VerseStyle.READING]）にする。
 * 歴史的仮名遣い（[VerseStyle.KANA]）の場面は [kanaReading] と [kanaResult]。
 */
internal object Scenes {
    private val KanaConfig = GameConfig(verseStyle = VerseStyle.KANA)

    /** 最初の札を読む前（札を覚える時間）。30秒のうち残り25秒。 */
    fun memorizing(): MatchUiState = Fixtures.state(
        phase = MatchPhase.Waiting,
        waitRemainingMillis = 24_200L,
        isMemorizing = true,
    )

    /**
     * 読みの間。お手つきの後に相手が自陣の札を取り、相手から2枚（金の縁）送られてきた。
     * 4秒のうち残り3秒。
     */
    fun interval(): MatchUiState = Fixtures.state(
        phase = MatchPhase.Waiting,
        player = cards(1, 17, 33, 61) + listOf(card(12, isNew = true), card(70, isNew = true)),
        cpu = cards(23, 46, 99),
        reading = Fixtures.reading(no = 87, readNumber = 6, totalReads = 20, revealed = 4),
        readsDone = 6,
        playerTaken = 1,
        cpuTaken = 2,
        playerFaults = 1,
        waitRemainingMillis = 2_300L,
    )

    /** 読みの間。相手が自陣の87番を取り、相手から1枚（金の縁）送られてきた。4秒のうち残り3秒。 */
    fun intervalAfterOneCardSent(): MatchUiState = Fixtures.state(
        phase = MatchPhase.Waiting,
        player = cards(1, 17, 33, 61) + listOf(card(12, isNew = true)),
        cpu = cards(23, 46, 70, 99),
        reading = Fixtures.reading(no = 87, readNumber = 6, totalReads = 20, revealed = 4),
        readsDone = 6,
        playerTaken = 2,
        cpuTaken = 2,
        waitRemainingMillis = 2_300L,
    )

    /** タップで読み始める設定。敵陣の札を取って自陣の1番を送った後、「読み始める」を待っている。 */
    fun tapToStart(): MatchUiState = Fixtures.state(
        config = GameConfig(readStartMode = ReadStartMode.TAP, verseStyle = VerseStyle.READING),
        phase = MatchPhase.Waiting,
        player = cards(17, 33, 61, 87),
        cpu = cards(23, 46, 70, 99) + listOf(card(1, isNew = true)),
        reading = Fixtures.reading(no = 12, readNumber = 5, totalReads = 20, revealed = 5),
        readsDone = 5,
        playerTaken = 2,
        cpuTaken = 2,
    )

    /** タップで読み始める設定で、最初の札を読む前。 */
    fun tapToStartFirst(): MatchUiState = Fixtures.state(
        config = GameConfig(readStartMode = ReadStartMode.TAP, verseStyle = VerseStyle.READING),
        phase = MatchPhase.Waiting,
        isMemorizing = true,
    )

    /**
     * 競技かるた式の前置き: 最初の札の前の序歌。上の句を読み終え、下の句（1回目）を7文字目まで読んだところ。
     * 句の表示はアプリの既定の歴史的仮名遣い。
     */
    fun jokaLeadIn(
        verse: LeadInVerse = LeadInVerse.SHIMO,
        pass: Int = 1,
        revealed: Int = 7,
        inMa: Boolean = false,
        readStartMode: ReadStartMode = ReadStartMode.AUTO,
    ): MatchUiState = Fixtures.state(
        config = GameConfig(verseStyle = VerseStyle.KANA, readStartMode = readStartMode),
        phase = MatchPhase.Waiting,
        leadIn = LeadInState(
            kind = LeadInKind.JOKA,
            poem = Joka.poem,
            verse = verse,
            pass = pass,
            revealedKana = revealed,
            verseKanaCount = kanaCount(if (verse == LeadInVerse.KAMI) Joka.kamiYomi else Joka.shimoYomi),
            remainingMillis = 11_000L,
            totalMillis = 18_200L,
            inMa = inMa,
        ),
    )

    /**
     * 競技かるた式の前置き: 4枚目の前に、3枚目に読んだ61番「いにしえの」の下の句を6文字目まで読んだところ。
     * 句の表示はアプリの既定の歴史的仮名遣い。
     */
    fun previousShimoLeadIn(revealed: Int = 6, inMa: Boolean = false): MatchUiState {
        val poem = Fixtures.poem(61)
        return Fixtures.state(
            config = KanaConfig,
            phase = MatchPhase.Waiting,
            player = cards(1, 17, 33, 87),
            reading = Fixtures.reading(no = 61, readNumber = 3, totalReads = 20, revealed = 8),
            readsDone = 3,
            playerTaken = 1,
            cpuTaken = 1,
            cpuFaults = 1,
            leadIn = LeadInState(
                kind = LeadInKind.PREVIOUS_SHIMO,
                poem = poem,
                verse = LeadInVerse.SHIMO,
                pass = 1,
                revealedKana = revealed,
                verseKanaCount = kanaCount(poem.shimoYomi),
                remainingMillis = 5_000L,
                totalMillis = 7_100L,
                inMa = inMa,
            ),
        )
    }

    /** 自陣の61番「いにしえの」を読んでいる途中。 */
    fun reading(revealed: Int = 9): MatchUiState = Fixtures.state(
        reading = Fixtures.reading(no = 61, readNumber = 4, totalReads = 20, revealed = revealed),
        readsDone = 3,
        playerTaken = 1,
        cpuTaken = 1,
        cpuFaults = 1,
    )

    /** 簡易ルール。下の句まで読み進めたところ。 */
    fun readingShimo(): MatchUiState = Fixtures.state(
        config = GameConfig(
            ruleMode = RuleMode.SIMPLE,
            emptyCardCount = 30,
            cpuLevel = CpuLevel.MASTER,
            verseStyle = VerseStyle.READING,
        ),
        reading = Fixtures.reading(no = 41, readNumber = 12, totalReads = 40, revealed = 23),
        readsDone = 11,
        playerTaken = 2,
        cpuTaken = 3,
    )

    /** 自陣の1番を7文字目で取った（決まり字3文字・緑3文字と赤4文字）。 */
    fun result(): MatchUiState = Fixtures.state(
        phase = MatchPhase.Result,
        player = cards(17, 33, 61, 87),
        reading = Fixtures.reading(no = 1, readNumber = 5, totalReads = 20, revealed = 7),
        lastResult = Fixtures.result(
            no = 1,
            kimarijiLength = 3,
            revealedAtTake = 7,
            takenBy = Side.PLAYER,
            takenFrom = Side.PLAYER,
            message = "自陣の札を取りました",
        ),
        readsDone = 5,
        playerTaken = 2,
        cpuTaken = 1,
    )

    /** 敵陣の札に触れてお手つきし、相手が自陣の87番を取った。相手から2枚送られて自陣は6枚。 */
    fun resultAfterFault(): MatchUiState = Fixtures.state(
        phase = MatchPhase.Result,
        player = cards(1, 17, 33, 61) + listOf(card(12, isNew = true), card(70, isNew = true)),
        cpu = cards(23, 46, 99),
        reading = Fixtures.reading(no = 87, readNumber = 6, totalReads = 20, revealed = 4),
        lastResult = Fixtures.result(
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
            message = "相手が自陣の札を取りました。お手つき！相手から2枚送られます",
        ),
        readsDone = 6,
        playerTaken = 1,
        cpuTaken = 2,
        playerFaults = 1,
    )

    /** 敵陣の12番を取ったので、自陣から1枚送る札を選ぶ。 */
    fun choosingSend(): MatchUiState = Fixtures.state(
        phase = MatchPhase.Result,
        cpu = cards(23, 46, 70, 99),
        reading = Fixtures.reading(no = 12, readNumber = 7, totalReads = 20, revealed = 5),
        lastResult = Fixtures.result(
            no = 12,
            kimarijiLength = 3,
            revealedAtTake = 5,
            takenBy = Side.PLAYER,
            takenFrom = Side.CPU,
            message = "敵陣の札を取りました。自陣から1枚送ってください",
        ),
        pendingPlayerSends = 1,
        readsDone = 7,
        playerTaken = 3,
        cpuTaken = 2,
    )

    /** 簡易ルール。決まり字（2文字）が出る前、1文字目で取った。 */
    fun takenEarly(): MatchUiState = Fixtures.state(
        config = GameConfig(ruleMode = RuleMode.SIMPLE, verseStyle = VerseStyle.READING),
        phase = MatchPhase.Result,
        cpu = cards(12, 46, 70, 99),
        reading = Fixtures.reading(no = 23, readNumber = 9, totalReads = 20, revealed = 1),
        lastResult = Fixtures.result(
            no = 23,
            kimarijiLength = 2,
            revealedAtTake = 1,
            takenBy = Side.PLAYER,
            takenFrom = Side.CPU,
            message = "敵陣の札を取りました",
        ),
        readsDone = 9,
        playerTaken = 4,
        cpuTaken = 2,
    )

    /** 空札（場にない35番）を上の句まで読んだ。 */
    fun emptyCard(): MatchUiState = Fixtures.state(
        phase = MatchPhase.Result,
        reading = Fixtures.reading(no = 35, readNumber = 10, totalReads = 20, revealed = 17),
        lastResult = Fixtures.result(
            no = 35,
            kimarijiLength = 3,
            revealedAtTake = null,
            takenBy = null,
            takenFrom = null,
            isEmptyCard = true,
            message = "空札でした",
        ),
        readsDone = 10,
        playerTaken = 2,
        cpuTaken = 2,
    )

    /** 両者お手つきで誰も取れなかった（罰の送り札は相殺）。 */
    fun nobodyTook(): MatchUiState = Fixtures.state(
        phase = MatchPhase.Result,
        player = cards(1, 17, 33, 87),
        reading = Fixtures.reading(no = 61, readNumber = 8, totalReads = 20, revealed = 30),
        lastResult = Fixtures.result(
            no = 61,
            kimarijiLength = 2,
            revealedAtTake = null,
            takenBy = null,
            takenFrom = null,
            faults = setOf(Side.PLAYER, Side.CPU),
            message = "誰も取れませんでした。お手つき。相手のお手つき。お互いのお手つきなので札は動きません",
        ),
        readsDone = 8,
        playerTaken = 2,
        cpuTaken = 2,
        playerFaults = 1,
        cpuFaults = 1,
    )

    /** 敵陣9枚・自陣1枚。送られてきた2枚は金の縁。 */
    fun nineAgainstOne(): MatchUiState = Fixtures.state(
        player = cards(61),
        cpu = cards(12, 23, 46, 70, 99, 1, 17) + listOf(card(33, isNew = true), card(87, isNew = true)),
        reading = Fixtures.reading(no = 99, readNumber = 15, totalReads = 20, revealed = 12),
        readsDone = 14,
        playerTaken = 6,
        cpuTaken = 2,
        playerFaults = 2,
        cpuFaults = 1,
    )

    /** 簡易ルールで、場の札をすべて読み終えて同数（引き分け）。 */
    fun draw(): MatchUiState = Fixtures.state(
        config = GameConfig(ruleMode = RuleMode.SIMPLE, emptyCardCount = 0, verseStyle = VerseStyle.READING),
        phase = MatchPhase.Finished(null),
        player = emptyList(),
        cpu = emptyList(),
        reading = Fixtures.reading(no = 99, readNumber = 10, totalReads = 10, revealed = 6),
        lastResult = Fixtures.result(
            no = 99,
            kimarijiLength = 3,
            revealedAtTake = 6,
            takenBy = Side.CPU,
            takenFrom = Side.CPU,
            message = "相手が敵陣の札を取りました",
        ),
        readsDone = 10,
        playerTaken = 5,
        cpuTaken = 5,
        playerFaults = 2,
    )

    /** 自陣の最後の1枚を取って勝った。 */
    fun finished(winner: Side? = Side.PLAYER): MatchUiState = Fixtures.state(
        phase = MatchPhase.Finished(winner),
        player = emptyList(),
        cpu = cards(23, 46, 99),
        reading = Fixtures.reading(no = 61, readNumber = 16, totalReads = 20, revealed = 3),
        lastResult = Fixtures.result(
            no = 61,
            kimarijiLength = 2,
            revealedAtTake = 3,
            takenBy = Side.PLAYER,
            takenFrom = Side.PLAYER,
            message = "自陣の札を取りました",
        ),
        readsDone = 16,
        playerTaken = 7,
        cpuTaken = 3,
        playerFaults = 1,
        cpuFaults = 2,
    )

    /**
     * 歴史的仮名遣いの表示で、敵陣の2番「はるすぎて」の下の句「ころもほすちょう」の「ち」まで
     * （上の句17文字と「ころもほすち」の6文字）読んだところ。「ちょう」は「てふ」にあたるので、「てふ」まで見せる。
     */
    fun kanaReading(): MatchUiState = Fixtures.state(
        config = KanaConfig,
        cpu = cards(2, 23, 46, 70, 99),
        reading = Fixtures.reading(no = 2, readNumber = 4, totalReads = 20, revealed = 23),
        readsDone = 3,
        playerTaken = 1,
        cpuTaken = 1,
        cpuFaults = 1,
    )

    /**
     * 歴史的仮名遣いの表示で、自陣の61番「いにしえの」を下の句「きょうこ」（21文字目）まで読んで取った。
     * 決まり字「いに」が緑、「けふこ」まで（表示では20文字目まで）が赤。
     */
    fun kanaResult(): MatchUiState = Fixtures.state(
        config = KanaConfig,
        phase = MatchPhase.Result,
        player = cards(1, 17, 33, 87),
        reading = Fixtures.reading(no = 61, readNumber = 5, totalReads = 20, revealed = 21),
        lastResult = Fixtures.result(
            no = 61,
            kimarijiLength = 2,
            revealedAtTake = 21,
            takenBy = Side.PLAYER,
            takenFrom = Side.PLAYER,
            message = "自陣の札を取りました",
        ),
        readsDone = 5,
        playerTaken = 2,
        cpuTaken = 1,
    )
}
