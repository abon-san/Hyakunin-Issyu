package io.github.abonsan.hyakunin.game.quiz

import io.github.abonsan.hyakunin.data.Poem
import io.github.abonsan.hyakunin.data.Poems
import io.github.abonsan.hyakunin.game.kimarijiLength

/** 「札を覚える」で出題する歌番号の範囲。 */
enum class PoemNumberRange(val label: String, val numbers: IntRange) {
    ALL("すべて", 1..100),
    FROM_1("1〜20", 1..20),
    FROM_21("21〜40", 21..40),
    FROM_41("41〜60", 41..60),
    FROM_61("61〜80", 61..80),
    FROM_81("81〜100", 81..100),
}

/** 「札を覚える」で出題する決まり字の字数（100首すべてから見た、試合の始まりの決まり字）。 */
enum class KimarijiGroup(val label: String, val lengths: IntRange) {
    ALL("すべて", 1..Int.MAX_VALUE),

    /** むすめふさほせ の7首。 */
    ONE("一字", 1..1),
    TWO("二字", 2..2),
    THREE("三字", 3..3),

    /** 四字・五字・六字決まり（14首）。 */
    FOUR_OR_MORE("四字以上", 4..Int.MAX_VALUE),
}

/** 「札を覚える」の出題範囲。歌番号・決まり字・間違えた札の条件をすべて満たす札を出す。 */
data class QuizConfig(
    val numberRange: PoemNumberRange = PoemNumberRange.ALL,
    val kimarijiGroup: KimarijiGroup = KimarijiGroup.ALL,
    /** これまでに間違えた札だけを出すか。 */
    val missedOnly: Boolean = false,
)

/** [config] の範囲に入る札（歌番号順）。[missed] はこれまでに間違えた札の歌番号。 */
fun quizPoems(config: QuizConfig, missed: Set<Int>, poems: List<Poem> = Poems.all): List<Poem> = poems.filter {
    it.no in config.numberRange.numbers &&
        kimarijiLength(it, poems) in config.kimarijiGroup.lengths &&
        (!config.missedOnly || it.no in missed)
}
