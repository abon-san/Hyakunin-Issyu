package io.github.abonsan.hyakunin.ui.home

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.CpuLevel
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.ReadingSpeed
import io.github.abonsan.hyakunin.game.RuleMode
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class GameConfigStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun defaultsWhenNothingIsSaved() {
        assertEquals(GameConfig(), GameConfigStore(context).load())
    }

    @Test
    fun savedSettingsAreRestored() {
        val config = GameConfig(
            ruleMode = RuleMode.SIMPLE,
            emptyCardCount = 90,
            cpuLevel = CpuLevel.MASTER,
            readingSpeed = ReadingSpeed.SLOW,
            flipOpponentCards = false,
        )
        GameConfigStore(context).save(config)
        // 別のインスタンス（アプリの再起動に相当）でも読める
        assertEquals(config, GameConfigStore(context).load())
    }

    @Test
    fun brokenValuesFallBackToDefaults() {
        val prefs = context.getSharedPreferences("game_config", Context.MODE_PRIVATE)
        prefs.edit {
            putString("rule_mode", "UNKNOWN")
            putInt("empty_card_count", 500)
            putString("cpu_level", null)
            putString("reading_speed", "FAST")
        }
        val config = GameConfigStore(prefs).load()
        assertEquals(GameConfig().ruleMode, config.ruleMode)
        assertEquals(GameConfig.MAX_EMPTY_CARDS, config.emptyCardCount)
        assertEquals(GameConfig().cpuLevel, config.cpuLevel)
        assertEquals(ReadingSpeed.FAST, config.readingSpeed)
    }
}
