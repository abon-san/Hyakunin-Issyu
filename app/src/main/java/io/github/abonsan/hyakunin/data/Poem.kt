package io.github.abonsan.hyakunin.data

/**
 * 小倉百人一首の一首。
 *
 * 句の区切りはすべて全角スペース（U+3000）。
 * - [kami] / [shimo]: 表記（漢字かな交じり、歴史的仮名遣い）
 * - [kamiYomi] / [shimoYomi]: 読み上げの読み（現代仮名遣い、ひらがな）。決まり字の判定と読み上げ表示に使う
 * - [shimoKana]: 取り札に書かれる下の句（歴史的仮名遣い、ひらがな）
 */
data class Poem(
    val no: Int,
    val kami: String,
    val shimo: String,
    val kamiYomi: String,
    val shimoYomi: String,
    val shimoKana: String,
    val author: String,
    val authorYomi: String,
)
