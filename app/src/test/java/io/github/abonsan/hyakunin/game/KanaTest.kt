package io.github.abonsan.hyakunin.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KanaTest {
    @Test
    fun kanaCountIgnoresSpaces() {
        assertEquals(0, kanaCount(""))
        assertEquals(12, kanaCount("あきのたの　かりおのいおの"))
        assertEquals(12, kanaCount("あきのたの かりおのいおの"))
        assertEquals(12, kanaCount("　あきのたの　　かりおのいおの　"))
        assertEquals("あきのたのかりおのいおの", stripYomiSpaces("あきのたの　かりおのいおの"))
    }

    @Test
    fun normalizeUnifiesSameSoundKana() {
        assertEquals("おとめ", normalizeYomi("をとめ"))
        assertEquals("いまこむ", normalizeYomi("ゐまこむ"))
        assertEquals("えび", normalizeYomi("ゑび"))
        assertEquals("じ", normalizeYomi("ぢ"))
        assertEquals("ず", normalizeYomi("づ"))
        // 変化しない仮名はそのまま
        assertEquals("あきのたの", normalizeYomi("あきのたの"))
    }

    @Test
    fun normalizeKeepsLengthExceptForSpaces() {
        val yomi = "おうことの　をぐらやま"
        assertEquals(kanaCount(yomi), normalizeYomi(yomi).length)
    }

    @Test
    fun longVowelAfterORowIsTreatedAsO() {
        // 「おう」と「おお」は同じ音
        assertEquals(normalizeYomi("おおえやま"), "おおえやま")
        assertEquals("おおこと", normalizeYomi("おうこと"))
        // お段の仮名ごとに確認
        for (c in "おこそとのほもよろごぞどぼぽょ") {
            assertEquals("${c}お", normalizeYomi("${c}う"))
        }
        // 「を」もお段
        assertEquals("おお", normalizeYomi("をう"))
        // お段以外の後の「う」はそのまま
        assertEquals("あう", normalizeYomi("あう"))
        assertEquals("くう", normalizeYomi("くう"))
        // 句の区切りをまたぐ「う」は長音にしない
        assertEquals("のう", normalizeYomi("の　う"))
    }

    @Test
    fun smallKana() {
        for (c in "ゃゅょぁぃぅぇぉゎ") assertTrue(isSmallKana(c))
        assertFalse(isSmallKana('や'))
        assertFalse(isSmallKana('つ'))
    }

    @Test
    fun commonPrefix() {
        assertEquals(0, commonPrefixLength("あい", "うえ"))
        assertEquals(2, commonPrefixLength("あいう", "あいえお"))
        assertEquals(2, commonPrefixLength("あい", "あいう"))
        assertEquals(0, commonPrefixLength("", "あ"))
    }
}
