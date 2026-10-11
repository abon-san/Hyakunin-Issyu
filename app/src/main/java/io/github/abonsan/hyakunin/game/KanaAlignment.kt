package io.github.abonsan.hyakunin.game

import io.github.abonsan.hyakunin.data.Poem

// 読み（現代仮名遣い）と歴史的仮名遣いの文字位置の対応。
// エンジンの revealedKana・kimarijiLength・revealedAtTake は読み（kamiYomi + shimoYomi、空白を除く）の
// 仮名の数で数える。歴史的仮名遣い（kamiKana + shimoKana、空白を除く）で表示するときは、
// verseBoundaries でその数を表示上の文字数に変換する。

/** 歴史的仮名遣いの1文字が、現代仮名遣いの別の1文字になる対応（は→わ など）。 */
private val SINGLE_KANA: Map<Char, Char> = mapOf(
    'は' to 'わ',
    'ひ' to 'い',
    'ふ' to 'う',
    'へ' to 'え',
    'ほ' to 'お',
    'ゐ' to 'い',
    'ゑ' to 'え',
    'を' to 'お',
    'ぢ' to 'じ',
    'づ' to 'ず',
)

/**
 * 歴史的仮名遣い→現代仮名遣いの、複数文字にまたがる対応（てふ→ちょう など）。
 * 文字数が変わる（くわ→か）ものもある。一部の文字だけを取り出して対応づけることはしない。
 */
internal val HISTORICAL_UNITS: List<Pair<String, String>> = listOf(
    "てふ" to "ちょう",
    "けふ" to "きょう",
    "あふ" to "おう",
    "わう" to "おう",
    "かう" to "こう",
    "さう" to "そう",
    "たう" to "とう",
    "なう" to "のう",
    "はう" to "ほう",
    "まう" to "もう",
    "やう" to "よう",
    "らう" to "ろう",
    "せう" to "しょう",
    "てう" to "ちょう",
    "けう" to "きょう",
    "めう" to "みょう",
    "れう" to "りょう",
    "しう" to "しゅう",
    "ちう" to "ちゅう",
    "きう" to "きゅう",
    "りう" to "りゅう",
    "ちゆう" to "ちゅう",
    "じゆう" to "じゅう",
    "くわ" to "か",
    "ぐわ" to "が",
)

/**
 * 読み（[Poem.kamiYomi] + [Poem.shimoYomi]）の先頭から何文字読んだかを、
 * 歴史的仮名遣い（[Poem.kamiKana] + [Poem.shimoKana]）の表示の文字数に変換する表を返す。
 *
 * 長さは「読みの仮名の総数 + 1」（[ReadingState.totalKanaCount] + 1）。
 * `b[i]` は、読みの先頭 i 文字に対応する歴史的仮名遣いの文字数（空白は数えない）。
 * `b[0]` は 0、最後は歴史的仮名遣いの総数で、単調に増える（減らない）。上の句と下の句は通しで扱う。
 *
 * 複数文字の単位（てふ→ちょう など）の途中の位置は、その単位の終わりに対応させる。
 * 例: 「ころもほすちょう」の「ちょ」まで読んだら、「ころもほすてふ」の「てふ」まで読んだものとして扱う。
 *
 * 句ごとに、同じ文字・1文字の対応（は→わ など）・複数文字の対応（[HISTORICAL_UNITS]）で
 * 読みと歴史的仮名遣いを対応づける。対応づけできない場合は [IllegalArgumentException]。
 */
