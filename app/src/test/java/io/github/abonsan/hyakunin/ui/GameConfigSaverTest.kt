package io.github.abonsan.hyakunin.ui

import androidx.compose.runtime.saveable.SaverScope
import io.github.abonsan.hyakunin.game.CpuLevel
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.MemorizeTime
import io.github.abonsan.hyakunin.game.ReadInterval
import io.github.abonsan.hyakunin.game.ReadStartMode
import io.github.abonsan.hyakunin.game.ReadingSpeed
import io.github.abonsan.hyakunin.game.ReadingStyle
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.game.VerseStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/** 対戦中の設定を画面の状態として保存し、プロセスの再生成の後に同じ設定へ戻せること。 */
class GameConfigSaverTest {
    private val scope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    @Test
    fun everySettingSurvivesSavingAndRestoring() {
        val configs = listOf(
            GameConfig(),
            GameConfig(
                ruleMode = RuleMode.SIMPLE,
                emptyCardCount = 42,
                cpuLevel = CpuLevel.MASTER,
                readingSpeed = ReadingSpeed.FAST,
                flipOpponentCards = false,
                verseStyle = VerseStyle.READING,
                readStartMode = ReadStartMode.TAP,
                memorizeTime = MemorizeTime.MIN_2,
                readInterval = ReadInterval.SEC_10,
                readingStyle = ReadingStyle.SIMPLE,
                voiceEnabled = false,
            ),
            GameConfig(readingStyle = ReadingStyle.SIMPLE),
            GameConfig(ruleMode = RuleMode.SIMPLE, readingStyle = ReadingStyle.COMPETITIVE, voiceEnabled = false),
        )
        for (config in configs) {
            val saved = with(GameConfigSaver) { scope.save(config) }!!
            assertEquals(config, GameConfigSaver.restore(saved))
        }
    }

    @Test
    fun stateSavedBeforeTheVoiceSettingsGetsTheDefaults() {
        // 読み方と読み上げ音声を足す前の版で保存した状態（9 項目）
        val old = listOf("SIMPLE", 30, "MASTER", "FAST", false, "READING", "TAP", "MIN_1", "SEC_6")
        assertEquals(
            GameConfig(
                ruleMode = RuleMode.SIMPLE,
                emptyCardCount = 30,
                cpuLevel = CpuLevel.MASTER,
                readingSpeed = ReadingSpeed.FAST,
                flipOpponentCards = false,
                verseStyle = VerseStyle.READING,
                readStartMode = ReadStartMode.TAP,
                memorizeTime = MemorizeTime.MIN_1,
                readInterval = ReadInterval.SEC_6,
                // 簡易モードの読み方の既定
                readingStyle = ReadingStyle.SIMPLE,
                voiceEnabled = true,
            ),
            GameConfigSaver.restore(old),
        )
        assertEquals(ReadingStyle.COMPETITIVE, GameConfigSaver.restore(listOf("COMPETITIVE") + old.drop(1))!!.readingStyle)
    }
}
