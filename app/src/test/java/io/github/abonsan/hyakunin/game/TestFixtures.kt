package io.github.abonsan.hyakunin.game

import io.github.abonsan.hyakunin.data.Poem
import kotlin.random.Random

/** テスト用の歌。読みだけを指定し、表記・作者は仮の値にする。 */
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

/** 配置と読み順を指定したエンジン。1文字の表示間隔は既定で 100ms。 */
internal fun layoutEngine(
    order: List<Int>,
    mode: RuleMode = RuleMode.COMPETITIVE,
    player: List<Int> = listOf(1, 2, 3, 4, 5),
    cpu: List<Int> = listOf(6, 7, 8, 9, 10),
    profile: CpuProfile = SLOW_CPU,
    random: Random = ZeroRandom,
    charMillis: Long = 100L,
    poems: List<Poem> = SYNTHETIC_POEMS,
): MatchEngine = MatchEngine.withLayout(
    config = GameConfig(ruleMode = mode, emptyCardCount = 0),
    random = random,
    poems = poems,
    playerNos = player,
    cpuNos = cpu,
    readOrder = order,
    timing = ReadTiming(charMillis),
    profile = profile,
)

/** Waiting を終えて次の読みを始める（直前の Waiting 開始から 1500ms 経っている必要がある）。 */
internal fun MatchEngine.beginRead(): ReadSession {
    advanceBy(1500L)
    check(snapshot().phase == MatchPhase.Reading) { "phase=${snapshot().phase}" }
    return session!!
}

/** 現在の読みの経過時間 [offset] ms の時点まで進める。 */
internal fun MatchEngine.advanceToOffset(s: ReadSession, offset: Long) = advanceTo(s.startMillis + offset)

internal val MatchEngine.state: MatchUiState get() = snapshot()

internal val MatchUiState.playerNos: List<Int> get() = playerCards.map { it.poemNo }
internal val MatchUiState.cpuNos: List<Int> get() = cpuCards.map { it.poemNo }
