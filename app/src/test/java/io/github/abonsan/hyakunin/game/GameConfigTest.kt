package io.github.abonsan.hyakunin.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GameConfigTest {

    @Test
    fun verseStyleDefaultsToHistoricalKana() {
        assertEquals(VerseStyle.KANA, GameConfig().verseStyle)
    }

    @Test
    fun verseStylesHaveLabelsAndDescriptions() {
        assertEquals(listOf(VerseStyle.READING, VerseStyle.KANA), VerseStyle.entries)
        assertEquals("読み（現代仮名遣い）", VerseStyle.READING.label)
        assertEquals("歴史的仮名遣い", VerseStyle.KANA.label)
        for (style in VerseStyle.entries) {
            assertTrue(style.name, style.description.isNotBlank())
        }
    }

    @Test
    fun verseStyleDoesNotChangeTheEngine() {
        // 表示だけに使う設定なので、どちらでも同じ並び・同じ読み上げの進み方になる
        fun stateAfterReading(style: VerseStyle): MatchUiState {
            val engine = MatchEngine.withLayout(
                config = GameConfig(emptyCardCount = 0, verseStyle = style),
                random = ZeroRandom,
                poems = SYNTHETIC_POEMS,
                playerNos = listOf(1, 2, 3, 4, 5),
                cpuNos = listOf(6, 7, 8, 9, 10),
                readOrder = (1..10).toList(),
                timing = ReadTiming(100L),
                profile = SLOW_CPU,
            )
            val session = engine.beginRead()
            engine.advanceToOffset(session, 1_000L)
            return engine.state.copy(config = GameConfig(emptyCardCount = 0))
        }
        assertEquals(stateAfterReading(VerseStyle.READING), stateAfterReading(VerseStyle.KANA))
    }
}
