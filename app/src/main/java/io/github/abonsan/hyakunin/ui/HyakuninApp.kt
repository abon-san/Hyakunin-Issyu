package io.github.abonsan.hyakunin.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.abonsan.hyakunin.game.CpuLevel
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.MemorizeTime
import io.github.abonsan.hyakunin.game.ReadInterval
import io.github.abonsan.hyakunin.game.ReadStartMode
import io.github.abonsan.hyakunin.game.ReadingSpeed
import io.github.abonsan.hyakunin.game.ReadingStyle
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.game.voice.VoiceBankSource
import io.github.abonsan.hyakunin.game.voice.VoiceBankState
import io.github.abonsan.hyakunin.ui.home.GameConfigStore
import io.github.abonsan.hyakunin.ui.home.MatchSetupScreen
import io.github.abonsan.hyakunin.ui.home.TopScreen
import io.github.abonsan.hyakunin.ui.home.defaultReadingStyle
import io.github.abonsan.hyakunin.ui.match.MatchRoute
import io.github.abonsan.hyakunin.ui.settings.AppSettingsStore
import io.github.abonsan.hyakunin.ui.settings.ScreenOrientation
import io.github.abonsan.hyakunin.ui.settings.SettingsScreen
import io.github.abonsan.hyakunin.ui.theme.Washi

/** アプリの画面。 */
internal enum class Screen {
    /** トップ（モードの選択）。 */
    TOP,

    /** 選んだモードの対戦設定。 */
    SETUP,

    /** 画面の設定。 */
    SETTINGS,

    /** 対戦。 */
    MATCH,
}

/**
 * トップ・対戦設定・設定・対戦の画面を切り替えるアプリ全体。
 * 表示中の画面と対戦の設定は画面の回転などでも保つ。設定は変えるたびに保存する。
 *
 * @param onOrientationChange 画面の向きの設定が変わったときに呼ぶ（Activity に向きを指定する）。
 * @param voiceBanks 読み上げ音声の読み込み。読み上げ音声を使う設定なら、対戦設定を開いたときに裏で読み込み始める。
 */
@Composable
fun HyakuninApp(
    configStore: GameConfigStore,
    settingsStore: AppSettingsStore,
    onOrientationChange: (ScreenOrientation) -> Unit = {},
    voiceBanks: VoiceBankSource = rememberAssetVoiceBanks(),
) {
    var screen by rememberSaveable { mutableStateOf(Screen.TOP) }
    var mode by rememberSaveable { mutableStateOf(RuleMode.COMPETITIVE) }
    var matchId by rememberSaveable { mutableIntStateOf(0) }
    // 対戦を始めたときの設定（対戦中は変わらない）
    var matchConfig by rememberSaveable(stateSaver = GameConfigSaver) { mutableStateOf(GameConfig()) }
    var settings by remember { mutableStateOf(settingsStore.load()) }
    var setupConfig by remember(mode) { mutableStateOf(configStore.load(mode)) }

    BackHandler(enabled = screen == Screen.SETUP || screen == Screen.SETTINGS) { screen = Screen.TOP }
    // 対戦を始めるまでに読み終えるよう、対戦設定を開いたら読み込み始める
    LaunchedEffect(screen, settings.voiceEnabled) {
        if (settings.voiceEnabled && (screen == Screen.SETUP || screen == Screen.MATCH)) voiceBanks.request()
    }

    when (screen) {
        Screen.TOP -> TopScreen(
            onSelectMode = {
                mode = it
                screen = Screen.SETUP
            },
            onOpenSettings = { screen = Screen.SETTINGS },
        )
        Screen.SETUP -> MatchSetupScreen(
            config = setupConfig,
            onConfigChange = {
                setupConfig = it
                configStore.save(it)
            },
            onStart = {
                // 画面の設定（敵陣の札の向き・句の表示・読み上げ音声）は、対戦を始めるときに対戦設定へ移す
                matchConfig = setupConfig.copy(
                    flipOpponentCards = settings.flipOpponentCards,
                    verseStyle = settings.verseStyle,
                    voiceEnabled = settings.voiceEnabled,
                )
                matchId++
                screen = Screen.MATCH
            },
            onBack = { screen = Screen.TOP },
        )
        Screen.SETTINGS -> SettingsScreen(
            settings = settings,
            onSettingsChange = { new ->
                val orientationChanged = new.orientation != settings.orientation
                settings = new
                settingsStore.save(new)
                if (orientationChanged) onOrientationChange(new.orientation)
            },
            onBack = { screen = Screen.TOP },
        )
        Screen.MATCH -> MatchScope(matchId = matchId, isActive = { screen == Screen.MATCH }) {
            VoicedMatch(config = matchConfig, voiceBanks = voiceBanks, onExit = { screen = Screen.SETUP })
        }
    }
}

