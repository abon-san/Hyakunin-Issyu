package io.github.abonsan.hyakunin.ui.match

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 小さめのスマホ（360×640dp）の縦画面でも、文字が欠けたり重なったりしないかを撮って確かめる。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class SmallScreenScreenshotTest : MatchScenesScreenshotTest(prefix = "small", fieldsAside = false)
