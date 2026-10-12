package io.github.abonsan.hyakunin.ui.match

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.abonsan.hyakunin.audio.ExoVoicePlayer
import io.github.abonsan.hyakunin.audio.VoicePlayer
import io.github.abonsan.hyakunin.audio.VoicePlayerFactory
import io.github.abonsan.hyakunin.game.MatchViewModel

/** 対戦の読み上げ音声を鳴らす再生器の作り方。既定は Media3 ExoPlayer。テストでは偽物に差し替える。 */
val LocalVoicePlayerFactory = staticCompositionLocalOf { VoicePlayerFactory(::ExoVoicePlayer) }

/**
 * 対戦の音声の再生器。対戦の ViewModel と同じ ViewModelStore に置き、同じ間だけ持つ
 * （画面を回しても鳴り続け、対戦を抜けて ViewModel が破棄されると手放す）。
 */
internal class VoicePlayerHolder(val player: VoicePlayer) : ViewModel() {
    override fun onCleared() = player.release()
}

/**
 * 対戦 [match] の読み上げ音声を鳴らす。対戦の状態（今鳴らすべき音声・エンジンの時計・一時停止・次に鳴るクリップ）を、
 * 対戦画面がある間ずっと再生器に渡す。アプリが裏に回ると対戦が一時停止する（MatchRoute の ON_STOP）ので、音も止まる。
 * 鳴っている間は、再生器が聞こえている位置を対戦の時計にする（[MatchViewModel.attachAudioClock]）。
 *
 * 音声フォーカスを失った・イヤホンが外れたなどで音を止められたときは、アプリが裏に回ったときと同じく
 * [MatchViewModel.setPaused] で対戦を止めて、表示と音声をそろえる（一時停止のダイアログから再開する）。
 *
 * @param warmUp 音声で読むなら true。最初の音が遅れないよう、再生器を先に用意する
 */
@Composable
internal fun MatchVoicePlayback(match: MatchViewModel, warmUp: Boolean) {
    val context = LocalContext.current.applicationContext
    val factory = LocalVoicePlayerFactory.current
    val holder = viewModel { VoicePlayerHolder(factory.create(context) { match.setPaused(true) }) }
    LaunchedEffect(holder, warmUp) {
        if (warmUp) holder.player.warmUp()
    }
    DisposableEffect(holder, match) {
        match.attachAudioClock(holder.player)
        onDispose { match.detachAudioClock(holder.player) }
    }
    LaunchedEffect(holder, match) {
        match.uiState.collect { holder.player.update(it.audio, it.engineTimeMillis, it.isPaused, it.nextAudio) }
    }
}
