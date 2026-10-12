package io.github.abonsan.hyakunin.game.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** 読み上げ音声の読み込みの状態。 */
sealed interface VoiceBankState {
    /** 読み込み中（まだ読み込みを頼まれていないときも）。 */
    data object Loading : VoiceBankState

    /** 読み込み終わり。[bank] が null なら音声なし（文字だけで読む）。 */
    data class Ready(val bank: VoiceBank?) : VoiceBankState
}

/** 読み上げ音声を、必要になったときに読み込むところ。 */
interface VoiceBankSource {
    /** 読み込みの状態。 */
    val state: StateFlow<VoiceBankState>

    /** まだ読み込んでいなければ読み込み始める。何度呼んでもよく、呼んだスレッドは止めない。 */
    fun request()

    companion object {
        /** 読み込み済みのもの。[bank] が null なら音声なし。 */
        fun ready(bank: VoiceBank?): VoiceBankSource = ReadyVoiceBankSource(bank)
    }
}

private class ReadyVoiceBankSource(bank: VoiceBank?) : VoiceBankSource {
    override val state: StateFlow<VoiceBankState> = MutableStateFlow(VoiceBankState.Ready(bank))

    override fun request() = Unit
}

/**
 * 最初に [request] されたときに、[load] を [dispatcher]（既定は IO スレッド）で一度だけ呼ぶ。
 * [load] が例外を投げたときは音声なし（[VoiceBankState.Ready] の null）にする。
 */
class VoiceBankLoader(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val load: () -> VoiceBank?,
) : VoiceBankSource {
    private val _state = MutableStateFlow<VoiceBankState>(VoiceBankState.Loading)
    override val state: StateFlow<VoiceBankState> = _state.asStateFlow()

    private val started = AtomicBoolean(false)

    override fun request() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(dispatcher) {
            val bank = try {
                load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // 読み込めなくても対戦はできる（文字だけで読む）
                null
            }
            _state.value = VoiceBankState.Ready(bank)
        }
    }
}
