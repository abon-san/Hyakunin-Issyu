package io.github.abonsan.hyakunin.ui.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import io.github.abonsan.hyakunin.audio.ExoVoicePreview
import io.github.abonsan.hyakunin.audio.VoicePreviewFactory

/** 設定の画面の「試聴」の再生器の作り方。既定は Media3 ExoPlayer。テストでは偽物に差し替える。 */
val LocalVoicePreviewFactory = staticCompositionLocalOf { VoicePreviewFactory(::ExoVoicePreview) }

/** 「試聴」の状態。[play] で鳴らし、鳴っている間は [stop] で止める。鳴っている間も [setVolume] で音量を変えられる。 */
@Stable
internal class VoicePreviewState(factory: VoicePreviewFactory, context: Context) {
    /** 鳴っているか（ボタンが「試聴」から「停止」に変わる）。鳴り終えた・外から止められたら false に戻る。 */
    var playing by mutableStateOf(false)
        private set

    private val preview = factory.create(context) { playing = false }

    /** [asset] を先頭から [volume] で鳴らす。鳴らせなかった（電話中など）ときは鳴っていないまま。 */
    fun play(asset: String, volume: Float) {
        playing = preview.play(asset, volume)
    }

    fun stop() {
        preview.stop()
        playing = false
    }

    fun setVolume(volume: Float) = preview.setVolume(volume)

    fun release() {
        preview.release()
        playing = false
    }
}

/**
 * 「試聴」の状態を覚えておく。画面を離れる（コンポジションから外れる）と止めて再生器を手放し、
 * アプリが裏に回ったときも止める。
 */
@Composable
internal fun rememberVoicePreview(): VoicePreviewState {
    val context = LocalContext.current.applicationContext
    val factory = LocalVoicePreviewFactory.current
    val state = remember(factory, context) { VoicePreviewState(factory, context) }
    DisposableEffect(state) {
        onDispose { state.release() }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { state.stop() }
    return state
}
