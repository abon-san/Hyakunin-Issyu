package io.github.abonsan.hyakunin.ui.settings

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.VerseStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
        // 音量は初めは 100%（対戦設定の既定と同じ）
        assertEquals(1f, AppSettingsStore(context).load().voiceVolume)
        assertEquals(GameConfig().voiceVolume, AppSettings().voiceVolume)
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
                    voiceVolume = 0.05f * (orientation.ordinal * 2 + verseStyle.ordinal + 1),
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
    fun theVolumeIsSavedUnderItsOwnKeyAndRestoredExactly() {
        // スライダーの刻み（5%）のどの値も、そのまま戻る（別のインスタンスでも）
        for (step in 0..20) {
            val volume = step / 20f
            AppSettingsStore(prefs).save(AppSettings(voiceVolume = volume))
            assertEquals(volume, prefs.getFloat("voice_volume", -1f))
            assertEquals(volume, AppSettingsStore(context).load().voiceVolume)
        }
        // 音量を変えても、ほかの設定は変わらない
        val settings = AppSettings(ScreenOrientation.PORTRAIT, flipOpponentCards = false, voiceEnabled = false, voiceVolume = 0.35f)
        AppSettingsStore(prefs).save(settings)
        assertEquals(settings, AppSettingsStore(prefs).load())
    }

    @Test
    fun settingsSavedBeforeTheVolumeGetFullVolume() {
        // 音量が無かった版で保存した設定は、そのまま引き継いで音量だけ 100% にする
        prefs.edit {
            putString("screen_orientation", ScreenOrientation.LANDSCAPE.name)
            putBoolean("voice_enabled", false)
        }
        assertEquals(
            AppSettings(ScreenOrientation.LANDSCAPE, voiceEnabled = false, voiceVolume = 1f),
            AppSettingsStore(prefs).load(),
        )
    }

    @Test
    fun anOutOfRangeVolumeIsClamped() {
        for ((saved, loaded) in listOf(1.7f to 1f, 100f to 1f, -0.3f to 0f, Float.MIN_VALUE to Float.MIN_VALUE, 0f to 0f)) {
            prefs.edit { putFloat("voice_volume", saved) }
            assertEquals("saved=$saved", loaded, AppSettingsStore(prefs).load().voiceVolume)
        }
    }

    @Test
    fun anUnreadableVolumeFallsBackToFullVolume() {
        // 数でない値・無限大・型の違う値は読めない値として既定（100%）に戻す。ほかの設定はそのまま読む
        prefs.edit { putBoolean("voice_enabled", false) }
        for (broken in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            prefs.edit { putFloat("voice_volume", broken) }
            assertEquals("saved=$broken", AppSettings(voiceEnabled = false), AppSettingsStore(prefs).load())
        }
        prefs.edit { putString("voice_volume", "0.5") }
        assertEquals(1f, AppSettingsStore(prefs).load().voiceVolume)
        prefs.edit { putInt("voice_volume", 50) }
        assertEquals(1f, AppSettingsStore(prefs).load().voiceVolume)
    }

    @Test
    fun aVolumeOutsideTheRangeIsRejected() {
        // 画面と保存の値はいつも 0〜1（読み込みで収める）。範囲の外の値は作れない
        assertThrows(IllegalArgumentException::class.java) { AppSettings(voiceVolume = 1.01f) }
        assertThrows(IllegalArgumentException::class.java) { AppSettings(voiceVolume = -0.01f) }
        assertThrows(IllegalArgumentException::class.java) { AppSettings(voiceVolume = Float.NaN) }
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
            putString("voice_volume", "loud")
        }
        assertEquals(AppSettings(), AppSettingsStore(prefs).load())
        // 型の違う値も読めない値として扱う
        prefs.edit { putInt("verse_style", 1) }
        assertEquals(VerseStyle.KANA, AppSettingsStore(prefs).load().verseStyle)
    }
}
