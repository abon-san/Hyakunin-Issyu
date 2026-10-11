package io.github.abonsan.hyakunin.game

import io.github.abonsan.hyakunin.data.Poem
import kotlin.random.Random

/** テスト用の歌。読みだけを指定し、表記・作者は仮の値にする。上の句の歴史的仮名遣いは読みと同じにする。 */
internal fun testPoem(
    no: Int,
    kamiYomi: String,
    shimoYomi: String = "しものくの　ひかりかな",
): Poem = Poem(
    no = no,
    kami = kamiYomi,
    shimo = shimoYomi,
    kamiYomi = kamiYomi,
    shimoYomi = shimoYomi,
    kamiKana = kamiYomi,
    shimoKana = "しも$no",
    author = "作者$no",
    authorYomi = "さくしゃ$no",
)

private val FIRST_KANA = "あいさたなはまやらわ".toList()
private val SECOND_KANA = "かきくけこさしすせそ".toList()

/**
 * 読みが重複しない合成の100首。歌 n の上の句は「(n-1)/10 番目の仮名 + (n-1)%10 番目の仮名」で始まる。
 * 先頭1文字は10首で共通、先頭2文字は100首すべて異なるので、最初の決まり字はすべて2。
 * 上の句は16文字（6+5+5）、下の句は10文字（5+5）。
 */
internal val SYNTHETIC_POEMS: List<Poem> = (1..100).map { n ->
    val i = n - 1
    testPoem(n, "${FIRST_KANA[i / 10]}${SECOND_KANA[i % 10]}ののあさ　ひのくれに　つきのかげ")
}

/** 常に 0 を返す乱数。最小値・最初の候補・確率の判定が必ず成立する側を選ぶ。 */
internal object ZeroRandom : Random() {
    override fun nextBits(bitCount: Int): Int = 0
}

/** 試合中ずっと取りに来ない（遅くとも下の句の後 500ms に取る）CPU。 */
internal val SLOW_CPU = CpuProfile(1_000_000L, 1_000_000L, 0.0)

/** 反応時間 0、必ずお手つきする CPU（友札があれば）。 */
internal val FAULTY_CPU = CpuProfile(0L, 0L, 1.0)

/** 反応時間 [reaction] ms、お手つきしない CPU。 */
internal fun steadyCpu(reaction: Long) = CpuProfile(reaction, reaction, 0.0)

/**
 * 配置と読み順を指定したエンジン。1文字の表示間隔は既定で 100ms。
 * 読み始めの待ちは、既定では短い選択肢（札を覚える時間 10秒、読みの間 2秒）にする。
 * 既定のタイミング（[timing]）は音声なし・前置きなしなので、読み始めの待ちが終われば直ちに上の句が始まる。
 */
internal fun layoutEngine(
    order: List<Int>,
    mode: RuleMode = RuleMode.COMPETITIVE,
    player: List<Int> = listOf(1, 2, 3, 4, 5),
    cpu: List<Int> = listOf(6, 7, 8, 9, 10),
    profile: CpuProfile = SLOW_CPU,
    random: Random = ZeroRandom,
    charMillis: Long = 100L,
    poems: List<Poem> = SYNTHETIC_POEMS,
    readStartMode: ReadStartMode = ReadStartMode.AUTO,
    memorizeTime: MemorizeTime = MemorizeTime.SEC_10,
    readInterval: ReadInterval = ReadInterval.SEC_2,
    readingStyle: ReadingStyle = ReadingStyle.COMPETITIVE,
    voiceEnabled: Boolean = true,
    timing: ReadTiming = ReadTiming(charMillis),
): MatchEngine = MatchEngine.withLayout(
    config = GameConfig(
        ruleMode = mode,
        emptyCardCount = 0,
        readStartMode = readStartMode,
        memorizeTime = memorizeTime,
        readInterval = readInterval,
        readingStyle = readingStyle,
        voiceEnabled = voiceEnabled,
    ),
    random = random,
    poems = poems,
    playerNos = player,
    cpuNos = cpu,
    readOrder = order,
    timing = timing,
    profile = profile,
)

/** Waiting の待ちを飛ばして（読み開始の指示を出して）次の読みを始める。 */
internal fun MatchEngine.beginRead(): ReadSession {
    startReading()
    check(snapshot().phase == MatchPhase.Reading) { "phase=${snapshot().phase}" }
    return session!!
}

/** 現在の読みの経過時間 [offset] ms の時点まで進める。 */
internal fun MatchEngine.advanceToOffset(s: ReadSession, offset: Long) = advanceTo(s.startMillis + offset)

internal val MatchEngine.state: MatchUiState get() = snapshot()

internal val MatchUiState.playerNos: List<Int> get() = playerCards.map { it.poemNo }
internal val MatchUiState.cpuNos: List<Int> get() = cpuCards.map { it.poemNo }
