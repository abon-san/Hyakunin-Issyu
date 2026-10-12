package io.github.abonsan.hyakunin.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import androidx.core.content.edit
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.ui.home.settingsPreferences

/**
 * 画面の向き。[activityOrientation] は Activity の requestedOrientation に指定する値。
 * 縦・横を選んだときも、端末を逆さに持てば上下を合わせる（USER_PORTRAIT / USER_LANDSCAPE）。
 */
enum class ScreenOrientation(val label: String, val description: String, val activityOrientation: Int) {
    AUTO("自動", "端末の自動回転の設定に従います", ActivityInfo.SCREEN_ORIENTATION_USER),
    PORTRAIT("縦", "いつも縦向きで表示します", ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT),
    LANDSCAPE("横", "いつも横向きで表示します", ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE),
}

/** トップの「設定」で変える、アプリ全体の設定。 */
data class AppSettings(
    val orientation: ScreenOrientation = ScreenOrientation.AUTO,
    /** 敵陣の札を相手向き（上下逆さ）に表示するか。対戦開始時に [GameConfig.flipOpponentCards] へ渡す。 */
    val flipOpponentCards: Boolean = true,
    /** 読み上げ中と結果の句の仮名遣い。対戦開始時に [GameConfig.verseStyle] へ渡す。 */
    val verseStyle: VerseStyle = VerseStyle.KANA,
    /** 読み上げ音声を鳴らすか。対戦開始時に [GameConfig.voiceEnabled] へ渡す。 */
    val voiceEnabled: Boolean = true,
    /** 読み上げ音声の音量（0〜1）。対戦開始時に [GameConfig.voiceVolume] へ渡す。設定の画面の「試聴」もこの音量で鳴らす。 */
    val voiceVolume: Float = 1f,
) {
    init {
        require(voiceVolume in 0f..1f) { "voiceVolume=$voiceVolume" }
    }
}

/**
 * [AppSettings] を SharedPreferences に保存・復元する。読めない値は既定値に戻す。
 * まだ保存していない項目（以前の版から更新した場合の句の表示・読み上げ音声・音量など）も既定値にする。
 * 音量は、範囲の外の値なら 0〜1 に収める（数でない値・無限大は読めない値として既定値にする）。
 */
class AppSettingsStore(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(settingsPreferences(context))

    fun load(): AppSettings {
        val default = AppSettings()
        val orientation = runCatching { prefs.getString(KEY_ORIENTATION, null) }.getOrNull()
        val verseStyle = runCatching { prefs.getString(KEY_VERSE_STYLE, null) }.getOrNull()
        return AppSettings(
            orientation = ScreenOrientation.entries.firstOrNull { it.name == orientation } ?: default.orientation,
            flipOpponentCards = runCatching { prefs.getBoolean(KEY_FLIP_OPPONENT, default.flipOpponentCards) }
                .getOrDefault(default.flipOpponentCards),
            verseStyle = VerseStyle.entries.firstOrNull { it.name == verseStyle } ?: default.verseStyle,
            voiceEnabled = runCatching { prefs.getBoolean(KEY_VOICE_ENABLED, default.voiceEnabled) }
                .getOrDefault(default.voiceEnabled),
            voiceVolume = runCatching { prefs.getFloat(KEY_VOICE_VOLUME, default.voiceVolume) }
                .getOrNull()
                ?.takeIf { it.isFinite() }
                ?.coerceIn(0f, 1f)
                ?: default.voiceVolume,
        )
    }

    fun save(settings: AppSettings) {
        prefs.edit {
            putString(KEY_ORIENTATION, settings.orientation.name)
            putBoolean(KEY_FLIP_OPPONENT, settings.flipOpponentCards)
            putString(KEY_VERSE_STYLE, settings.verseStyle.name)
            putBoolean(KEY_VOICE_ENABLED, settings.voiceEnabled)
            putFloat(KEY_VOICE_VOLUME, settings.voiceVolume)
        }
    }

    private companion object {
        const val KEY_ORIENTATION = "screen_orientation"

        /** 対戦設定と一緒に保存していた頃と同じキー（以前の値をそのまま引き継ぐ）。 */
        const val KEY_FLIP_OPPONENT = "flip_opponent_cards"

        const val KEY_VERSE_STYLE = "verse_style"

        const val KEY_VOICE_ENABLED = "voice_enabled"

        const val KEY_VOICE_VOLUME = "voice_volume"
    }
}
