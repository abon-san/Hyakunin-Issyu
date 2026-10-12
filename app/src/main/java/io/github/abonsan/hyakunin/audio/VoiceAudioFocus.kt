package io.github.abonsan.hyakunin.audio

import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * 読み上げ音声の音声フォーカス（ゲームの読み上げとして AUDIOFOCUS_GAIN を求める）。
 * 鳴らす前に [request]、対戦を止めたら [abandon]。知らせはメインスレッドで受ける。
 *
 * - ほかのアプリに取られたら（電話など一時的なものも）手放して [onLoss] を呼ぶ。呼び出し側は対戦を一時停止し、
 *   戻ったら「再開」で続ける（勝手には鳴らし直さない）
 * - 通知音などの短い音の間は [onDuck] で小さくする（読み上げの音声はシステムが自動では小さくしない）
 */
internal class VoiceAudioFocus(
    private val audioManager: AudioManager,
    private val onLoss: () -> Unit,
    private val onDuck: (Boolean) -> Unit,
) {
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(SPEECH_ATTRIBUTES)
        .setOnAudioFocusChangeListener(::onFocusChange, Handler(Looper.getMainLooper()))
        .build()

    /** フォーカスを持っているか。 */
    var held: Boolean = false
        private set

    /** フォーカスを求める。得られた（もう持っている）なら true。電話中などで得られなければ false。 */
    fun request(): Boolean {
        if (!held) held = audioManager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return held
    }

    /** フォーカスを手放す。 */
    fun abandon() {
        if (!held) return
        held = false
        audioManager.abandonAudioFocusRequest(focusRequest)
        onDuck(false)
    }

    private fun onFocusChange(change: Int) {
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> onDuck(false)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> onDuck(true)
            AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                abandon()
                onLoss()
            }
        }
    }

    companion object {
        /** ゲームの読み上げ（声）。 */
        val SPEECH_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
    }
}
