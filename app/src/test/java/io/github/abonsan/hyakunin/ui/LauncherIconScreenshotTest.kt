package io.github.abonsan.hyakunin.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.R
import io.github.abonsan.hyakunin.ui.theme.Kon
import io.github.abonsan.hyakunin.ui.theme.KonPale
import io.github.abonsan.hyakunin.ui.theme.Washi
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** アダプティブアイコンを、端末の形（丸・角丸）とテーマアイコン（単色）で並べて撮る。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class LauncherIconScreenshotTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun launcherIcon() {
        composeRule.setContent {
            Row(
                Modifier
                    .background(Washi)
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(CircleShape, monochrome = false)
                Icon(RoundedCornerShape(24.dp), monochrome = false)
                Icon(CircleShape, monochrome = true)
            }
        }
        composeRule.captureScreenshot("launcher_icon")
    }

    /** 108dp の層のうち、中央の 72dp を [shape] で切り抜いて見せる（ランチャーと同じ）。 */
    @Composable
    private fun Icon(shape: Shape, monochrome: Boolean) {
        Box(
            Modifier
                .size(72.dp)
                .clip(shape)
                .background(if (monochrome) KonPale else colorResource(R.color.ic_launcher_background)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(if (monochrome) R.mipmap.ic_launcher_monochrome else R.mipmap.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.requiredSize(108.dp),
                colorFilter = if (monochrome) ColorFilter.tint(Kon) else null,
            )
        }
    }
}
