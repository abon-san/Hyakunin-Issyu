package io.github.abonsan.hyakunin.ui.match

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.MatchActions
import io.github.abonsan.hyakunin.game.MatchPhase
import io.github.abonsan.hyakunin.game.MatchViewModel
import io.github.abonsan.hyakunin.game.ReadTiming
import io.github.abonsan.hyakunin.game.ReadingStyle
import io.github.abonsan.hyakunin.game.voice.VoiceBank
import io.github.abonsan.hyakunin.ui.components.WafuDialog

/**
 * 対戦画面の入口。ViewModel は呼び出し側が対戦ごとに用意した ViewModelStore に作る。
 * アプリが裏に回ると一時停止し、戻ると再開する（自分で一時停止していた場合は止めたまま）。
 * 読み上げ音声は [MatchVoicePlayback] で鳴らし、一時停止の間は止める。
 *
 * @param voiceBank 読み上げ音声。null なら文字だけで読む（[GameConfig.voiceEnabled] が false のときも）
 */
@Composable
fun MatchRoute(config: GameConfig, onExit: () -> Unit, voiceBank: VoiceBank? = null) {
    val viewModel = viewModel { MatchViewModel(config, timing = matchTiming(config, voiceBank)) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // 一時停止ボタンや確認ダイアログで止めているか（アプリが前に戻っても勝手に再開しない）
    var pausedByUser by rememberSaveable { mutableStateOf(false) }
    var confirmingExit by rememberSaveable { mutableStateOf(false) }

    // 画面の状態だけが復元されて ViewModel が新しくなった場合（プロセスの再生成）も、止めていたなら止める
    LaunchedEffect(viewModel) {
        if (pausedByUser || confirmingExit) viewModel.setPaused(true)
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.setPaused(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        if (!pausedByUser && !confirmingExit) viewModel.setPaused(false)
    }
    MatchVoicePlayback(viewModel, warmUp = config.voiceEnabled && voiceBank != null)

    val actions = remember(viewModel) {
        object : MatchActions by viewModel {
            override fun setPaused(paused: Boolean) {
                pausedByUser = paused
                viewModel.setPaused(paused)
            }
        }
    }

    BackHandler {
        if (state.phase is MatchPhase.Finished) {
            onExit()
        } else {
            confirmingExit = true
            viewModel.setPaused(true)
        }
    }

    MatchScreen(
        state = state,
        actions = actions,
        onExit = onExit,
        pauseDialogVisible = state.isPaused && !confirmingExit,
    )

    if (confirmingExit) {
        val keepPlaying = {
            confirmingExit = false
            if (!pausedByUser) viewModel.setPaused(false)
        }
        WafuDialog(
            title = "対戦をやめますか？",
            message = "ここまでの成績は残りません。",
            confirmLabel = "やめる",
            onConfirm = onExit,
            dismissLabel = "続ける",
            onDismiss = keepPlaying,
            onDismissRequest = keepPlaying,
        )
    }
}

/**
 * 対戦の読み上げのタイミング。[voice] があれば音声に合わせて読む。
 * 競技かるた式なら、音声がなくても前置き（序歌・前の歌の下の句）を文字で出し、音声のあるときと同じ流れにする。
 */
internal fun matchTiming(config: GameConfig, voice: VoiceBank?): ReadTiming =
    ReadTiming.of(config.readingSpeed, voice, textLeadIn = config.readingStyle == ReadingStyle.COMPETITIVE)
