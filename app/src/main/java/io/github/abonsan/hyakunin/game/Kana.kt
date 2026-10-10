package io.github.abonsan.hyakunin.game

// 読み（現代仮名遣い、ひらがな）を扱うユーティリティ。
// 句の区切りは全角スペースなどの空白で、仮名の数には数えない。

/** お段の仮名。直後の「う」は長音として「お」と同じ音になる。 */
private const val O_ROW = "おこそとのほもよろごぞどぼぽょ"

/** 直前の仮名と合わせて一音で読む小書き仮名。 */
private const val SMALL_KANA = "ゃゅょぁぃぅぇぉゎ"

/** 句の区切り（空白）かどうか。 */
internal fun isYomiSpace(c: Char): Boolean = c.isWhitespace()

/** 小書き仮名（拗音など）かどうか。 */
fun isSmallKana(c: Char): Boolean = c in SMALL_KANA

/** 空白を除いた仮名の数。 */
fun kanaCount(yomi: String): Int = yomi.count { !isYomiSpace(it) }

/** 空白を取り除いた読み。 */
fun stripYomiSpaces(yomi: String): String = yomi.filterNot { isYomiSpace(it) }

/**
 * 決まり字の比較用に読みを正規化する。空白は取り除く。
 *
 * - を→お、ゐ→い、ゑ→え、ぢ→じ、づ→ず（読み上げ音が同じため）
 * - お段の仮名の直後の「う」は「お」にする（長音。「おう」と「おお」は同じ音）
 *
 * 1文字を1文字に置き換えるだけなので、正規化後の位置は元の仮名の位置（空白を除く）と一致する。
 */
fun normalizeYomi(yomi: String): String {
    val sb = StringBuilder(yomi.length)
    // 直前の仮名（う→お の置換前）。句の区切りでは長音として扱わない。
    var prev: Char? = null
    for (c in yomi) {
        if (isYomiSpace(c)) {
            prev = null
            continue
        }
        val mapped = when (c) {
            'を' -> 'お'
            'ゐ' -> 'い'
            'ゑ' -> 'え'
            'ぢ' -> 'じ'
            'づ' -> 'ず'
            else -> c
        }
        val p = prev
        sb.append(if (mapped == 'う' && p != null && p in O_ROW) 'お' else mapped)
        prev = mapped
    }
    return sb.toString()
}

/** 2つの文字列の先頭から一致している文字数。 */
fun commonPrefixLength(a: String, b: String): Int {
    val n = minOf(a.length, b.length)
    var i = 0
    while (i < n && a[i] == b[i]) i++
    return i
}