fun verseBoundaries(poem: Poem): IntArray {
    val boundaries = IntArray(kanaCount(poem.kamiYomi) + kanaCount(poem.shimoYomi) + 1)
    var yomiOffset = 0
    var kanaOffset = 0
    try {
        for ((yomi, kana) in listOf(poem.kamiYomi to poem.kamiKana, poem.shimoYomi to poem.shimoKana)) {
            val yomiPhrases = splitPhrases(yomi)
            val kanaPhrases = splitPhrases(kana)
            require(yomiPhrases.size == kanaPhrases.size) { "句の数が違う: 読み='$yomi' 仮名='$kana'" }
            for (p in yomiPhrases.indices) {
                val phrase = alignPhrase(yomiPhrases[p], kanaPhrases[p])
                for (t in 1 until phrase.size) boundaries[yomiOffset + t] = kanaOffset + phrase[t]
                yomiOffset += yomiPhrases[p].length
                kanaOffset += kanaPhrases[p].length
            }
        }
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("${poem.no}番: ${e.message}", e)
    }
    return boundaries
}

/** 空白で区切られた句（空の句は除く）。 */
private fun splitPhrases(text: String): List<String> {
    val phrases = mutableListOf<String>()
    val current = StringBuilder()
    for (c in text) {
        if (!isYomiSpace(c)) {
            current.append(c)
        } else if (current.isNotEmpty()) {
            phrases += current.toString()
            current.clear()
        }
    }
    if (current.isNotEmpty()) phrases += current.toString()
    return phrases
}

/**
 * 1句の読み [yomi] と歴史的仮名遣い [kana]（どちらも空白を含まない）の文字位置を対応づける。
 * 返す配列は長さ `yomi.length + 1` で、`result[i]` は読みの先頭 i 文字に対応する [kana] の文字数。
 * 対応づけできない場合は [IllegalArgumentException]。
 */
internal fun alignPhrase(yomi: String, kana: String): IntArray {
    val n = yomi.length
    val m = kana.length
    // aligned[i][j]: yomi[i..] と kana[j..] を、残りすべて対応づけられるか
    val aligned = Array(n + 1) { BooleanArray(m + 1) }
    aligned[n][m] = true
    for (i in n downTo 0) {
        for (j in m downTo 0) {
            if (i == n && j == m) continue
            aligned[i][j] = anyUnit(yomi, kana, i, j) { yomiLength, kanaLength ->
                aligned[i + yomiLength][j + kanaLength]
            }
        }
    }
    require(aligned[0][0]) { "読み='$yomi' と仮名='$kana' を対応づけできない" }

    val result = IntArray(n + 1)
    var i = 0
    var j = 0
    while (i < n || j < m) {
        val (yomiLength, kanaLength) = checkNotNull(firstUnit(yomi, kana, i, j) { yl, kl -> aligned[i + yl][j + kl] })
        // 単位の途中の位置は、その単位の終わりに対応させる
        for (t in 1..yomiLength) result[i + t] = j + kanaLength
        i += yomiLength
        j += kanaLength
    }
    return result
}

/** [yomi] の [i] 文字目・[kana] の [j] 文字目から始まる対応の候補（読みの長さ, 仮名の長さ）のうち、[accept] が true を返す最初のもの。 */
private inline fun firstUnit(
    yomi: String,
    kana: String,
    i: Int,
    j: Int,
    accept: (yomiLength: Int, kanaLength: Int) -> Boolean,
): Pair<Int, Int>? {
    // 1文字どうし（同じ文字、または歴史的仮名遣いの1文字対応）を先に見る
    if (i < yomi.length && j < kana.length) {
        val y = yomi[i]
        val k = kana[j]
        if ((y == k || SINGLE_KANA[k] == y) && accept(1, 1)) return 1 to 1
    }
    for ((historical, modern) in HISTORICAL_UNITS) {
        if (kana.startsWith(historical, j) && yomi.startsWith(modern, i) && accept(modern.length, historical.length)) {
            return modern.length to historical.length
        }
    }
    return null
}

private inline fun anyUnit(
    yomi: String,
    kana: String,
    i: Int,
    j: Int,
    accept: (yomiLength: Int, kanaLength: Int) -> Boolean,
): Boolean = firstUnit(yomi, kana, i, j, accept) != null
