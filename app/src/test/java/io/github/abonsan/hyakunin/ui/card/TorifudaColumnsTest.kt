package io.github.abonsan.hyakunin.ui.card

import io.github.abonsan.hyakunin.data.Poems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Normalizer

class TorifudaColumnsTest {
    @Test
    fun splitsFiveFiveAndTheRestFromTheRight() {
        // 14字 → 5・5・4
        assertEquals(
            listOf("わがころも", "ではつゆに", "ぬれつつ"),
            torifudaColumns("わがころもでは　つゆにぬれつつ"),
        )
        // 15字 → 5・5・5
        assertEquals(listOf("あいうえお", "かきくけこ", "さしすせそ"), torifudaColumns("あいうえおかきくけこさしすせそ"))
        // 16字 → 5・5・6（残りはすべて3列目）
        assertEquals(
            listOf("ありあけの", "つきをまち", "いでつるかな"),
            torifudaColumns("ありあけのつきを　まちいでつるかな"),
        )
    }

    @Test
    fun shortTextFillsTheColumnsFromTheRight() {
        assertEquals(emptyList<String>(), torifudaColumns(""))
        assertEquals(listOf("あ"), torifudaColumns("あ"))
        assertEquals(listOf("あいうえお"), torifudaColumns("あいうえお"))
        assertEquals(listOf("あいうえお", "か"), torifudaColumns("あいうえおか"))
        assertEquals(listOf("あいうえお", "かきくけこ"), torifudaColumns("あいうえおかきくけこ"))
        assertEquals(listOf("あいうえお", "かきくけこ", "さ"), torifudaColumns("あいうえおかきくけこさ"))
    }

    @Test
    fun ignoresAnyWhitespace() {
        // 句の区切りの全角スペースのほか、半角スペースや改行も字に数えない
        assertEquals(listOf("あいうえお", "か"), torifudaColumns(" あい　う\nえお\tか "))
        assertEquals(emptyList<String>(), torifudaColumns("　"))
    }

    @Test
    fun splittingKeepsTheCharacters() {
        // 列に分けるだけで濁点は外さない（参照画像と比べる札は、参照画像と同じく濁点つきで書く）
        assertEquals(listOf("いくよねざ", "めぬすまの", "せきもり"), torifudaColumns("いくよねざめぬ　すまのせきもり"))
    }

    @Test
    fun removesDakutenAndHandakuten() {
        assertEquals("かきくけこさしすせそたちつてとはひふへほ", withoutDakuten("がぎぐげござじずぜぞだぢづでどばびぶべぼ"))
        assertEquals("はひふへほ", withoutDakuten("ぱぴぷぺぽ"))
        assertEquals("う", withoutDakuten("ゔ"))
        assertEquals("カハウ", withoutDakuten("ガパヴ"))
        // 合成用の濁点・半濁点で書いた字も同じ
        assertEquals("かは", withoutDakuten("か\u3099は\u309A"))
        // 21番「ありあけのつきを　まちいでつるかな」は「いでつる」が「いてつる」になる
        assertEquals(listOf("ありあけの", "つきをまち", "いてつるかな"), torifudaColumns(withoutDakuten(Poems.byNo(21).shimoKana)))
    }

    @Test
    fun keepsTheOtherCharacters() {
        // 濁点の無い字・空白・歴史的仮名遣いの字・小書きの仮名・長音符はそのまま
        val text = "ありあけの　つきを ゐゑをんゃっー"
        assertEquals(text, withoutDakuten(text))
    }

    @Test
    fun everyCardHasFiveFiveAndFourToSixCharacters() {
        for (poem in Poems.all) {
            val label = "${poem.no}番"
            val written = withoutDakuten(poem.shimoKana).filterNot { it.isWhitespace() }
            val columns = torifudaColumns(withoutDakuten(poem.shimoKana))
            assertEquals(label, 3, columns.size)
            assertEquals(label, listOf(5, 5), columns.take(2).map { it.length })
            assertTrue(label, columns[2].length in 4..6)
            assertEquals(label, written, columns.joinToString(""))
            // 1字ずつ濁点を外すだけで、字の数は変わらない
            assertEquals(label, poem.shimoKana.filterNot { it.isWhitespace() }.length, written.length)
            // 濁点・半濁点のついた字（分解すると合成用の濁点が出る字）も、合成用の濁点そのものも残らない
            assertEquals(label, written, Normalizer.normalize(written, Normalizer.Form.NFD))
            assertTrue(label, written.none { it == '\u3099' || it == '\u309A' })
        }
        // 6字の列があるのは16字の21番だけ
        assertEquals(listOf(21), Poems.all.filter { torifudaColumns(withoutDakuten(it.shimoKana))[2].length == 6 }.map { it.no })
    }
}
