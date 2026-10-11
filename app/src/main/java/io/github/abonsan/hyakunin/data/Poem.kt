package io.github.abonsan.hyakunin.data

/**
 * 小倉百人一首の一首。
 *
 * 句の区切りはすべて全角スペース（U+3000）。
 * - [kami] / [shimo]: 表記（漢字かな交じり、歴史的仮名遣い）
 * - [kamiYomi] / [shimoYomi]: 読み上げの読み（現代仮名遣い、ひらがな）。決まり字の判定と読み上げ表示に使う
 * - [kamiKana] / [shimoKana]: 漢字をひらがなに直しただけの表記（歴史的仮名遣い、ひらがな）。
 *   [shimoKana] は取り札に書かれる下の句で、[kamiKana] は上の句の同じ表記（句は3句）。
 *   読みとの文字位置の対応は `verseBoundaries`（game/KanaAlignment.kt）で求める
 */
data class Poem(
    val no: Int,
    val kami: String,
    val shimo: String,
    val kamiYomi: String,
    val shimoYomi: String,
    val kamiKana: String,
    val shimoKana: String,
    val author: String,
    val authorYomi: String,
)
