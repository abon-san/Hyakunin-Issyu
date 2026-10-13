package io.github.abonsan.hyakunin.ui.settings

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertRangeInfoEquals
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.voice.FAKE_JOKA_KAMI
import io.github.abonsan.hyakunin.game.voice.VoiceClip
import io.github.abonsan.hyakunin.ui.captureScreenshot
import io.github.abonsan.hyakunin.ui.theme.HyakuninTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 設定の画面の「音量」の行: スライダー（0〜200%、5% 刻み。100% が真ん中）と値、読み上げ機能向けの説明、「試聴」。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class VolumeSettingTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val previews = FakeVoicePreviewFactory()
    private var settings by mutableStateOf(AppSettings())

    /** 画面に出ている「試聴」の再生器。 */
    private val preview: FakeVoicePreview get() = previews.previews.single()

    private fun show(initial: AppSettings = AppSettings(), clip: VoiceClip? = FAKE_JOKA_KAMI) {
        settings = initial
        composeRule.setContent {
            CompositionLocalProvider(LocalVoicePreviewFactory provides previews) {
                HyakuninTheme {
                    SettingsScreen(settings = settings, onSettingsChange = { settings = it }, onBack = {}, previewClip = clip)
                }
            }
        }
    }

    private fun slider() = composeRule.onNodeWithContentDescription(VOLUME_LABEL)

    private fun previewButton() = composeRule.onNodeWithText(PREVIEW_LABEL)

    private fun stopButton() = composeRule.onNodeWithText(PREVIEW_STOP_LABEL)

    @Test
    fun theSliderShowsTheVolumeAndReadsItOutInPercent() {
        show(AppSettings(voiceVolume = 1.5f))
        // 読み上げ機能には「音量」と「150%」を渡す（0〜200% を 5% ずつ。割合の 75% ではなく音量の % を読む）
        slider()
            .performScrollTo()
            .assertIsEnabled()
            .assert(hasStateDescription("150%"))
            .assertRangeInfoEquals(ProgressBarRangeInfo(1.5f, 0f..2f, steps = 39))
        composeRule.onNodeWithText("150%").assertExists()
        // 見出しと値の文字は読み上げない（スライダーが名前と値を持つので、二重に読まない）
        val hidden = SemanticsMatcher.keyIsDefined(SemanticsProperties.HideFromAccessibility)
        composeRule.onNodeWithText(VOLUME_LABEL).assert(hidden)
        composeRule.onNodeWithText("150%").assert(hidden)
    }

    @Test
    fun theDefaultFullVolumeSitsAtTheCenterOfTheSlider() {
        // 既定の 100%（以前の版の最大、クリップそのままの大きさ）は、0〜200% のちょうど真ん中
        show()
        slider()
            .performScrollTo()
            .assert(hasStateDescription("100%"))
            .assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..2f, steps = 39))
        val range = slider().fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
        assertEquals(0.5f, (range.current - range.range.start) / (range.range.endInclusive - range.range.start))
        composeRule.onNodeWithText("100%").assertExists()
    }

    @Test
    fun movingTheSliderSavesTheVolumeInFivePercentStepsUpTo200Percent() {
        show()
        composeRule.onNodeWithText("100%").assertExists()
        // 読み上げ機能の操作（音量キーなど）でも刻みにそろう
        slider().performScrollTo().performSemanticsAction(SemanticsActions.SetProgress) { it(1.43f) }
        assertEquals(1.45f, settings.voiceVolume)
        composeRule.onNodeWithText("145%").assertExists()
        slider().assert(hasStateDescription("145%"))
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(0.43f) }
        assertEquals(0.45f, settings.voiceVolume)
        composeRule.onNodeWithText("45%").assertExists()
        // 最大は 200%（それより右へは行かない）
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(2.6f) }
        assertEquals(2f, settings.voiceVolume)
        composeRule.onNodeWithText("200%").assertExists()
        slider().assert(hasStateDescription("200%"))
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        assertEquals(0f, settings.voiceVolume)
        composeRule.onNodeWithText("0%").assertExists()
        // ほかの設定は変わらない
        assertEquals(AppSettings(voiceVolume = 0f), settings)
    }

    @Test
    fun theRowIsDisabledWhileTheVoiceIsOff() {
        show(AppSettings(voiceEnabled = false, voiceVolume = 0.4f))
        composeRule.onNodeWithText("読み上げ音声").assertIsOff()
        slider().performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("40%").assertExists()
        composeRule.onNodeWithText(VOLUME_LABEL).assertExists()
        previewButton().assertIsNotEnabled()
        composeRule.captureScreenshot("settings_voice_off")
        // オンにすると動かせる
        composeRule.onNodeWithText("読み上げ音声").performClick()
        slider().assertIsEnabled()
        previewButton().assertIsEnabled()
    }

    @Test
    fun thePreviewPlaysTheJokaAtTheChosenVolumeAndASecondPressStopsIt() {
        show(AppSettings(voiceVolume = 1.5f))
        previewButton().performScrollTo().assertIsEnabled().performClick()
        assertEquals("play ${FAKE_JOKA_KAMI.asset} 1.5", preview.commands.last { it.startsWith("play") })
        assertTrue(preview.playing)
        // 鳴っている間は「停止」になる
        stopButton().assertIsEnabled()
        previewButton().assertDoesNotExist()
        composeRule.captureScreenshot("settings_previewing")
        // もう一度押すと止まり、「試聴」に戻る
        stopButton().performClick()
        assertFalse(preview.playing)
        previewButton().assertExists()
        stopButton().assertDoesNotExist()
    }

    @Test
    fun movingTheSliderWhilePlayingChangesTheVolumeLive() {
        show(AppSettings(voiceVolume = 0.7f))
        previewButton().performScrollTo().performClick()
        val played = preview.commands.size
        // 鳴っている最中に 200% まで上げてから 30% に下げる: 止めず・鳴らし直さずに、音量だけを変える
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(2f) }
        composeRule.waitForIdle()
        assertEquals(2f, preview.volume)
        slider().performSemanticsAction(SemanticsActions.SetProgress) { it(0.3f) }
        composeRule.waitForIdle()
        assertEquals(0.3f, preview.volume)
        assertEquals(listOf("volume 2.0", "volume 0.3"), preview.commands.drop(played))
        assertTrue(preview.playing)
        stopButton().assertExists()
    }

    @Test
    fun theButtonGoesBackToPreviewWhenTheClipEnds() {
        show()
        previewButton().performScrollTo().performClick()
        stopButton().assertExists()
        // 鳴り終えた（音声フォーカスを失ったなども同じ）
        composeRule.runOnUiThread { preview.finish() }
        previewButton().assertIsEnabled()
        stopButton().assertDoesNotExist()
    }

    @Test
    fun turningTheVoiceOffStopsThePreview() {
        show()
        previewButton().performScrollTo().performClick()
        composeRule.onNodeWithText("読み上げ音声").performClick()
        composeRule.waitForIdle()
        assertFalse(preview.playing)
        previewButton().assertIsNotEnabled()
        slider().assertIsNotEnabled()
    }

    @Test
    fun withoutTheClipThePreviewCannotBePressedButTheVolumeCanBeSet() {
        // 音声を読み込み中・音声がないとき
        show(clip = null)
        slider().performScrollTo().assertIsEnabled()
        previewButton().assertIsNotEnabled()
    }

    @Test
    fun aRefusedAudioFocusLeavesTheButtonAsPreview() {
        show()
        preview.focusGranted = false
        previewButton().performScrollTo().performClick()
        assertFalse(preview.playing)
        previewButton().assertIsEnabled()
        stopButton().assertDoesNotExist()
    }

    @Test
    fun goingToTheBackgroundStopsThePreview() {
        show()
        previewButton().performScrollTo().performClick()
        assertTrue(preview.playing)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse(preview.playing)
        assertFalse(preview.released)
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        // 戻ってきても勝手には鳴らさない
        previewButton().assertIsEnabled()
        assertFalse(preview.playing)
    }

    @Test
    fun leavingTheScreenStopsAndReleasesThePreview() {
        var visible by mutableStateOf(true)
        composeRule.setContent {
            CompositionLocalProvider(LocalVoicePreviewFactory provides previews) {
                HyakuninTheme {
                    if (visible) {
                        SettingsScreen(settings = settings, onSettingsChange = { settings = it }, onBack = {}, previewClip = FAKE_JOKA_KAMI)
                    }
                }
            }
        }
        previewButton().performScrollTo().performClick()
        assertTrue(preview.playing)
        visible = false
        composeRule.waitForIdle()
        assertFalse(preview.playing)
        assertTrue(preview.released)
    }
}
