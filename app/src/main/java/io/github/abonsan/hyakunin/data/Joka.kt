package io.github.abonsan.hyakunin.data

/**
 * 序歌（難波津の歌）。競技かるたでは最初の札を読む前に、上の句→下の句→下の句（もう一度）の順に読む。
 *
 * 句の区切りは [Poem] と同じ全角スペース（U+3000）。[poem] は、前置きの表示で百首と同じ部品を使えるように
 * 歌番号 0 の [Poem] にしたもの（取り札はない）。
 */
data object Joka {
    /** 上の句の表記。 */
    val kami: String = "難波津に　咲くやこの花　冬ごもり"

    /** 下の句の表記。 */
    val shimo: String = "今を春べと　咲くやこの花"

    /** 上の句の読み（現代仮名遣い）。仮名は17。 */
    val kamiYomi: String = "なにわずに　さくやこのはな　ふゆごもり"

    /** 下の句の読み（現代仮名遣い）。仮名は14。 */
    val shimoYomi: String = "いまをはるべと　さくやこのはな"

    /** 上の句の歴史的仮名遣い。 */
    val kamiKana: String = "なにはづに　さくやこのはな　ふゆごもり"

    /** 下の句の歴史的仮名遣い。 */
    val shimoKana: String = "いまをはるべと　さくやこのはな"

    /** 前置きの表示用。歌番号は 0。 */
    val poem: Poem = Poem(
        no = 0,
        kami = kami,
        shimo = shimo,
        kamiYomi = kamiYomi,
        shimoYomi = shimoYomi,
        kamiKana = kamiKana,
        shimoKana = shimoKana,
        author = "王仁",
        authorYomi = "わに",
    )
}
