package io.github.abonsan.hyakunin.ui.quiz

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import io.github.abonsan.hyakunin.game.quiz.QuizConfig
import io.github.abonsan.hyakunin.ui.home.settingsPreferences

/**
 * 「札を覚える」の設定（出題範囲とどこまで読むか）と、間違えた札の記録を SharedPreferences に保存・復元する
 * （対戦設定と同じファイル）。
 * 読めない値は既定値（記録なら空）にする。
 */
class QuizStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(settingsPreferences(context))

    fun loadConfig(): QuizConfig {
        val default = QuizConfig()
        return QuizConfig(
            numberRange = enum(KEY_NUMBER_RANGE) ?: default.numberRange,
            kimarijiGroup = enum(KEY_KIMARIJI) ?: default.kimarijiGroup,
            missedOnly = runCatching { prefs.getBoolean(KEY_MISSED_ONLY, default.missedOnly) }
                .getOrDefault(default.missedOnly),
            readRange = enum(KEY_READ_RANGE) ?: default.readRange,
        )
    }

    fun saveConfig(config: QuizConfig) {
        prefs.edit {
            putString(KEY_NUMBER_RANGE, config.numberRange.name)
            putString(KEY_KIMARIJI, config.kimarijiGroup.name)
            putBoolean(KEY_MISSED_ONLY, config.missedOnly)
            putString(KEY_READ_RANGE, config.readRange.name)
        }
    }

    /** これまでに間違えた（その後まだ正解していない）札の歌番号。 */
    fun missed(): Set<Int> {
        val saved = runCatching { prefs.getString(KEY_MISSED, null) }.getOrNull() ?: return emptySet()
        return saved.split(',').mapNotNull { it.toIntOrNull()?.takeIf { no -> no in 1..100 } }.toSet()
    }

    /** 歌番号 [poemNo] の札に答えた。間違えたら記録に足し、正解したら記録から外す。 */
    fun recordAnswer(poemNo: Int, correct: Boolean) {
        val missed = missed()
        val updated = if (correct) missed - poemNo else missed + poemNo
        if (updated == missed) return
        prefs.edit { putString(KEY_MISSED, updated.sorted().joinToString(",")) }
    }

    private inline fun <reified T : Enum<T>> enum(key: String): T? {
        val name = runCatching { prefs.getString(key, null) }.getOrNull()
        return enumValues<T>().firstOrNull { it.name == name }
    }

    private companion object {
        const val KEY_NUMBER_RANGE = "quiz.number_range"
        const val KEY_KIMARIJI = "quiz.kimariji"
        const val KEY_MISSED_ONLY = "quiz.missed_only"
        const val KEY_READ_RANGE = "quiz.read_range"

        /** 間違えた札の歌番号（カンマ区切り）。 */
        const val KEY_MISSED = "quiz.missed"
    }
}
