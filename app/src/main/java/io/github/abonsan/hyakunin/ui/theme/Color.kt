package io.github.abonsan.hyakunin.ui.theme

import androidx.compose.ui.graphics.Color

// 和の配色。文字に使う色は生成り（Washi）の上で 4.5:1 以上のコントラストを確保する。

/** 和紙の生成り。画面の背景。 */
val Washi = Color(0xFFF4ECD8)

/** 明るい生成り。パネルやダイアログの面。 */
val WashiLight = Color(0xFFFAF5E8)

/** 濃い生成り。区切りや控えめな面。 */
val WashiDeep = Color(0xFFE9DEC3)

/** 胡粉。朱や紺の上に置く文字。 */
val Gofun = Color(0xFFFFFBF2)

/** 取り札の札面。 */
val CardFace = Color(0xFFFCF8EE)

/** 取り札の文字。 */
val CardInk = Color(0xFF000000)

/** 墨。本文の文字。 */
val Sumi = Color(0xFF2B2621)

/** 薄墨。装飾や、読み終えたあとの控えめな文字。 */
val Usuzumi = Color(0xFF8A8178)

/** 文字用の薄墨。補足の文字に使う（生成りの上で約 5:1）。 */
val UsuzumiText = Color(0xFF6B6259)

/** 朱。主ボタンとアクセント。 */
val Shu = Color(0xFFC8452D)

/** 淡い朱。案内の帯など。 */
val ShuPale = Color(0xFFF6DED4)

/** 濃い朱。淡い面の上に置く朱の文字（淡い朱の上で約 5.4:1）。 */
val ShuDeep = Color(0xFFA3321D)

/** 朱の墨。淡い朱の面の上の文字。 */
val ShuInk = Color(0xFF5A1C10)

/** 明るい朱。暗い面の上の朱。 */
val ShuLight = Color(0xFFFFB4A1)

/** 金茶。枠や区切り線。 */
val Kincha = Color(0xFFB08D3C)

/** 淡い金茶。控えめな面。 */
val KinchaPale = Color(0xFFF0E2BD)

/** 金茶の墨。淡い金茶の面の上の文字。 */
val KinchaInk = Color(0xFF3B2E0D)

/** 薄い金茶の線。 */
val KinchaLine = Color(0xFFD9CBA3)

/** 金。新しく来た札の縁。 */
val Kin = Color(0xFFD4A32F)

/** 紺。選択中の項目や自陣の印。 */
val Kon = Color(0xFF1F3A5F)

/** 淡い紺。 */
val KonPale = Color(0xFFDDE3EB)

/** 畳。陣の地。 */
val Tatami = Color(0xFFC9C08A)

/** 畳目の線。 */
val TatamiLine = Color(0xFFA99E62)

/** 畳縁。 */
val TatamiHeri = Color(0xFF233650)

/** 取り札の縁（裏紙の緑）。 */
val CardEdge = Color(0xFF295737)

/** 決まり字の緑（生成りの上で約 5.3:1）。 */
val KimarijiGreen = Color(0xFF2A6E2E)

/** 決まり字を超えて読まれた部分の赤（生成りの上で約 4.9:1）。 */
val OverReadRed = Color(0xFFC3272B)
