package io.github.abonsan.hyakunin.ui.settings

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.VerseStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class AppSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("game_config", Context.MODE_PRIVATE)

    @Test
    fun orientationMapsToTheActivityOrientation() {
        // 自動は端末の自動回転の設定に従い、縦・横は逆さに持っても向きを合わせる
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER, ScreenOrientation.AUTO.activityOrientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT, ScreenOrientation.PORTRAIT.activityOrientation)
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE, ScreenOrientation.LANDSCAPE.activityOrientation)
        assertEquals("端末の自動回転の設定に従います", ScreenOrientation.AUTO.description)
    }

    @Test
    fun defaultsWhenNothingIsSaved() {
        // 初めて起動したときの句の表示は歴史的仮名遣い（対戦設定の既定と同じ）
        assertEquals(
            AppSettings(ScreenOrientation.AUTO, flipOpponentCards = true, verseStyle = VerseStyle.KANA, voiceEnabled = true),
            AppSettingsStore(context).load(),
        )
        assertEquals(GameConfig().verseStyle, AppSettings().verseStyle)
        // 読み上げ音声は初めからオン（対戦設定の既定と同じ）
        assertEquals(GameConfig().voiceEnabled, AppSettings().voiceEnabled)
    }

    @Test
    fun savedSettingsAreRestored() {
        for (orientation in ScreenOrientation.entries) {
            for (verseStyle in VerseStyle.entries) {
                val settings = AppSettings(
                    orientation = orientation,
                    flipOpponentCards = orientation != ScreenOrientation.AUTO,
                    verseStyle = verseStyle,
                    voiceEnabled = verseStyle == VerseStyle.KANA,
                )
                AppSettingsStore(context).save(settings)
                // 別のインスタンス（アプリの再起動に相当）でも読める
                assertEquals(settings, AppSettingsStore(context).load())
            }
        }
    }

    @Test
    fun keepsTheFlipSettingSavedByTheOlderVersion() {
        prefs.edit { putBoolean("flip_opponent_cards", false) }
        assertEquals(false, AppSettingsStore(prefs).load().flipOpponentCards)
    }

    @Test
    fun settingsSavedByTheOlderVersionGetHistoricalKana() {
        // 句の表示が無かった版で保存した設定は、そのまま引き継いで句の表示だけ既定の歴史的仮名遣いにする
        prefs.edit {
            putString("screen_orientation", ScreenOrientation.LANDSCAPE.name)
            putBoolean("flip_opponent_cards", false)
        }
        assertEquals(
            AppSettings(ScreenOrientation.LANDSCAPE, flipOpponentCards = false, verseStyle = VerseStyle.KANA),
            AppSettingsStore(prefs).load(),
        )
    }

    @Test
    fun theVoiceSettingIsSavedUnderItsOwnKey() {
        AppSettingsStore(prefs).save(AppSettings(voiceEnabled = false))
        assertEquals(false, prefs.getBoolean("voice_enabled", true))
        assertEquals(false, AppSettingsStore(prefs).load().voiceEnabled)
        AppSettingsStore(prefs).save(AppSettings(voiceEnabled = true))
        assertEquals(true, AppSettingsStore(prefs).load().voiceEnabled)
    }

    @Test
    fun settingsSavedBeforeTheVoiceSettingTurnTheVoiceOn() {
        // 読み上げ音声が無かった版で保存した設定は、そのまま引き継いで読み上げ音声をオンにする
        prefs.edit {
            putString("screen_orientation", ScreenOrientation.PORTRAIT.name)
            putBoolean("flip_opponent_cards", false)
            putString("verse_style", VerseStyle.READING.name)
        }
        assertEquals(
            AppSettings(ScreenOrientation.PORTRAIT, flipOpponentCards = false, verseStyle = VerseStyle.READING, voiceEnabled = true),
            AppSettingsStore(prefs).load(),
        )
    }

    @Test
    fun theVerseStyleIsSavedWithItsName() {
        AppSettingsStore(prefs).save(AppSettings(verseStyle = VerseStyle.READING))
        assertEquals("READING", prefs.getString("verse_style", null))
        // 他の設定と同じ保存先（対戦設定とも共用）に、別のキーで保存する
        assertTrue(prefs.contains("screen_orientation"))
        assertTrue(prefs.contains("flip_opponent_cards"))
    }

    @Test
    fun brokenValuesFallBackToDefaults() {
        prefs.edit {
            putString("screen_orientation", "SIDEWAYS")
            putString("flip_opponent_cards", "yes")
            putString("verse_style", "KATAKANA")
            putString("voice_enabled", "off")
        }
        assertEquals(AppSettings(), AppSettingsStore(prefs).load())
        // 型の違う値も読めない値として扱う
        prefs.edit { putInt("verse_style", 1) }
        assertEquals(VerseStyle.KANA, AppSettingsStore(prefs).load().verseStyle)
    }
}
