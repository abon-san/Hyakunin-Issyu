package io.github.abonsan.hyakunin.game.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.data.Joka
import io.github.abonsan.hyakunin.data.Poems
import io.github.abonsan.hyakunin.game.ReadTiming
import io.github.abonsan.hyakunin.game.ReadingSpeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** アセットの読み上げ音声（timings.json）の読み込みと、歌の読みとの照らし合わせ。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AssetVoiceBankTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** src/test/resources/voice/timings_fixture.json（本物の時刻表から作り、いくつかを壊したもの）。 */
    private val fixture: String =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("voice/timings_fixture.json")).use {
            it.readBytes().decodeToString()
        }

    /** 時刻表にあるファイルのうち、4番の上の句を除いたもの（そのファイルがないことにする）。 */
    private val files: Set<String> =
        Regex(""""file":\s*"([^"]+)"""").findAll(fixture).map { it.groupValues[1] }.toSet() - "004_kami.ogg"

    @Test
    fun clipsThatFitTheReadingsAreUsedAndTheOthersFallBackToText() {
        val warnings = mutableListOf<String>()
        val bank = AssetVoiceBank.parse(fixture, "voice/test", Poems.all, files, warn = { warnings += it })
        assertNotNull(bank)
        bank!!
        // 1・2・8番は上の句・下の句とも使える
        assertEquals(3, bank.poemCount)
        val one = bank.poem(1)!!
        assertEquals("voice/test/001_kami.ogg", one.kami.asset)
        assertEquals("voice/test/001_shimo.ogg", one.shimo.asset)
        assertTrue(one.fits(Poems.byNo(1)))
        // 同じ句が2つあれば先のものを使う
        assertEquals(4213L, one.kami.durationMs)
        assertEquals(18, one.kami.kanaStartMs.size)
        assertEquals(107, one.kami.kanaStartMs.first())
        assertNotNull(bank.poem(2))
        // 8番は別読み（_alt）を使わない
        assertEquals("voice/test/008_shimo.ogg", bank.poem(8)!!.shimo.asset)

        // 使えないクリップのある歌は、音声なし（文字だけの時刻表）になる
        for (no in listOf(3, 4, 5, 6, 7, 9, 10, 100)) assertNull("$no", bank.poem(no))
        assertTrue(warnings.toString(), warnings.any { it.startsWith("003_shimo.ogg: 仮名の数が合わない（時刻表 13、読み 14）") })
        assertTrue(warnings.toString(), warnings.any { it == "004_kami.ogg: ファイルがない" })
        assertTrue(warnings.toString(), warnings.any { it.startsWith("005_shimo.ogg: 読みが違う") })
        assertTrue(warnings.toString(), warnings.any { it.startsWith("006_kami.ogg: 時刻表がおかしい") })
        assertTrue(warnings.toString(), warnings.any { it.startsWith("007_shimo.ogg: 時刻表がおかしい") })
        assertTrue(warnings.toString(), warnings.any { it.startsWith("9番は上の句と下の句の音声がそろわない") })
        assertTrue(warnings.toString(), warnings.any { it.startsWith("001_kami.ogg: 同じ句のクリップが2つある") })
        assertTrue(warnings.toString(), warnings.any { it == "101_kami.ogg: 101番の歌がない" })
        assertTrue(warnings.toString(), warnings.any { it.startsWith("file・poem・part のない項目") })
        assertTrue(warnings.toString(), warnings.none { "alt" in it })

        // 序歌
        assertEquals("voice/test/joka_kami.ogg", bank.jokaKami!!.asset)
        assertTrue(bank.jokaKami!!.fits(Joka.kamiYomi))
        assertTrue(bank.jokaShimo!!.fits(Joka.shimoYomi))
        assertEquals(6125L, bank.jokaShimo!!.durationMs)
    }

    @Test
    fun withoutAFileListEveryListedClipIsTried() {
        val bank = AssetVoiceBank.parse(fixture, "voice/test", Poems.all, files = null, warn = {})!!
        // ファイルを確かめなければ 4番も使える
        assertNotNull(bank.poem(4))
        assertEquals(4, bank.poemCount)
    }

    @Test
    fun brokenOrEmptyTimingsGiveNoVoice() {
        val warnings = mutableListOf<String>()
        assertNull(AssetVoiceBank.parse("{", "voice/test", warn = { warnings += it }))
        assertNull(AssetVoiceBank.parse("""{"version": "v1"}""", "voice/test", warn = { warnings += it }))
        assertNull(AssetVoiceBank.parse("""{"clips": []}""", "voice/test", warn = { warnings += it }))
        // どのクリップも合わない
        val bad = """{"clips": [{"file": "001_kami.ogg", "poem": 1, "part": "kami", "durationMs": 100, "kanaStartMs": [1, 2]}]}"""
        assertNull(AssetVoiceBank.parse(bad, "voice/test", warn = { warnings += it }))
        assertTrue(warnings.toString(), warnings.any { it.startsWith("timings.json を読めない") })
        assertTrue(warnings.toString(), warnings.any { it == "使える音声がないので、音声なしで読む" })
    }

    @Test
    fun missingAssetsGiveNoVoiceWithoutThrowing() {
        assertNull(AssetVoiceBank.load(context.assets, dir = "voice/none"))
        assertNull(AssetVoiceBank.load(context.assets, dir = ""))
        // 音声がなければ、エンジンは文字だけで読む（前置きは競技かるた式の文字だけ）
        val timing = ReadTiming.of(ReadingSpeed.NORMAL, AssetVoiceBank.load(context.assets, dir = "voice/none"), textLeadIn = true)
        assertNull(timing.voice)
    }

    /**
     * 入れてある音声（tools/voice/install_voice_assets.py で入れたもの）で、100首と序歌がそろい、すべて歌の読みに合う。
     * 音声は作り直すことがあるので、時刻の値そのものは確かめない。
     */
    @Test
    fun theInstalledVoiceCoversEveryPoemAndTheJoka() {
        val bank = AssetVoiceBank.load(context.assets)
        assertNotNull("app/src/main/assets/${AssetVoiceBank.DIR} に音声がない", bank)
        bank!!
        assertEquals(100, bank.poemCount)
        val assets = context.assets.list(AssetVoiceBank.DIR)!!.toSet()
        val clips = Poems.all.flatMap { poem ->
            val voice = bank.poem(poem.no)
            assertNotNull("${poem.no}", voice)
            assertTrue("${poem.no}", voice!!.fits(poem))
            listOf(voice.kami, voice.shimo)
        } + listOf(bank.jokaKami!!, bank.jokaShimo!!)
        assertTrue(bank.jokaKami!!.fits(Joka.kamiYomi))
        assertTrue(bank.jokaShimo!!.fits(Joka.shimoYomi))
        for (clip in clips) {
            assertTrue(clip.asset, clip.asset.removePrefix("${AssetVoiceBank.DIR}/") in assets)
            assertTrue(clip.asset, clip.durationMs > clip.kanaStartMs.last())
        }
        // 時刻表にあるファイルだけが入っている
        assertEquals(clips.map { it.asset.removePrefix("${AssetVoiceBank.DIR}/") }.toSet() + AssetVoiceBank.TIMINGS, assets)
    }
}