/**
 * 対戦。読み上げ音声を使うなら、読み込み終えてから始める（ふつうは対戦設定の間に読み終えている。
 * 読み込み中は和紙の地だけを出す）。音声がない（読めなかった）ときは文字だけで読む。
 */
@Composable
private fun VoicedMatch(config: GameConfig, voiceBanks: VoiceBankSource, onExit: () -> Unit) {
    if (!config.voiceEnabled) {
        MatchRoute(config = config, onExit = onExit)
        return
    }
    LaunchedEffect(voiceBanks) { voiceBanks.request() }
    val voice by voiceBanks.state.collectAsState()
    when (val loaded = voice) {
        VoiceBankState.Loading -> Box(Modifier.fillMaxSize().background(Washi))
        is VoiceBankState.Ready -> MatchRoute(config = config, onExit = onExit, voiceBank = loaded.bank)
    }
}

/** [GameConfig] を画面の状態として保存する。 */
internal val GameConfigSaver: Saver<GameConfig, Any> = listSaver(
    save = {
        listOf(
            it.ruleMode.name,
            it.emptyCardCount,
            it.cpuLevel.name,
            it.readingSpeed.name,
            it.flipOpponentCards,
            it.verseStyle.name,
            it.readStartMode.name,
            it.memorizeTime.name,
            it.readInterval.name,
            it.readingStyle.name,
            it.voiceEnabled,
        )
    },
    restore = {
        val ruleMode = RuleMode.valueOf(it[0] as String)
        GameConfig(
            ruleMode = ruleMode,
            emptyCardCount = it[1] as Int,
            cpuLevel = CpuLevel.valueOf(it[2] as String),
            readingSpeed = ReadingSpeed.valueOf(it[3] as String),
            flipOpponentCards = it[4] as Boolean,
            verseStyle = VerseStyle.valueOf(it[5] as String),
            readStartMode = ReadStartMode.valueOf(it[6] as String),
            memorizeTime = MemorizeTime.valueOf(it[7] as String),
            readInterval = ReadInterval.valueOf(it[8] as String),
            // 後から足した項目。足す前に保存した状態から戻すときは既定値
            readingStyle = (it.getOrNull(9) as String?)?.let(ReadingStyle::valueOf) ?: ruleMode.defaultReadingStyle,
            voiceEnabled = it.getOrNull(10) as Boolean? ?: true,
        )
    },
)

/**
 * 対戦ごとに新しい ViewModelStore を [content] に渡す。
 * 画面の回転などの構成変更では同じ対戦を続け、対戦を抜けたら（[isActive] が false）破棄する。
 */
@Composable
private fun MatchScope(matchId: Int, isActive: () -> Boolean, content: @Composable () -> Unit) {
    val holder = viewModel { MatchStoreHolder() }
    val owner = remember(holder, matchId) { holder.ownerFor(matchId) }
    val currentIsActive by rememberUpdatedState(isActive)
    DisposableEffect(owner) {
        onDispose { if (!currentIsActive()) holder.release(matchId) }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

/** 対戦用の ViewModelStore を Activity の ViewModel として持つ。 */
internal class MatchStoreHolder : ViewModel() {
    private val store = ViewModelStore()
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore: ViewModelStore get() = store
    }
    private var matchId: Int? = null

    fun ownerFor(id: Int): ViewModelStoreOwner {
        if (matchId != id) {
            store.clear()
            matchId = id
        }
        return owner
    }

    /** 対戦中なら、その対戦の ViewModel を持つ owner。対戦していなければ null（テストで状態を確かめるため）。 */
    @VisibleForTesting
    fun activeOwner(): ViewModelStoreOwner? = owner.takeIf { matchId != null }

    fun release(id: Int) {
        if (matchId == id) {
            store.clear()
            matchId = null
        }
    }

    override fun onCleared() = store.clear()
}
