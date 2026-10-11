package io.github.abonsan.hyakunin

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.abonsan.hyakunin.ui.HyakuninApp
import io.github.abonsan.hyakunin.ui.home.GameConfigStore
import io.github.abonsan.hyakunin.ui.settings.AppSettingsStore
import io.github.abonsan.hyakunin.ui.settings.ScreenOrientation
import io.github.abonsan.hyakunin.ui.theme.HyakuninTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 和紙の明るい地に合わせ、端末のダークテーマでもシステムバーのアイコンは濃い色にする
        val barStyle = SystemBarStyle.light(Color.TRANSPARENT, DARK_SCRIM)
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        val configStore = GameConfigStore(applicationContext)
        val settingsStore = AppSettingsStore(applicationContext)
        // 中身を描く前に、設定した画面の向きを指定する
        applyOrientation(settingsStore.load().orientation)
        setContent {
            HyakuninTheme {
                HyakuninApp(
                    configStore = configStore,
                    settingsStore = settingsStore,
                    onOrientationChange = ::applyOrientation,
                )
            }
        }
    }

    /**
     * 画面の向きを指定する。Android 16 以降の大きな画面では端末側が指定を無視することがあるので、
     * どの向きで表示されても崩れないように画面を作ってある。
     */
    private fun applyOrientation(orientation: ScreenOrientation) {
        if (requestedOrientation != orientation.activityOrientation) {
            requestedOrientation = orientation.activityOrientation
        }
    }

    private companion object {
        /** 濃い色のアイコンを出せない古い端末で、システムバーの下に敷く色。 */
        val DARK_SCRIM = Color.argb(0x80, 0x1B, 0x1B, 0x1B)
    }
}
