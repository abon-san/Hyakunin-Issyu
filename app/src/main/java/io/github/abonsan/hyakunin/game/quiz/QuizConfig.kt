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

/** 「札を覚える」で、答える前にどこまで読むか。 */
enum class QuizReadRange(val label: String, val description: String) {
    /** 上の句だけを読む。 */
    KAMI_ONLY("上の句だけ", "上の句を読んだら止めます"),

    /** 上の句に続けて下の句も読む（文字も下の句まで出す）。下の句を聞いてから選べる。 */
    THROUGH_SHIMO("下の句まで", "上の句に続けて下の句も読み、文字も出します"),
}

/**
 * 「札を覚える」の設定。出題範囲（歌番号・決まり字・間違えた札の条件をすべて満たす札を出す）と、どこまで読むか。
 */
data class QuizConfig(
    val numberRange: PoemNumberRange = PoemNumberRange.ALL,
    val kimarijiGroup: KimarijiGroup = KimarijiGroup.ALL,
    /** これまでに間違えた札だけを出すか。 */
    val missedOnly: Boolean = false,
    /** 答える前にどこまで読むか。出題範囲には関わらない。 */
    val readRange: QuizReadRange = QuizReadRange.KAMI_ONLY,
)

/** [config] の範囲に入る札（歌番号順）。[missed] はこれまでに間違えた札の歌番号。 */
fun quizPoems(config: QuizConfig, missed: Set<Int>, poems: List<Poem> = Poems.all): List<Poem> = poems.filter {
    it.no in config.numberRange.numbers &&
        kimarijiLength(it, poems) in config.kimarijiGroup.lengths &&
        (!config.missedOnly || it.no in missed)
}
