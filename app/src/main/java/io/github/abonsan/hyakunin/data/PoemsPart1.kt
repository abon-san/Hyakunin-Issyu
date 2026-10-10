package io.github.abonsan.hyakunin.data

// 暫定データ（1〜3番のみ）。データ作成時に1〜50番へ置き換える。
internal val POEMS_1_TO_50: List<Poem> = listOf(
    Poem(
        no = 1,
        kami = "秋の田の　かりほの庵の　苫をあらみ",
        shimo = "わが衣手は　露にぬれつつ",
        kamiYomi = "あきのたの　かりおのいおの　とまをあらみ",
        shimoYomi = "わがころもでは　つゆにぬれつつ",
        shimoKana = "わがころもでは　つゆにぬれつつ",
        author = "天智天皇",
        authorYomi = "てんじてんのう",
    ),
    Poem(
        no = 2,
        kami = "春過ぎて　夏来にけらし　白妙の",
        shimo = "衣ほすてふ　天の香具山",
        kamiYomi = "はるすぎて　なつきにけらし　しろたえの",
        shimoYomi = "ころもほすちょう　あまのかぐやま",
        shimoKana = "ころもほすてふ　あまのかぐやま",
        author = "持統天皇",
        authorYomi = "じとうてんのう",
    ),
    Poem(
        no = 3,
        kami = "あしびきの　山鳥の尾の　しだり尾の",
        shimo = "ながながし夜を　ひとりかも寝む",
        kamiYomi = "あしびきの　やまどりのおの　しだりおの",
        shimoYomi = "ながながしよを　ひとりかもねむ",
        shimoKana = "ながながしよを　ひとりかもねむ",
        author = "柿本人麻呂",
        authorYomi = "かきのもとのひとまろ",
    ),
)
