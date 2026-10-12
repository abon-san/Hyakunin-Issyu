package io.github.abonsan.hyakunin.ui.home

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.BuildConfig
import io.github.abonsan.hyakunin.game.CpuLevel
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.MemorizeTime
import io.github.abonsan.hyakunin.game.ReadInterval
import io.github.abonsan.hyakunin.game.ReadStartMode
import io.github.abonsan.hyakunin.game.ReadingSpeed
import io.github.abonsan.hyakunin.game.ReadingStyle
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.game.voice.FAKE_JOKA_KAMI
import io.github.abonsan.hyakunin.ui.captureScreenshot
import io.github.abonsan.hyakunin.ui.settings.AppSettings
import io.github.abonsan.hyakunin.ui.settings.FakeVoicePreviewFactory
import io.github.abonsan.hyakunin.ui.settings.LocalVoicePreviewFactory
import io.github.abonsan.hyakunin.ui.settings.PREVIEW_LABEL
import io.github.abonsan.hyakunin.ui.settings.ScreenOrientation
import io.github.abonsan.hyakunin.ui.settings.SettingsScreen
import io.github.abonsan.hyakunin.ui.settings.VOICE_CREDIT
import io.github.abonsan.hyakunin.ui.settings.VOICE_CREDIT_NOTE
import io.github.abonsan.hyakunin.ui.settings.VOLUME_LABEL
import io.github.abonsan.hyakunin.ui.settings.settingsDescription
import io.github.abonsan.hyakunin.ui.theme.HyakuninTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * トップ・対戦設定・設定の画面を撮る。画面の大きさと向きはサブクラスの @Config で決める。
 * 画像の名前は「[prefix]_画面」（[prefix] が空なら画面の名前だけ）。
 */
abstract class MenuScreensScreenshotTest(private val prefix: String) {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun capture(screen: String) =
        composeRule.captureScreenshot(if (prefix.isEmpty()) screen else "${prefix}_$screen")

    /** 文字 [text] の欄の、画面での位置。 */
    private fun boundsOf(text: String): Rect = composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot

    @Test
    fun top() {
        composeRule.setContent {
            HyakuninTheme { TopScreen(onSelectMode = {}, onOpenSettings = {}) }
        }
        composeRule.onNodeWithText("競技モード").assertExists()
        composeRule.onNodeWithText(RuleMode.SIMPLE.description).assertExists()
        composeRule.onNodeWithText("設定").assertExists()
        // 読み上げ音声のクレジットを小さく添える
        composeRule.onNodeWithText(VOICE_CREDIT).assertExists()
        // 版（BuildConfig の versionName）は、クレジットの下の行に小さく添える。ほかの文字やボタンと重ならない
        assertEquals("Ver. ${BuildConfig.VERSION_NAME}", VERSION_LABEL)
        composeRule.onNodeWithContentDescription("バージョン ${BuildConfig.VERSION_NAME}").assertExists()
        val version = boundsOf(VERSION_LABEL)
        assertTrue("クレジットの下: $version / ${boundsOf(VOICE_CREDIT)}", version.top >= boundsOf(VOICE_CREDIT).bottom)
        for (other in listOf(VOICE_CREDIT, "設定", "簡易モード", RuleMode.SIMPLE.description, "百人一首")) {
            assertFalse("$other と重なる: $version / ${boundsOf(other)}", version.overlaps(boundsOf(other)))
        }
        capture("top")
        // 低い画面ではスクロールすれば、画面からはみ出さずに見える
        composeRule.onNodeWithText(VERSION_LABEL).performScrollTo().assertIsDisplayed()
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val scrolled = boundsOf(VERSION_LABEL)
        assertTrue("画面からはみ出す: $scrolled / $root", root.intersect(scrolled) == scrolled)
    }

