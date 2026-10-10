package io.github.abonsan.hyakunin.game

import io.github.abonsan.hyakunin.data.Poems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class KimarijiTest {
    private fun poem(no: Int, kami: String) = testPoem(no, kami)

    @Test
    fun uniqueFirstKanaIsOne() {
        val a = poem(1, "あきのたの")
        val b = poem(2, "はるすぎて")
        assertEquals(1, kimarijiLength(a, listOf(a, b)))
        assertEquals(1, kimarijiLength(b, listOf(a, b)))
    }

    @Test
    fun sharedPrefixExtendsByOne() {
        val a = poem(1, "あきのたの")
        val b = poem(2, "あきかぜの")
        // 共通は「あき」の2文字なので3文字目で決まる
        assertEquals(3, kimarijiLength(a, listOf(a, b)))
        assertEquals(3, kimarijiLength(b, listOf(a, b)))
    }

    @Test
    fun targetIsIgnoredInUnread() {
        val a = poem(1, "あきのたの")
        assertEquals(1, kimarijiLength(a, listOf(a)))
        assertEquals(1, kimarijiLength(a, emptyList()))
    }

    @Test
    fun spacesAreIgnored() {
        val a = poem(1, "あさ　ぼらけ　ありあけの")
        val b = poem(2, "あさぼらけ　うじのかわぎり")
        assertEquals(6, kimarijiLength(a, listOf(a, b)))
    }

    @Test
    fun readCardsShortenKimariji() {
        // 「あさぼらけ　あ…」「あさぼらけ　う…」「あさじうの…」
        val a = poem(1, "あさぼらけ　ありあけの")
        val b = poem(2, "あさぼらけ　うじのかわぎり")
        val c = poem(3, "あさじうの　のべのしのはら")
        val all = listOf(a, b, c)
        assertEquals(6, kimarijiLength(a, all))
        assertEquals(6, kimarijiLength(b, all))
        assertEquals(3, kimarijiLength(c, all))

        // b が読まれると、a は c とだけ区別すればよい（「あさぼ」）
        assertEquals(3, kimarijiLength(a, listOf(a, c)))
        // a が読まれると b も同様
        assertEquals(3, kimarijiLength(b, listOf(b, c)))
        // 両方読まれた後の c は「あ」だけで決まる
        assertEquals(1, kimarijiLength(c, listOf(c)))
        // c が読まれても a と b は互いに紛らわしい
        assertEquals(6, kimarijiLength(a, listOf(a, b)))
    }

    @Test
    fun smallKanaExtendsKimariji() {
        // 共通は「か」だけなので「かし」で決まるが、直後の小書き仮名「ゃ」は一音として読むので3文字になる
        val a = poem(1, "かしゃくの")
        val b = poem(2, "かるくの")
        assertEquals(3, kimarijiLength(a, listOf(a, b)))

        // 先頭1文字で決まる場合も、直後の小書き仮名を含めて「ちょ」の2文字
        val c = poem(3, "ちょかくの")
        val d = poem(4, "はるすぎて")
        assertEquals(2, kimarijiLength(c, listOf(c, d)))

        // 共通部分の後ろに小書き仮名が続く場合
        val e = poem(5, "きしゃのくの")
        val f = poem(6, "きしろのくの")
        assertEquals(3, kimarijiLength(e, listOf(e, f)))
    }

    @Test
    fun woAndOAreTheSameSound() {
        val a = poem(1, "をぐらやま")
        val b = poem(2, "おぐらのみ")
        // 「を」と「お」は同じ音なので、「おぐら」の3文字まで一致する
        assertEquals(4, kimarijiLength(a, listOf(a, b)))
        assertEquals(4, kimarijiLength(b, listOf(a, b)))
        val c = poem(3, "おとこやま")
        // 「おとこやま」とは「お」だけが共通
        assertEquals(2, kimarijiLength(a, listOf(a, c)))
    }

    @Test
    fun longVowelIsTheSameSound() {
        // 44番「おうことの」は60番「おおえやま」・95番「おおけなく」と2文字目まで同じ音
        val a = poem(44, "おうことの　たえてしなくは")
        val b = poem(60, "おおえやま　いくののみちの")
        val c = poem(95, "おおけなく　うきよのたみに")
        val all = listOf(a, b, c)
        assertEquals(3, kimarijiLength(a, all))
        assertEquals(3, kimarijiLength(b, all))
        assertEquals(3, kimarijiLength(c, all))
        // 44番だけが相手でも、「おお」までは同じなので3文字
        assertEquals(3, kimarijiLength(b, listOf(a, b)))
    }

    @Test
    fun longVowelOnlyAfterORow() {
        // 「あう」と「ああ」は別の音
        val a = poem(1, "あうの")
        val b = poem(2, "ああの")
        assertEquals(2, kimarijiLength(a, listOf(a, b)))
    }

    @Test
    fun cappedAtWholeUpperPhrase() {
        // 同じ読みなら区別できないので、上の句全体の長さが上限
        val a = poem(1, "あいうえお")
        val b = poem(2, "あいうえお")
        assertEquals(5, kimarijiLength(a, listOf(a, b)))
        // 他の歌の読みが上の句全体の接頭辞になっている場合
        val c = poem(3, "あいう")
        val d = poem(4, "あいうえ")
        assertEquals(3, kimarijiLength(c, listOf(c, d)))
        assertEquals(4, kimarijiLength(d, listOf(c, d)))
    }

    @Test
    fun emptyReadingHasNoKimariji() {
        assertEquals(0, kimarijiLength(poem(1, ""), listOf(poem(2, "あ"))))
    }

    @Test
    fun syntheticPoemsStartWithTwoKana() {
        for (p in SYNTHETIC_POEMS) assertEquals(2, kimarijiLength(p, SYNTHETIC_POEMS))
        // 同じ先頭仮名の10首のうち、自分以外が読まれていれば1文字で決まる
        val p = SYNTHETIC_POEMS[0]
        assertEquals(1, kimarijiLength(p, listOf(p) + SYNTHETIC_POEMS.drop(10)))
    }

    @Test
    fun initialKimarijiDistributionOfTheHundredPoems() {
        // 100首がそろっているときだけ確かめる（暫定データではスキップ）
        val all = Poems.all
        assumeTrue(all.size == 100)
        val distribution = all.groupingBy { kimarijiLength(it, all) }.eachCount().toSortedMap()
        // 一字7・二字42・三字37・四字6・五字2・六字6
        assertEquals(mapOf(1 to 7, 2 to 42, 3 to 37, 4 to 6, 5 to 2, 6 to 6), distribution)
        // 44番「おうことの」は60番・95番と2文字目まで同じ音なので3
        assertEquals(3, kimarijiLength(all[43], all))
    }

    @Test
    fun invariantsHoldForBundledPoems() {
        // 同梱データ（暫定でも100首でも）に対して成り立つべき性質
        val all = Poems.all
        for (p in all) {
            val t = normalizeYomi(p.kamiYomi)
            val k = kimarijiLength(p, all)
            assertTrue("poem ${p.no}: k=$k", k in 1..t.length)
            val prefix = t.take(k)
            for (o in all) {
                if (o.no == p.no) continue
                val other = normalizeYomi(o.kamiYomi)
                // k 文字目までで、他のどの歌とも区別できる
                assertTrue("poem ${p.no} vs ${o.no}", !other.startsWith(prefix) || other == t)
            }
        }
    }
}
