package io.github.abonsan.hyakunin.data

import io.github.abonsan.hyakunin.game.kimarijiLength
import io.github.abonsan.hyakunin.game.stripYomiSpaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PoemsTest {

    private val poems = Poems.all

    @Test
    fun hasAll100PoemsInOrder() {
        assertEquals((1..100).toList(), poems.map { it.no })
        poems.forEach { assertEquals(it, Poems.byNo(it.no)) }
    }

    @Test
    fun yomiAndKanaAreHiraganaSeparatedByIdeographicSpace() {
        val hiragana = Regex("[\\u3041-\\u3096]+(\\u3000[\\u3041-\\u3096]+)*")
        for (p in poems) {
            for ((name, value) in listOf(
                "kamiYomi" to p.kamiYomi,
                "shimoYomi" to p.shimoYomi,
                "kamiKana" to p.kamiKana,
                "shimoKana" to p.shimoKana,
                "authorYomi" to p.authorYomi,
            )) {
                assertTrue("${p.no} $name: $value", hiragana.matches(value))
            }
        }
    }

    @Test
    fun phraseCountsMatch() {
        fun phrases(s: String) = s.split('　').size
        for (p in poems) {
            assertEquals("${p.no} kami", 3, phrases(p.kami))
            assertEquals("${p.no} kamiYomi", 3, phrases(p.kamiYomi))
            assertEquals("${p.no} shimo", 2, phrases(p.shimo))
            assertEquals("${p.no} shimoYomi", 2, phrases(p.shimoYomi))
            assertEquals("${p.no} kamiKana", 3, phrases(p.kamiKana))
            assertEquals("${p.no} shimoKana", 2, phrases(p.shimoKana))
        }
    }

    @Test
    fun kamiKanaIsHistoricalKanaWithOdorijiExpanded() {
        assertEquals("あきのたの　かりほのいほの　とまをあらみ", Poems.byNo(1).kamiKana)
        assertEquals("はるすぎて　なつきにけらし　しろたへの", Poems.byNo(2).kamiKana)
        assertEquals("あふことの　たえてしなくば　なかなかに", Poems.byNo(44).kamiKana)
        // 踊り字（ゝ・ゞ）は展開してある
        assertEquals("かささぎの　わたせるはしに　おくしもの", Poems.byNo(6).kamiKana)
        assertEquals("つきみれば　ちぢにものこそ　かなしけれ", Poems.byNo(23).kamiKana)
        assertEquals("をぐらやま　みねのもみぢば　こころあらば", Poems.byNo(26).kamiKana)
        assertEquals("ひとはいさ　こころもしらず　ふるさとは", Poems.byNo(35).kamiKana)
        for (p in poems) {
            assertTrue("${p.no} 踊り字が残っている", p.kamiKana.none { it == 'ゝ' || it == 'ゞ' })
        }
    }

    @Test
    fun initialKimarijiDistributionMatchesStandard() {
        val distribution = poems.groupingBy { kimarijiLength(it, poems) }.eachCount()
        assertEquals(mapOf(1 to 7, 2 to 42, 3 to 37, 4 to 6, 5 to 2, 6 to 6), distribution.toSortedMap())
    }

    @Test
    fun initialKimarijiOfWellKnownPoems() {
        fun kimariji(no: Int): String {
            val poem = Poems.byNo(no)
            return stripYomiSpaces(poem.kamiYomi).take(kimarijiLength(poem, poems))
        }
        assertEquals("む", kimariji(87))
        assertEquals("す", kimariji(18))
        assertEquals("め", kimariji(57))
        assertEquals("ふ", kimariji(22))
        assertEquals("さ", kimariji(70))
        assertEquals("ほ", kimariji(81))
        assertEquals("せ", kimariji(77))
        assertEquals("あきの", kimariji(1))
        assertEquals("おうこ", kimariji(44))
        assertEquals("ちは", kimariji(17))
        assertEquals("ちぎりお", kimariji(75))
        assertEquals("ちぎりき", kimariji(42))
        assertEquals("よのなかは", kimariji(93))
        assertEquals("よのなかよ", kimariji(83))
        assertEquals("あさぼらけあ", kimariji(31))
        assertEquals("あさぼらけう", kimariji(64))
        assertEquals("きみがためは", kimariji(15))
        assertEquals("きみがためお", kimariji(50))
        assertEquals("わたのはらこ", kimariji(76))
        assertEquals("わたのはらや", kimariji(11))
    }
}
