package io.github.abonsan.hyakunin.ui.home

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.CpuLevel
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.MemorizeTime
import io.github.abonsan.hyakunin.game.ReadInterval
import io.github.abonsan.hyakunin.game.ReadStartMode
import io.github.abonsan.hyakunin.game.ReadingSpeed
import io.github.abonsan.hyakunin.game.ReadingStyle
import io.github.abonsan.hyakunin.game.RuleMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class GameConfigStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("game_config", Context.MODE_PRIVATE)

    @Test
    fun defaultsWhenNothingIsSaved() {
        val store = GameConfigStore(context)
        // 読み方の既定は、競技モードは競技かるた式、簡易モードは簡易式
        assertEquals(
            GameConfig(ruleMode = RuleMode.COMPETITIVE, readingStyle = ReadingStyle.COMPETITIVE),
            store.load(RuleMode.COMPETITIVE),
        )
        assertEquals(GameConfig(ruleMode = RuleMode.SIMPLE, readingStyle = ReadingStyle.SIMPLE), store.load(RuleMode.SIMPLE))
    }

    @Test
    fun eachModeRemembersItsOwnReadingStyle() {
        val store = GameConfigStore(prefs)
        store.save(store.load(RuleMode.COMPETITIVE).copy(readingStyle = ReadingStyle.SIMPLE))
        assertEquals(ReadingStyle.SIMPLE, GameConfigStore(prefs).load(RuleMode.COMPETITIVE).readingStyle)
        assertEquals(ReadingStyle.SIMPLE, GameConfigStore(prefs).load(RuleMode.SIMPLE).readingStyle)
        store.save(store.load(RuleMode.SIMPLE).copy(readingStyle = ReadingStyle.COMPETITIVE))
        assertEquals(ReadingStyle.COMPETITIVE, GameConfigStore(prefs).load(RuleMode.SIMPLE).readingStyle)
        assertEquals(ReadingStyle.SIMPLE, GameConfigStore(prefs).load(RuleMode.COMPETITIVE).readingStyle)
        assertEquals("COMPETITIVE", prefs.getString("simple.reading_style", null))
        assertEquals("SIMPLE", prefs.getString("competitive.reading_style", null))
    }

    @Test
    fun theVoiceSettingIsNotPartOfTheModeSettings() {
        GameConfigStore(context).save(GameConfig(voiceEnabled = false))
        assertEquals(true, GameConfigStore(context).load(RuleMode.COMPETITIVE).voiceEnabled)
        assertFalse(prefs.contains("competitive.voice_enabled"))
    }

    @Test
    fun eachModeRemembersItsOwnSettings() {
        val competitive = GameConfig(
            ruleMode = RuleMode.COMPETITIVE,
            emptyCardCount = 90,
            cpuLevel = CpuLevel.MASTER,
            readingSpeed = ReadingSpeed.SLOW,
            readStartMode = ReadStartMode.TAP,
            memorizeTime = MemorizeTime.MIN_2,
            readInterval = ReadInterval.SEC_10,
            readingStyle = ReadingStyle.SIMPLE,
        )
        val simple = GameConfig(
            ruleMode = RuleMode.SIMPLE,
            emptyCardCount = 0,
            cpuLevel = CpuLevel.BEGINNER,
            readingSpeed = ReadingSpeed.FAST,
            memorizeTime = MemorizeTime.SEC_10,
            readInterval = ReadInterval.SEC_2,
            readingStyle = ReadingStyle.COMPETITIVE,
        )
        GameConfigStore(context).save(competitive)
        GameConfigStore(context).save(simple)
        // 別のインスタンス（アプリの再起動に相当）でも、モードごとに読める
        val store = GameConfigStore(context)
        assertEquals(competitive, store.load(RuleMode.COMPETITIVE))
        assertEquals(simple, store.load(RuleMode.SIMPLE))
    }

    @Test
    fun flipSettingIsNotPartOfTheModeSettings() {
        GameConfigStore(context).save(GameConfig(flipOpponentCards = false))
        assertEquals(GameConfig().flipOpponentCards, GameConfigStore(context).load(RuleMode.COMPETITIVE).flipOpponentCards)
    }

    @Test
    fun legacySharedSettingsBecomeTheStartingPointOfBothModes() {
        // モード共通で保存していた頃の値
        prefs.edit {
            putString("rule_mode", "SIMPLE")
            putInt("empty_card_count", 42)
            putString("cpu_level", "ADVANCED")
            putString("reading_speed", "SLOW")
            putBoolean("flip_opponent_cards", false)
        }
        val store = GameConfigStore(prefs)
        for (mode in RuleMode.entries) {
            val config = store.load(mode)
            assertEquals(mode, config.ruleMode)
            assertEquals(42, config.emptyCardCount)
            assertEquals(CpuLevel.ADVANCED, config.cpuLevel)
            assertEquals(ReadingSpeed.SLOW, config.readingSpeed)
            // 旧版に無かった項目は既定値
            assertEquals(GameConfig().readStartMode, config.readStartMode)
            assertEquals(GameConfig().memorizeTime, config.memorizeTime)
            assertEquals(GameConfig().readInterval, config.readInterval)
            assertEquals(mode.defaultReadingStyle, config.readingStyle)
        }

        // 片方のモードで変えて保存しても、もう片方は旧版の値のまま
        store.save(store.load(RuleMode.COMPETITIVE).copy(cpuLevel = CpuLevel.BEGINNER, emptyCardCount = 3))
        assertEquals(CpuLevel.BEGINNER, store.load(RuleMode.COMPETITIVE).cpuLevel)
        assertEquals(3, store.load(RuleMode.COMPETITIVE).emptyCardCount)
        assertEquals(CpuLevel.ADVANCED, store.load(RuleMode.SIMPLE).cpuLevel)
        assertEquals(42, store.load(RuleMode.SIMPLE).emptyCardCount)
    }

    @Test
    fun brokenValuesFallBackToDefaults() {
        prefs.edit {
            putString("competitive.cpu_level", "UNKNOWN")
            putInt("competitive.empty_card_count", 500)
            putString("competitive.reading_speed", "FAST")
            putInt("competitive.read_start_mode", 3)
            putString("competitive.memorize_time", null)
            putString("simple.read_interval", "SEC_99")
            putString("empty_card_count", "many")
            putString("competitive.reading_style", "FAST")
            putInt("simple.reading_style", 2)
        }
        val competitive = GameConfigStore(prefs).load(RuleMode.COMPETITIVE)
        assertEquals(GameConfig().cpuLevel, competitive.cpuLevel)
        assertEquals(GameConfig.MAX_EMPTY_CARDS, competitive.emptyCardCount)
        assertEquals(ReadingSpeed.FAST, competitive.readingSpeed)
        assertEquals(GameConfig().readStartMode, competitive.readStartMode)
        assertEquals(GameConfig().memorizeTime, competitive.memorizeTime)
        val simple = GameConfigStore(prefs).load(RuleMode.SIMPLE)
        assertEquals(GameConfig().readInterval, simple.readInterval)
        // 旧キーに型の違う値があっても既定値にする
        assertEquals(GameConfig().emptyCardCount, simple.emptyCardCount)
        // 読めない読み方はモードの既定
        assertEquals(ReadingStyle.COMPETITIVE, competitive.readingStyle)
        assertEquals(ReadingStyle.SIMPLE, simple.readingStyle)
    }
}