    @Test
    fun matchSetup() {
        composeRule.setContent {
            HyakuninTheme {
                MatchSetupScreen(config = GameConfig(), onConfigChange = {}, onStart = {}, onBack = {})
            }
        }
        composeRule.onNodeWithText(MATCH_SETUP_CAPTION).assertExists()
        composeRule.onNodeWithText("対戦開始").assertExists()
        // 読み方。競技モードの既定は競技かるた式
        composeRule.onNodeWithText("読み方").assertExists()
        composeRule.onNodeWithText(ReadingStyle.COMPETITIVE.label).assertIsSelected()
        composeRule.onNodeWithText(ReadingStyle.COMPETITIVE.setupDescription).assertExists()
        capture("setup")
    }

    @Test
    fun matchSetupForSimpleModeWithTapToStart() {
        val config = GameConfig(
            ruleMode = RuleMode.SIMPLE,
            emptyCardCount = 90,
            cpuLevel = CpuLevel.MASTER,
            readingSpeed = ReadingSpeed.SLOW,
            readStartMode = ReadStartMode.TAP,
            memorizeTime = MemorizeTime.MIN_2,
            readInterval = ReadInterval.SEC_10,
            readingStyle = ReadingStyle.SIMPLE,
        )
        composeRule.setContent {
            HyakuninTheme {
                MatchSetupScreen(config = config, onConfigChange = {}, onStart = {}, onBack = {})
            }
        }
        composeRule.onNodeWithText("簡易モード").assertExists()
        composeRule.onNodeWithText(ReadingStyle.SIMPLE.setupDescription).assertExists()
        capture("setup_simple_tap")
    }

    @Test
    fun settings() {
        composeRule.setContent {
            CompositionLocalProvider(LocalVoicePreviewFactory provides FakeVoicePreviewFactory()) {
                HyakuninTheme {
                    SettingsScreen(
                        settings = AppSettings(orientation = ScreenOrientation.AUTO),
                        onSettingsChange = {},
                        onBack = {},
                        previewClip = FAKE_JOKA_KAMI,
                    )
                }
            }
        }
        composeRule.onNodeWithText(ScreenOrientation.AUTO.description).assertExists()
        // 句の表示の既定は歴史的仮名遣い。選んでいない方の説明は読み上げない
        composeRule.onNodeWithText("句の表示").assertExists()
        composeRule.onNodeWithText(VerseStyle.KANA.settingsDescription).assertExists()
        composeRule.onNodeWithText(VerseStyle.READING.settingsDescription).assertDoesNotExist()
        // 読み上げ音声（既定はオン）と、その下の音量（既定は 100%）と「試聴」、クレジット
        composeRule.onNodeWithText("読み上げ音声").assertIsOn()
        composeRule.onNodeWithContentDescription(VOLUME_LABEL).assertIsEnabled()
        composeRule.onNodeWithText("100%").assertExists()
        composeRule.onNodeWithText(PREVIEW_LABEL).assertIsEnabled()
        assertTrue("音量は読み上げ音声のすぐ下", boundsOf(VOLUME_LABEL).top > boundsOf("読み上げ音声").bottom)
        composeRule.onNodeWithText(VOICE_CREDIT).assertExists()
        composeRule.onNodeWithText(VOICE_CREDIT_NOTE).assertExists()
        capture("settings")
    }
}

/** 一般的な大きさのスマホの横画面。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w891dp-h411dp-land-xxhdpi")
class LandscapeMenuScreenshotTest : MenuScreensScreenshotTest(prefix = "land")

/** 小さめのスマホの横画面。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w640dp-h360dp-land-xhdpi")
class SmallLandscapeMenuScreenshotTest : MenuScreensScreenshotTest(prefix = "land_small")

/** 小さめのスマホの縦画面。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class SmallMenuScreenshotTest : MenuScreensScreenshotTest(prefix = "small")

/** スマホの横画面を左右に分けた分割画面の片側。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w445dp-h411dp-land-xxhdpi")
class SplitLandscapeMenuScreenshotTest : MenuScreensScreenshotTest(prefix = "split_land")

/** スマホの縦画面を上下に分けた分割画面の片側。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h440dp-port-xxhdpi")
class SplitPortraitMenuScreenshotTest : MenuScreensScreenshotTest(prefix = "split_port")

/** タブレットの横画面。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w1280dp-h800dp-land-mdpi")
class TabletLandscapeMenuScreenshotTest : MenuScreensScreenshotTest(prefix = "tablet")
