package io.github.abonsan.hyakunin.ui.card

import io.github.abonsan.hyakunin.data.Poems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TorifudaColumnsTest {
    @Test
    fun splitsIntoThreeColumnsFromTheRight() {
        // 14文字 → 1列 5文字ずつ（最後の列は4文字）
        assertEquals(
            listOf("わがころも", "ではつゆに", "ぬれつつ"),
            torifudaColumns("わがころもでは　つゆにぬれつつ"),
        )
    }

    @Test
    fun columnLengthIsCeilOfAThird() {
        // 16文字 → 6・6・4
        assertEquals(
            listOf("ありあけのつ", "きをまちいで", "つるかな"),
            torifudaColumns("ありあけのつきを　まちいでつるかな"),
        )
        // 15文字 → 5・5・5
        assertEquals(listOf("あいうえお", "かきくけこ", "さしすせそ"), torifudaColumns("あいうえおかきくけこさしすせそ"))
    }

    @Test
    fun ignoresAnyWhitespace() {
        assertEquals(listOf("あい", "うえ", "お"), torifudaColumns(" あい　う\nえお "))
        assertEquals(emptyList<String>(), torifudaColumns("　"))
    }

    @Test
    fun everyPoemFitsInThreeColumnsOfAtMostSixRows() {
        for (poem in Poems.all) {
            val columns = torifudaColumns(poem.shimoKana)
            assertEquals("${poem.no}", 3, columns.size)
            assertTrue("${poem.no}", columns.all { it.length in 1..6 })
            assertEquals("${poem.no}", poem.shimoKana.filterNot { it.isWhitespace() }, columns.joinToString(""))
        }
    }
}
