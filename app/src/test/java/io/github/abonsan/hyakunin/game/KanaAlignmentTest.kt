package io.github.abonsan.hyakunin.game

import io.github.abonsan.hyakunin.data.Poems
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KanaAlignmentTest {

    @Test
    fun identicalTextMapsOneToOne() {
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5), alignPhrase("あきのたの", "あきのたの"))
        assertArrayEquals(intArrayOf(0), alignPhrase("", ""))
    }

    @Test
    fun singleCharCorrespondences() {
        val pairs = mapOf(
            'は' to 'わ', 'ひ' to 'い', 'ふ' to 'う', 'へ' to 'え', 'ほ' to 'お',
            'ゐ' to 'い', 'ゑ' to 'え', 'を' to 'お', 'ぢ' to 'じ', 'づ' to 'ず',
        )
        for ((historical, modern) in pairs) {
            assertArrayEquals("$historical", intArrayOf(0, 1, 2, 3), alignPhrase("か${modern}き", "か${historical}き"))
        }
        // 読みでも「は」「ふ」などのままの文字（ちはやぶる、もみじふみわけ）は同じ文字として対応する
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5), alignPhrase("ちはやぶる", "ちはやぶる"))
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4), alignPhrase("ふみわけ", "ふみわけ"))
    }

    @Test
    fun multiCharCorrespondencesMapMidUnitToEndOfUnit() {
        // 複数文字の単位の途中の位置は、その単位の終わりに対応する
        assertArrayEquals(intArrayOf(0, 2, 2, 2), alignPhrase("ちょう", "てふ"))
        assertArrayEquals(intArrayOf(0, 2, 2, 2), alignPhrase("きょう", "けふ"))
        assertArrayEquals(intArrayOf(0, 2, 2), alignPhrase("おう", "あふ"))
        assertArrayEquals(intArrayOf(0, 2, 2), alignPhrase("おう", "わう"))
        // 文字数が減る対応
        assertArrayEquals(intArrayOf(0, 2, 3), alignPhrase("かじ", "くわじ"))
        assertArrayEquals(intArrayOf(0, 2, 3), alignPhrase("がん", "ぐわん"))
        // 3文字の単位
        assertArrayEquals(intArrayOf(0, 3, 3, 3), alignPhrase("ちゅう", "ちゆう"))
        assertArrayEquals(intArrayOf(0, 3, 3, 3, 4), alignPhrase("じゅうた", "じゆうた"))
    }

    @Test
    fun everyMultiCharUnitAlignsAsAWhole() {
        for ((historical, modern) in HISTORICAL_UNITS) {
            val expected = IntArray(modern.length + 1) { if (it == 0) 0 else historical.length }
            assertArrayEquals("$historical -> $modern", expected, alignPhrase(modern, historical))
            // 前後に他の文字が続いても単位の境界は変わらない
            val surrounded = alignPhrase("あ${modern}い", "あ${historical}い")
            assertEquals("$historical -> $modern", 1 + historical.length, surrounded[1 + modern.length])
            assertEquals("$historical -> $modern", 2 + historical.length, surrounded.last())
        }
    }

    @Test
    fun specificUnitsFromTheListOfModernSpellings() {
        val examples = listOf(
            "てふ" to "ちょう", "けふ" to "きょう", "あふ" to "おう", "わう" to "おう", "かう" to "こう",
            "さう" to "そう", "たう" to "とう", "なう" to "のう", "はう" to "ほう", "まう" to "もう",
            "やう" to "よう", "らう" to "ろう", "せう" to "しょう", "てう" to "ちょう", "けう" to "きょう",
            "めう" to "みょう", "れう" to "りょう", "しう" to "しゅう", "ちう" to "ちゅう", "きう" to "きゅう",
            "りう" to "りゅう", "ちゆう" to "ちゅう", "じゆう" to "じゅう", "くわ" to "か", "ぐわ" to "が",
        )
        for ((historical, modern) in examples) {
            val b = alignPhrase(modern, historical)
            assertEquals("$historical -> $modern", historical.length, b.last())
            assertEquals("$historical -> $modern", 0, b.first())
            assertTrue("$historical -> $modern", b.drop(1).all { it == historical.length })
        }
    }

    @Test
    fun poem2ShimoTeFuIsChou() {
        val b = verseBoundaries(Poems.byNo(2))
        // 上の句 17 文字は 1 対 1（しろたえの↔しろたへの）
        assertArrayEquals((0..17).toList().toIntArray(), b.copyOfRange(0, 18))
        // 下の句「ころもほすちょう」↔「ころもほすてふ」：ころもほす（5文字）の後に「ちょう」が「てふ」に対応する
        assertEquals(listOf(18, 19, 20, 21, 22), (1..5).map { b[17 + it] })
        assertEquals(listOf(24, 24, 24), (6..8).map { b[17 + it] })
        // 続く「あまのかぐやま」
        assertEquals(listOf(25, 26, 27, 28, 29, 30, 31), (9..15).map { b[17 + it] })
        assertEquals(17 + 8 + 7 + 1, b.size)
        assertEquals(31, b.last())
    }

    @Test
    fun poem61ShimoKyouIsKefu() {
        val b = verseBoundaries(Poems.byNo(61))
        // 上の句は「いにしえの　ならのみやこの　やえざくら」の17文字
        assertEquals(17, b[17])
        // 下の句「きょうここのえに」↔「けふここのへに」
        assertEquals(listOf(19, 19, 19), (1..3).map { b[17 + it] })
        assertEquals(listOf(20, 21, 22, 23, 24), (4..8).map { b[17 + it] })
        // 続く「においぬるかな」↔「にほひぬるかな」
        assertEquals(31, b.last())
    }

    @Test
    fun poem44KamiOuIsAfu() {
        val b = verseBoundaries(Poems.byNo(44))
        // 上の句「おうことの」↔「あふことの」：「お」まで読んだら「あふ」まで読んだことにする
        assertArrayEquals(intArrayOf(0, 2, 2, 3, 4, 5), b.copyOfRange(0, 6))
        // 決まり字（おうこ＝3文字）は「あふこ」の3文字
        assertEquals(3, b[kimarijiLength(Poems.byNo(44), Poems.all)])
    }

    @Test
    fun unalignableTextIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { alignPhrase("あか", "あお") }
        assertThrows(IllegalArgumentException::class.java) { alignPhrase("あ", "あい") }
        assertThrows(IllegalArgumentException::class.java) { alignPhrase("あいう", "あい") }
        // 単位の一部だけでは対応づけできない（「ちょ」に対する「て」）
        assertThrows(IllegalArgumentException::class.java) { alignPhrase("ちょ", "て") }
        // 「か」と「く」（くわ→か の途中）
        assertThrows(IllegalArgumentException::class.java) { alignPhrase("か", "く") }
    }

    @Test
    fun poemsThatCannotBeAlignedAreRejectedWithTheirNumber() {
        val poem = Poems.byNo(1)
        // 読みと対応しない仮名
        val unmatched = assertThrows(IllegalArgumentException::class.java) {
            verseBoundaries(poem.copy(kamiKana = "あきのたの　かりほのいほの　とまをあらい"))
        }
        assertTrue(unmatched.message, unmatched.message!!.startsWith("1番"))
        // 句の数が違う
        val phrases = assertThrows(IllegalArgumentException::class.java) {
            verseBoundaries(poem.copy(shimoKana = "わがころもでは　つゆに　ぬれつつ"))
        }
        assertTrue(phrases.message, phrases.message!!.startsWith("1番"))
    }

    @Test
    fun allPoemsAlignInBothVerses() {
        for (poem in Poems.all) {
            val b = verseBoundaries(poem)
            val kamiYomi = kanaCount(poem.kamiYomi)
            val yomi = kamiYomi + kanaCount(poem.shimoYomi)
            val kamiKana = kanaCount(poem.kamiKana)
            val kana = kamiKana + kanaCount(poem.shimoKana)
            assertEquals("${poem.no} size", yomi + 1, b.size)
            assertEquals("${poem.no} first", 0, b.first())
            assertEquals("${poem.no} last", kana, b.last())
            // 上の句を読み終えた位置は、上の句の歴史的仮名遣いの終わりに対応する
            assertEquals("${poem.no} kami end", kamiKana, b[kamiYomi])
            for (i in 1 until b.size) {
                assertTrue("${poem.no} monotone at $i: ${b.toList()}", b[i] >= b[i - 1])
            }
        }
    }

    @Test
    fun allPoemsAlignPhraseByPhrase() {
        // 句の切れ目（読みの句の終わり）は、歴史的仮名遣いの句の終わりに対応する
        for (poem in Poems.all) {
            val b = verseBoundaries(poem)
            var yomiEnd = 0
            var kanaEnd = 0
            val yomiPhrases = poem.kamiYomi.split('　') + poem.shimoYomi.split('　')
            val kanaPhrases = poem.kamiKana.split('　') + poem.shimoKana.split('　')
            assertEquals("${poem.no} phrases", 5, kanaPhrases.size)
            for (p in yomiPhrases.indices) {
                yomiEnd += yomiPhrases[p].length
                kanaEnd += kanaPhrases[p].length
                assertEquals("${poem.no} phrase $p", kanaEnd, b[yomiEnd])
            }
        }
    }

    @Test
    fun totalCountsMatchTheReadingStateContract() {
        // ReadingState.totalKanaCount = 読みの仮名の総数 なので、表の長さは totalKanaCount + 1
        for (poem in Poems.all) {
            val total = kanaCount(poem.kamiYomi) + kanaCount(poem.shimoYomi)
            assertEquals("${poem.no}", total + 1, verseBoundaries(poem).size)
        }
    }
}
