package io.github.abonsan.hyakunin.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.data.Poems
import io.github.abonsan.hyakunin.game.CpuLevel
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.LeadInKind
import io.github.abonsan.hyakunin.game.MemorizeTime
import io.github.abonsan.hyakunin.game.ReadStartMode
import io.github.abonsan.hyakunin.game.ReadingStyle
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.game.voice.VoiceBankSource
import io.github.abonsan.hyakunin.game.voice.FAKE_JOKA_KAMI
import io.github.abonsan.hyakunin.game.voice.VoiceBankState
import io.github.abonsan.hyakunin.game.voice.fakeVoiceBank
import io.github.abonsan.hyakunin.ui.home.GameConfigStore
import io.github.abonsan.hyakunin.ui.match.LocalVoicePlayerFactory
import io.github.abonsan.hyakunin.ui.settings.AppSettings
import io.github.abonsan.hyakunin.ui.settings.AppSettingsStore
import io.github.abonsan.hyakunin.ui.settings.FakeVoicePreviewFactory
import io.github.abonsan.hyakunin.ui.settings.LocalVoicePreviewFactory
import io.github.abonsan.hyakunin.ui.settings.PREVIEW_LABEL
import io.github.abonsan.hyakunin.ui.settings.ScreenOrientation
import io.github.abonsan.hyakunin.ui.settings.VOLUME_LABEL
import io.github.abonsan.hyakunin.ui.theme.HyakuninTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** トップ・対戦設定・設定・対戦の行き来と、設定の保存。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class HyakuninAppTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val configStore = GameConfigStore(context)
    private val settingsStore = AppSettingsStore(context)
    private val orientations = mutableListOf<ScreenOrientation>()
    private val players = RecordingVoicePlayerFactory()
    private val previews = FakeVoicePreviewFactory()

    /**
     * アプリを表示する。読み上げ音声は [voiceBanks]（既定は音声なし）で、対戦と「試聴」の再生器は記録するだけの偽物にする。
     */
    private fun showApp(voiceBanks: VoiceBankSource = VoiceBankSource.ready(null)) {
        composeRule.setContent {
            CompositionLocalProvider(LocalVoicePlayerFactory provides players, LocalVoicePreviewFactory provides previews) {
                HyakuninTheme {
                    HyakuninApp(
                        configStore,
                        settingsStore,
                        onOrientationChange = { orientations += it },
                        voiceBanks = voiceBanks,
                    )
                }
            }
        }
    }

    private fun pressBack() {
        // 直前の操作による画面の切り替え（戻る操作の受け手）を反映してから押す
        composeRule.waitForIdle()
        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.waitForIdle()
    }

    private fun activeMatch() = composeRule.waitForIdle().let { composeRule.activity.activeMatchViewModel() }

    @Test
    fun topLeadsToTheMatchSetupAndTheMatch() {
        showApp()
        composeRule.onNodeWithText("百人一首").assertExists()

        composeRule.onNodeWithText("競技モード").performClick()
        composeRule.onNodeWithText("対戦設定").assertExists()
        composeRule.onNodeWithText("対戦開始").performClick()
        composeRule.onNodeWithText("札を覚える時間").assertExists()
        assertNotNull(activeMatch())

        // 戻るとやめるかを確かめる。続けると対戦に残る
        pressBack()
        composeRule.onNodeWithText("対戦をやめますか？").assertExists()
        composeRule.onNodeWithText("続ける").performClick()
        composeRule.onNodeWithText("札を覚える時間").assertExists()

        // やめると対戦設定に戻り、対戦の ViewModel は破棄する
        pressBack()
        composeRule.onNodeWithText("やめる").performClick()
        composeRule.onNodeWithText("対戦開始").assertExists()
        assertNull(activeMatch())

        // 対戦設定から戻るとトップ
        pressBack()
        composeRule.onNodeWithText("モードを選んでください").assertExists()
    }

    @Test
    fun topLeadsToSettingsAndBack() {
        showApp()
        composeRule.onNodeWithText("設定").performClick()
        composeRule.onNodeWithText("画面の向き").assertExists()
        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.onNodeWithText("モードを選んでください").assertExists()

        composeRule.onNodeWithText("設定").performClick()
        pressBack()
        composeRule.onNodeWithText("モードを選んでください").assertExists()
    }

    @Test
    fun settingsAreSavedAndTheOrientationIsApplied() {
        showApp()
        composeRule.onNodeWithText("設定").performClick()
        composeRule.onNodeWithText(ScreenOrientation.LANDSCAPE.label).performClick()
        composeRule.onNodeWithText("敵陣の札を逆さに表示").performScrollTo().performClick()
        composeRule.onNodeWithText(VerseStyle.READING.label).performScrollTo().performClick()

        assertEquals(
            AppSettings(ScreenOrientation.LANDSCAPE, flipOpponentCards = false, verseStyle = VerseStyle.READING),
            settingsStore.load(),
        )
        assertEquals(listOf(ScreenOrientation.LANDSCAPE), orientations)
        // 向きを変えないときは呼ばない
        composeRule.onNodeWithText("敵陣の札を逆さに表示").performScrollTo().performClick()
        composeRule.onNodeWithText(VerseStyle.KANA.label).performScrollTo().performClick()
        assertEquals(VerseStyle.KANA, settingsStore.load().verseStyle)
        composeRule.onNodeWithText(ScreenOrientation.AUTO.label).performScrollTo().performClick()
        assertEquals(listOf(ScreenOrientation.LANDSCAPE, ScreenOrientation.AUTO), orientations)
    }

    @Test
    fun theVolumeIsSavedAndTheMatchPlaysAtIt() {
        showApp(voiceBanks = VoiceBankSource.ready(fakeVoiceBank(poems = Poems.all)))
        composeRule.onNodeWithText("設定").performClick()
        composeRule.onNodeWithContentDescription(VOLUME_LABEL)
            .performScrollTo()
            .performSemanticsAction(SemanticsActions.SetProgress) { it(1.6f) }
        assertEquals(1.6f, settingsStore.load().voiceVolume)
        composeRule.onNodeWithContentDescription("戻る").performClick()

        // 対戦を始めるときに音量を対戦設定へ移し、鳴らす前に再生器へ渡す
        composeRule.onNodeWithText("競技モード").performClick()
        composeRule.onNodeWithText("対戦開始").performClick()
        composeRule.waitForIdle()
        assertEquals(1.6f, activeMatch()!!.uiState.value.config.voiceVolume)
        val player = players.players.single()
        assertEquals(listOf(1.6f), player.volumes)
        // 対戦の間は渡し直さない
        composeRule.onNodeWithText("すぐ読む").performClick()
        composeRule.advanceMainLooper(3_000L)
        assertEquals(listOf(1.6f), player.volumes)
    }

    @Test
    fun theSettingsPreviewPlaysTheJokaFromTheVoiceBank() {
        val loading = MutableStateFlow<VoiceBankState>(VoiceBankState.Loading)
        var requests = 0
        val source = object : VoiceBankSource {
            override val state: StateFlow<VoiceBankState> = loading

            override fun request() {
                requests++
            }
        }
        settingsStore.save(AppSettings(voiceVolume = 0.6f))
        showApp(voiceBanks = source)
        composeRule.onNodeWithText("設定").performClick()
        composeRule.waitForIdle()
        // 設定の画面を開いたら音声を読み込み始め、読み終えるまで「試聴」は押せない
        assertTrue(requests > 0)
        composeRule.onNodeWithText(PREVIEW_LABEL).performScrollTo().assertIsNotEnabled()
        loading.value = VoiceBankState.Ready(fakeVoiceBank(poems = Poems.all))
        composeRule.onNodeWithText(PREVIEW_LABEL).assertIsEnabled().performClick()
        // 序歌の上の句を、設定の音量で鳴らす
        val preview = previews.previews.single()
        assertEquals("play ${FAKE_JOKA_KAMI.asset} 0.6", preview.commands.last { it.startsWith("play") })
        // 設定の画面を離れると止めて手放す
        composeRule.onNodeWithContentDescription("戻る").performClick()
        composeRule.waitForIdle()
        assertTrue(preview.released)
    }

    @Test
    fun withTheVoiceOffTheSettingsScreenDoesNotLoadTheVoice() {
        settingsStore.save(AppSettings(voiceEnabled = false))
        val source = object : VoiceBankSource {
            override val state: StateFlow<VoiceBankState> = MutableStateFlow(VoiceBankState.Loading)

            override fun request() = error("読み上げ音声を使わないなら読み込まない")
        }
        showApp(voiceBanks = source)
        composeRule.onNodeWithText("設定").performClick()
        composeRule.onNodeWithContentDescription(VOLUME_LABEL).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun eachModeKeepsItsOwnMatchSettings() {
        showApp()
        composeRule.onNodeWithText("簡易モード").performClick()
        composeRule.onNodeWithText(CpuLevel.MASTER.label).performClick()
        composeRule.onNodeWithText(ReadStartMode.TAP.label).performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("戻る").performClick()

        // 競技モードは別の設定のまま
        composeRule.onNodeWithText("競技モード").performClick()
        composeRule.onNodeWithText(MemorizeTime.MIN_1.label).performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("戻る").performClick()

        assertEquals(CpuLevel.MASTER, configStore.load(RuleMode.SIMPLE).cpuLevel)
        assertEquals(ReadStartMode.TAP, configStore.load(RuleMode.SIMPLE).readStartMode)
        assertEquals(GameConfig().cpuLevel, configStore.load(RuleMode.COMPETITIVE).cpuLevel)
        assertEquals(MemorizeTime.MIN_1, configStore.load(RuleMode.COMPETITIVE).memorizeTime)
        assertEquals(GameConfig().memorizeTime, configStore.load(RuleMode.SIMPLE).memorizeTime)

        // もう一度選ぶと、そのモードの設定で対戦する
        composeRule.onNodeWithText("簡易モード").performClick()
        composeRule.onNodeWithText(CpuLevel.MASTER.label).assertIsSelected()
        composeRule.onNodeWithText("対戦開始").performClick()
        composeRule.onNodeWithText("相手（名人）").assertExists()
        composeRule.onNodeWithText("読み始める").assertExists()
    }

    @Test
    fun theMatchIsStartedWithTheModeSettingsAndTheDisplaySettings() {
        configStore.save(
            GameConfig(
                ruleMode = RuleMode.SIMPLE,
                emptyCardCount = 30,
                cpuLevel = CpuLevel.BEGINNER,
                readingStyle = ReadingStyle.COMPETITIVE,
            ),
        )
        settingsStore.save(AppSettings(flipOpponentCards = false, verseStyle = VerseStyle.READING, voiceEnabled = false))
        showApp()
        composeRule.onNodeWithText("簡易モード").performClick()
        composeRule.onNodeWithText("対戦開始").performClick()

        val config = activeMatch()!!.uiState.value.config
        assertEquals(
            GameConfig(
                ruleMode = RuleMode.SIMPLE,
                emptyCardCount = 30,
                cpuLevel = CpuLevel.BEGINNER,
                flipOpponentCards = false,
                verseStyle = VerseStyle.READING,
                readingStyle = ReadingStyle.COMPETITIVE,
                voiceEnabled = false,
            ),
            config,
        )
        composeRule.onNodeWithText("相手（初級）").assertExists()
    }

    @Test
    fun eachModeStartsWithItsOwnReadingStyle() {
        showApp()
        composeRule.onNodeWithText("簡易モード").performClick()
        composeRule.onNodeWithText(ReadingStyle.SIMPLE.label).performScrollTo().assertIsSelected()
        composeRule.onNodeWithText("対戦開始").performClick()
        assertEquals(ReadingStyle.SIMPLE, activeMatch()!!.uiState.value.config.readingStyle)
        pressBack()
        composeRule.onNodeWithText("やめる").performClick()
        pressBack()

        composeRule.onNodeWithText("競技モード").performClick()
        composeRule.onNodeWithText(ReadingStyle.COMPETITIVE.label).performScrollTo().assertIsSelected()
        composeRule.onNodeWithText("対戦開始").performClick()
        val config = activeMatch()!!.uiState.value.config
        assertEquals(ReadingStyle.COMPETITIVE, config.readingStyle)
        // 読み上げ音声の既定はオン
        assertEquals(true, config.voiceEnabled)
    }

    @Test
    fun aCompetitiveMatchWithoutVoiceStillReadsTheJokaAsText() {
        // 音声がなくても、競技かるた式なら序歌を文字で出してから最初の札を読む
        showApp(voiceBanks = VoiceBankSource.ready(null))
        composeRule.onNodeWithText("競技モード").performClick()
        composeRule.onNodeWithText("対戦開始").performClick()
        composeRule.onNodeWithText("すぐ読む").performClick()
        composeRule.onNodeWithText("序歌").assertExists()
        val state = activeMatch()!!.uiState.value
        assertEquals(LeadInKind.JOKA, state.leadIn!!.kind)
        // 音声はない
        assertNull(state.audio)
        assertTrue(players.players.single().cues.isEmpty())
    }

    @Test
    fun theVoiceFollowsTheMatchAndIsReleasedWhenTheMatchEnds() {
        showApp(voiceBanks = VoiceBankSource.ready(fakeVoiceBank(poems = Poems.all)))
        composeRule.onNodeWithText("競技モード").performClick()
        composeRule.onNodeWithText("対戦開始").performClick()
        composeRule.waitForIdle()
        val player = players.players.single()
        // 音声で読むときは、最初の音が遅れないよう先に用意しておく
        assertTrue(player.warmedUp)
        // 札を覚える時間の間に、最初に鳴る序歌の上の句を予告する（再生側が先に読み込む）
        composeRule.advanceMainLooper(100L)
        assertEquals("voice/joka_kami.ogg", player.nexts.last()?.asset)

        // 札を覚える時間を飛ばすと、序歌の上の句が鳴る
        composeRule.onNodeWithText("すぐ読む").performClick()
        composeRule.advanceMainLooper(500L)
        val state = activeMatch()!!.uiState.value
        assertEquals(LeadInKind.JOKA, state.leadIn!!.kind)
        assertEquals("voice/joka_kami.ogg", state.audio!!.asset)
        composeRule.onNodeWithText("序歌").assertExists()
        // 再生器には、対戦の最新の状態（音声・エンジンの時計・一時停止）がそのまま渡っている
        val latest = activeMatch()!!.uiState.value
        val (cue, time, paused) = player.updates.last()
        assertEquals(latest.audio, cue)
        assertEquals(latest.engineTimeMillis, time)
        assertFalse(paused)

        // 一時停止すると、再生器にも一時停止が渡る
        composeRule.onNodeWithContentDescription("一時停止").performClick()
        composeRule.waitForIdle()
        assertTrue(player.updates.last().third)
        composeRule.onNodeWithText("再開").performClick()
        composeRule.waitForIdle()
        assertFalse(player.updates.last().third)

        // 対戦をやめると再生器を手放す
        pressBack()
        composeRule.onNodeWithText("やめる").performClick()
        composeRule.waitForIdle()
        assertTrue(player.released)
    }

    @Test
    fun eachMatchHasOneVoicePlayerAndAnOldOneIsNotLeftPlaying() {
        showApp(voiceBanks = VoiceBankSource.ready(fakeVoiceBank(poems = Poems.all)))
        composeRule.onNodeWithText("競技モード").performClick()
        composeRule.onNodeWithText("対戦開始").performClick()
        composeRule.onNodeWithText("すぐ読む").performClick()
        // 刻みごとに画面を描き直しても（一時停止・再開をはさんでも）、再生器は作り直さない
        composeRule.advanceMainLooper(3_000L)
        composeRule.onNodeWithContentDescription("一時停止").performClick()
        composeRule.onNodeWithText("再開").performClick()
        val first = players.players.single()
        val updatesBefore = first.updates.size
        composeRule.advanceMainLooper(3_000L)
        assertSame(first, players.players.single())
        assertSame(first, composeRule.activity.activeVoicePlayer())
        assertFalse(first.released)
        assertTrue("${first.updates.size} > $updatesBefore", first.updates.size > updatesBefore)

        // 対戦をやめて新しい対戦を始めると、前の再生器は手放し、新しい再生器だけが鳴らす
        pressBack()
        composeRule.onNodeWithText("やめる").performClick()
        composeRule.waitForIdle()
        assertTrue(first.released)
        composeRule.onNodeWithText("対戦開始").performClick()
        composeRule.onNodeWithText("すぐ読む").performClick()
        composeRule.advanceMainLooper(1_000L)
        assertEquals(2, players.players.size)
        val second = players.players.last()
        assertSame(second, composeRule.activity.activeVoicePlayer())
        assertFalse(second.released)
        val updatesOfFirst = first.updates.size
        composeRule.advanceMainLooper(1_000L)
        assertEquals("手放した再生器には何も渡さない", updatesOfFirst, first.updates.size)
    }

    @Test
    fun losingTheAudioFocusPausesTheMatch() {
        showApp(voiceBanks = VoiceBankSource.ready(fakeVoiceBank(poems = Poems.all)))
        composeRule.onNodeWithText("競技モード").performClick()
        composeRule.onNodeWithText("対戦開始").performClick()
        composeRule.onNodeWithText("すぐ読む").performClick()
        composeRule.advanceMainLooper(500L)
        val match = activeMatch()!!
        assertFalse(match.uiState.value.isPaused)

        // 電話などで音を止められた: アプリが裏に回ったときと同じく対戦を止め、一時停止のダイアログを出す
        composeRule.runOnUiThread { players.players.single().onInterrupted() }
        composeRule.waitForIdle()
        assertTrue(match.uiState.value.isPaused)
        composeRule.onNodeWithText("一時停止中").assertExists()
        val frozen = match.uiState.value.engineTimeMillis
        composeRule.advanceMainLooper(2_000L)
        assertEquals(frozen, match.uiState.value.engineTimeMillis)
        composeRule.onNodeWithText("再開").performClick()
        assertFalse(match.uiState.value.isPaused)
    }

    @Test
    fun theMatchWaitsUntilTheVoiceIsLoaded() {
        val loading = MutableStateFlow<VoiceBankState>(VoiceBankState.Loading)
        var requests = 0
        val source = object : VoiceBankSource {
            override val state: StateFlow<VoiceBankState> = loading

            override fun request() {
                requests++
            }
        }
        showApp(voiceBanks = source)
        // 対戦設定を開いたら読み込み始める
        composeRule.onNodeWithText("競技モード").performClick()
        composeRule.waitForIdle()
        assertTrue(requests > 0)
        // 読み込み中に対戦を始めると、読み終えるまで待つ
        composeRule.onNodeWithText("対戦開始").performClick()
        composeRule.onNodeWithText("札を覚える時間").assertDoesNotExist()
        loading.value = VoiceBankState.Ready(fakeVoiceBank(poems = Poems.all))
        composeRule.onNodeWithText("札を覚える時間").assertExists()
        assertTrue(players.players.single().warmedUp)
    }

    @Test
    fun withTheVoiceTurnedOffTheMatchDoesNotWaitForIt() {
        settingsStore.save(AppSettings(voiceEnabled = false))
        val source = object : VoiceBankSource {
            override val state: StateFlow<VoiceBankState> = MutableStateFlow(VoiceBankState.Loading)

            override fun request() = error("読み上げ音声を使わないなら読み込まない")
        }
        showApp(voiceBanks = source)
        composeRule.onNodeWithText("競技モード").performClick()
        composeRule.onNodeWithText("対戦開始").performClick()
        composeRule.onNodeWithText("札を覚える時間").assertExists()
        assertEquals(false, activeMatch()!!.uiState.value.config.voiceEnabled)
    }

    @Test
    fun theFirstMatchShowsVersesInHistoricalKana() {
        // 何も保存していない（初めて起動した）ときは、歴史的仮名遣いで対戦する
        showApp()
        composeRule.onNodeWithText("競技モード").performClick()
        composeRule.onNodeWithText("対戦開始").performClick()
        assertEquals(VerseStyle.KANA, activeMatch()!!.uiState.value.config.verseStyle)
    }
}
