package io.github.abonsan.hyakunin.ui.settings

import android.content.Context
import android.content.pm.ActivityInfo
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
        assertEquals(AppSettings(ScreenOrientation.AUTO, flipOpponentCards = true), AppSettingsStore(context).load())
    }

    @Test
    fun savedSettingsAreRestored() {
        for (orientation in ScreenOrientation.entries) {
            val settings = AppSettings(orientation = orientation, flipOpponentCards = orientation != ScreenOrientation.AUTO)
            AppSettingsStore(context).save(settings)
            // 別のインスタンス（アプリの再起動に相当）でも読める
            assertEquals(settings, AppSettingsStore(context).load())
        }
    }

    @Test
    fun keepsTheFlipSettingSavedByTheOlderVersion() {
        prefs.edit { putBoolean("flip_opponent_cards", false) }
        assertEquals(false, AppSettingsStore(prefs).load().flipOpponentCards)
    }

    @Test
    fun brokenValuesFallBackToDefaults() {
        prefs.edit {
            putString("screen_orientation", "SIDEWAYS")
            putString("flip_opponent_cards", "yes")
        }
        assertEquals(AppSettings(), AppSettingsStore(prefs).load())
    }
}
