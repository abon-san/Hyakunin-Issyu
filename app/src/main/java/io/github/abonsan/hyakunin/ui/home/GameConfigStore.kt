package io.github.abonsan.hyakunin.ui.home

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.abonsan.hyakunin.game.GameConfig

/** 対戦設定を SharedPreferences に保存・復元する。読めない値は既定値に戻す。 */
class GameConfigStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    fun load(): GameConfig {
        val default = GameConfig()
        return GameConfig(
            ruleMode = enumOrDefault(prefs.getString(KEY_RULE_MODE, null), default.ruleMode),
            emptyCardCount = prefs.getInt(KEY_EMPTY_CARDS, default.emptyCardCount)
                .coerceIn(0, GameConfig.MAX_EMPTY_CARDS),
            cpuLevel = enumOrDefault(prefs.getString(KEY_CPU_LEVEL, null), default.cpuLevel),
            readingSpeed = enumOrDefault(prefs.getString(KEY_READING_SPEED, null), default.readingSpeed),
            flipOpponentCards = prefs.getBoolean(KEY_FLIP_OPPONENT, default.flipOpponentCards),
        )
    }

    fun save(config: GameConfig) {
        prefs.edit {
            putString(KEY_RULE_MODE, config.ruleMode.name)
            putInt(KEY_EMPTY_CARDS, config.emptyCardCount)
            putString(KEY_CPU_LEVEL, config.cpuLevel.name)
            putString(KEY_READING_SPEED, config.readingSpeed.name)
            putBoolean(KEY_FLIP_OPPONENT, config.flipOpponentCards)
        }
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val PREFS_NAME = "game_config"
        const val KEY_RULE_MODE = "rule_mode"
        const val KEY_EMPTY_CARDS = "empty_card_count"
        const val KEY_CPU_LEVEL = "cpu_level"
        const val KEY_READING_SPEED = "reading_speed"
        const val KEY_FLIP_OPPONENT = "flip_opponent_cards"
    }
}
