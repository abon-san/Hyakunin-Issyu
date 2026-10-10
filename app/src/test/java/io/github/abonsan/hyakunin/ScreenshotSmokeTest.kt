package io.github.abonsan.hyakunin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class ScreenshotSmokeTest {
    @Test
    fun rendersJapanese() {
        captureRoboImage("build/outputs/roborazzi/smoke.png") {
            Column(Modifier.background(Color(0xFFF3EAD3)).padding(16.dp)) {
                Text("システム: 秋の田の　かりほの庵の", fontSize = 20.sp)
                Text(
                    "明朝: あきのたの　かりおのいおの",
                    fontSize = 20.sp,
                    fontFamily = FontFamily(Font(R.font.shippori_mincho_regular)),
                )
            }
        }
    }
}
