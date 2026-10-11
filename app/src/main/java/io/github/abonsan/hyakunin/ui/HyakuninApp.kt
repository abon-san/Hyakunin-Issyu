package io.github.abonsan.hyakunin.ui

import androidx.activity.compose.BackHandler
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.ui.home.GameConfigStore
import io.github.abonsan.hyakunin.ui.home.MatchSetupScreen
import io.github.abonsan.hyakunin.ui.home.TopScreen
import io.github.abonsan.hyakunin.ui.match.MatchRoute
import io.github.abonsan.hyakunin.ui.settings.AppSettingsStore
import io.github.abonsan.hyakunin.ui.settings.ScreenOrientation
import io.github.abonsan.hyakunin.ui.settings.SettingsScreen

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
 */
@Composable
fun HyakuninApp(
    configStore: GameConfigStore,
    settingsStore: AppSettingsStore,
    onOrientationChange: (ScreenOrientation) -> Unit = {},
) {
    var screen by rememberSaveable { mutableStateOf(Screen.TOP) }
    var mode by rememberSaveable { mutableStateOf(RuleMode.COMPETITIVE) }
    var matchId by rememberSaveable { mutableIntStateOf(0) }
    // 対戦を始めたときの設定（対戦中は変わらない）
    var matchConfig by rememberSaveable(stateSaver = GameConfigSaver) { mutableStateOf(GameConfig()) }
    var settings by remember { mutableStateOf(settingsStore.load()) }
    var setupConfig by remember(mode) { mutableStateOf(configStore.load(mode)) }

    BackHandler(enabled = screen == Screen.SETUP || screen == Screen.SETTINGS) { screen = Screen.TOP }

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
                // 画面の設定（敵陣の札の向き・句の表示）は、対戦を始めるときに対戦設定へ移す
                matchConfig = setupConfig.copy(
                    flipOpponentCards = settings.flipOpponentCards,
                    verseStyle = settings.verseStyle,
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
            MatchRoute(config = matchConfig, onExit = { screen = Screen.SETUP })
        }
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
        )
    },
    restore = {
        GameConfig(
            ruleMode = RuleMode.valueOf(it[0] as String),
            emptyCardCount = it[1] as Int,
            cpuLevel = CpuLevel.valueOf(it[2] as String),
            readingSpeed = ReadingSpeed.valueOf(it[3] as String),
            flipOpponentCards = it[4] as Boolean,
            verseStyle = VerseStyle.valueOf(it[5] as String),
            readStartMode = ReadStartMode.valueOf(it[6] as String),
            memorizeTime = MemorizeTime.valueOf(it[7] as String),
            readInterval = ReadInterval.valueOf(it[8] as String),
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
