package io.github.abonsan.hyakunin.ui

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage

/** スクリーンショットの出力先（app モジュールからの相対パス）。 */
internal const val SCREENSHOT_DIR = "build/outputs/roborazzi"

/** 画面の中身（Compose のルート）を撮る。 */
internal fun ComposeTestRule.captureScreenshot(name: String) {
    waitForIdle()
    onRoot().captureRoboImage("$SCREENSHOT_DIR/$name.png")
}

/** ダイアログも含めた画面全体を撮る。 */
@OptIn(ExperimentalRoborazziApi::class)
internal fun ComposeTestRule.captureWholeScreen(name: String) {
    waitForIdle()
    captureScreenRoboImage("$SCREENSHOT_DIR/$name.png")
}
