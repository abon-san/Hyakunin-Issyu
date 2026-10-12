package io.github.abonsan.hyakunin.ui.home

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.ReadingStyle
import io.github.abonsan.hyakunin.game.RuleMode

/** 設定を保存する SharedPreferences（対戦設定と画面の設定で共用する）。 */
internal fun settingsPreferences(context: Context): SharedPreferences =
    context.getSharedPreferences(SETTINGS_PREFS_NAME, Context.MODE_PRIVATE)

private const val SETTINGS_PREFS_NAME = "game_config"

/**
 * 対戦設定をルールのモードごとに SharedPreferences に保存・復元する。読めない値は既定値に戻す。
 * そのモードでまだ保存していない項目は、モード共通だった以前の保存値（旧キー）を引き継ぐ。
 * 読み方（[GameConfig.readingStyle]）の既定はモードで違う（[defaultReadingStyle]）。
 * [GameConfig.flipOpponentCards]・[GameConfig.verseStyle]・[GameConfig.voiceEnabled] は画面の設定（AppSettingsStore）で
 * 持つので、ここでは扱わない。
 */
class GameConfigStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(settingsPreferences(context))

    /** [mode] の対戦設定。画面の設定で持つ項目（札の向き・句の表示・読み上げ音声）は既定値のまま返す。 */
    fun load(mode: RuleMode): GameConfig {
        val default = GameConfig(ruleMode = mode, readingStyle = mode.defaultReadingStyle)
        val keys = ModeKeys(mode)
        return GameConfig(
            ruleMode = mode,
            emptyCardCount = (int(keys.emptyCards) ?: int(LEGACY_EMPTY_CARDS) ?: default.emptyCardCount)
                .coerceIn(0, GameConfig.MAX_EMPTY_CARDS),
            cpuLevel = enum(keys.cpuLevel, LEGACY_CPU_LEVEL) ?: default.cpuLevel,
            readingSpeed = enum(keys.readingSpeed, LEGACY_READING_SPEED) ?: default.readingSpeed,
            readStartMode = enum(keys.readStartMode) ?: default.readStartMode,
            memorizeTime = enum(keys.memorizeTime) ?: default.memorizeTime,
            readInterval = enum(keys.readInterval) ?: default.readInterval,
            readingStyle = enum(keys.readingStyle) ?: default.readingStyle,
        )
    }

    /** [GameConfig.ruleMode] のモードの設定として保存する。 */
    fun save(config: GameConfig) {
        val keys = ModeKeys(config.ruleMode)
        prefs.edit {
            putInt(keys.emptyCards, config.emptyCardCount)
            putString(keys.cpuLevel, config.cpuLevel.name)
            putString(keys.readingSpeed, config.readingSpeed.name)
            putString(keys.readStartMode, config.readStartMode.name)
            putString(keys.memorizeTime, config.memorizeTime.name)
            putString(keys.readInterval, config.readInterval.name)
            putString(keys.readingStyle, config.readingStyle.name)
        }
    }

    private fun int(key: String): Int? =
        if (prefs.contains(key)) runCatching { prefs.getInt(key, 0) }.getOrNull() else null

    /** [keys] を順に見て、最初に読めた値。 */
    private inline fun <reified T : Enum<T>> enum(vararg keys: String): T? = keys.firstNotNullOfOrNull { key ->
        val name = runCatching { prefs.getString(key, null) }.getOrNull()
        enumValues<T>().firstOrNull { it.name == name }
    }

    /** モードごとの保存キー（例: competitive.cpu_level）。 */
    private class ModeKeys(mode: RuleMode) {
        private val prefix = mode.name.lowercase() + "."
        val emptyCards = prefix + "empty_card_count"
        val cpuLevel = prefix + "cpu_level"
        val readingSpeed = prefix + "reading_speed"
        val readStartMode = prefix + "read_start_mode"
        val memorizeTime = prefix + "memorize_time"
        val readInterval = prefix + "read_interval"
        val readingStyle = prefix + "reading_style"
    }

    private companion object {
        // モード共通で保存していた頃のキー
        const val LEGACY_EMPTY_CARDS = "empty_card_count"
        const val LEGACY_CPU_LEVEL = "cpu_level"
        const val LEGACY_READING_SPEED = "reading_speed"
    }
}

/** モードごとの読み方の既定。競技モードは競技かるた式（序歌と前の歌の下の句を読む）、簡易モードは簡易式。 */
internal val RuleMode.defaultReadingStyle: ReadingStyle
    get() = when (this) {
        RuleMode.COMPETITIVE -> ReadingStyle.COMPETITIVE
        RuleMode.SIMPLE -> ReadingStyle.SIMPLE
    }
