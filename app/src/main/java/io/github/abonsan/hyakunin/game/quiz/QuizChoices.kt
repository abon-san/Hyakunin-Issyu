package io.github.abonsan.hyakunin.game.quiz

import io.github.abonsan.hyakunin.data.Poem
import io.github.abonsan.hyakunin.game.commonPrefixLength
import io.github.abonsan.hyakunin.game.normalizeYomi
import kotlin.random.Random

/** 1問で並べる取り札の枚数（正解1枚と誤答3枚）。 */
const val QUIZ_CHOICE_COUNT = 4

/**
 * [answer] の問題で並べる取り札（[answer] を含む [QUIZ_CHOICE_COUNT] 枚、並びは無作為）。
 * 誤答には紛らわしい札を選ぶ: [poems] のほかの札を、上の句の読みの出だしが同じ長さ（友札ほど長い）の降順、
 * 同じなら下の句の読みの出だしが同じ長さの降順に並べ、それも同じなら無作為に選ぶ。
 */
fun choicesFor(answer: Poem, poems: List<Poem>, random: Random): List<Poem> {
    val kami = normalizeYomi(answer.kamiYomi)
    val shimo = normalizeYomi(answer.shimoYomi)
    // 並べ替えは安定なので、先に混ぜておくと同じ近さの札の中から無作為に選ぶことになる
    val decoys = poems
        .filter { it.no != answer.no }
        .shuffled(random)
        .sortedWith(
            compareByDescending<Poem> { commonPrefixLength(kami, normalizeYomi(it.kamiYomi)) }
                .thenByDescending { commonPrefixLength(shimo, normalizeYomi(it.shimoYomi)) },
        )
        .take(QUIZ_CHOICE_COUNT - 1)
    return (decoys + answer).shuffled(random)
}
