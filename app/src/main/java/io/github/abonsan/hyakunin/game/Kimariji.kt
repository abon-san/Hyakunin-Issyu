package io.github.abonsan.hyakunin.game

import io.github.abonsan.hyakunin.data.Poem

/**
 * [target] の決まり字（上の句の読みの先頭から数えた仮名の数）。
 *
 * [unread] はまだ読まれていない札（[target] 自身を含んでいてよい）。
 * 正規化した上の句の読み（[normalizeYomi]）の先頭が、[unread] 内の他のどの歌とも一致しなくなる最小の文字数。
 * 直後が小書き仮名なら一音として読むので、それも含める。上限は上の句全体の長さ。
 * 100首すべてを渡せば、試合開始時点の決まり字になる。
 */
fun kimarijiLength(target: Poem, unread: Collection<Poem>): Int {
    val others = unread.filter { it.no != target.no }.map { normalizeYomi(it.kamiYomi) }
    return kimarijiLength(normalizeYomi(target.kamiYomi), others)
}

/** 正規化済みの上の句の読み [target] と、他の未読札の正規化済みの読み [others] から決まり字を求める。 */
internal fun kimarijiLength(target: String, others: Collection<String>): Int {
    if (target.isEmpty()) return 0
    var longestCommon = 0
    for (other in others) {
        longestCommon = maxOf(longestCommon, commonPrefixLength(target, other))
    }
    var k = minOf(longestCommon + 1, target.length)
    while (k < target.length && isSmallKana(target[k])) k++
    return k
}
