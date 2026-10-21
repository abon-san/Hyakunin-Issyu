package io.github.abonsan.hyakunin.ui.quiz

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.abonsan.hyakunin.audio.VoicePreview
import io.github.abonsan.hyakunin.game.quiz.QuizPhase
import io.github.abonsan.hyakunin.game.quiz.QuizUiState
import io.github.abonsan.hyakunin.game.quiz.QuizViewModel
import io.github.abonsan.hyakunin.game.quiz.TEXT_KANA_MILLIS
import io.github.abonsan.hyakunin.game.quiz.revealedKanaAt
import io.github.abonsan.hyakunin.game.voice.VoiceBank
import io.github.abonsan.hyakunin.game.voice.VoiceClip
import io.github.abonsan.hyakunin.ui.settings.LocalVoicePreviewFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「札を覚える」の上の句の読み手。音声があれば鳴らして、聞こえている位置に合わせて文字を出し、なければ（鳴らせなければ）
 * [TEXT_KANA_MILLIS] ごとに1字ずつ出す。練習の ViewModel と同じ ViewModelStore に置き、同じ間だけ持つ
 * （画面を回しても読み続け、練習を抜けて破棄されると再生器を手放す）。メインスレッドからだけ呼ぶ。
 *
 * アプリが裏に回った間（[setInBackground]）と、やめるかを確かめている間（[setConfirming]）は、位置を保ったまま止める。
 */
internal class QuizReader(create: (onStopped: () -> Unit) -> VoicePreview) : ViewModel() {
    val player: VoicePreview = create { sounding = false }

    /** 最後に読み始めた読みの番号（[QuizUiState.readId]）。画面を回した後に同じ読みを読み直さないために覚えておく。 */
    var startedReadId: Long? = null
        private set

    /** 音声を鳴らしている（止めている間も含む）か。鳴り終えた・外から止められた・[stop] したら false。 */
    private var sounding = false

    private var inBackground = false
    private var confirming = false

    /** 再生器を [VoicePreview.pause] で止めているか。 */
    private var paused = false

    /** 文字を出していく仕事（音声の位置を追う、または時間で1字ずつ出す）。 */
    private var job: Job? = null

    private val holding: Boolean get() = inBackground || confirming

    /**
     * 読み [readId] の上の句を初めから読み、読んだ仮名の数を [reveal] で知らせる。[clip] があれば音量 [volume] で鳴らす。
     * [clip] がない・鳴らせない（電話中など）ときは、[kanaCount] 字を時間で1字ずつ出す。
     */
    fun start(readId: Long, clip: VoiceClip?, kanaCount: Int, volume: Float, reveal: (Int) -> Unit) {
        stop()
        startedReadId = readId
        sounding = clip != null && player.play(clip.asset, volume)
        // 止めているべきときに鳴らし始めたら（めったにない）、すぐ止める
        syncHold()
        job = viewModelScope.launch {
            if (clip != null && sounding) {
                while (sounding) {
                    player.positionMillis?.let { reveal(revealedKanaAt(it, clip.kanaStartMs)) }
                    delay(POSITION_POLL_MILLIS)
                }
            } else {
                var shown = 0
                while (shown < kanaCount) {
                    if (!holding) reveal(++shown)
                    delay(TEXT_KANA_MILLIS)
                }
            }
        }
    }

    /** 読むのをやめる（答えた・読み直す・練習をやめる）。 */
    fun stop() {
        job?.cancel()
        job = null
        if (sounding || paused) player.stop()
        sounding = false
        paused = false
    }

    fun setInBackground(value: Boolean) {
        inBackground = value
        syncHold()
    }

    fun setConfirming(value: Boolean) {
        confirming = value
        syncHold()
    }

    private fun syncHold() {
        if (holding && sounding && !paused) {
            player.pause()
            paused = true
        } else if (!holding && paused) {
            paused = false
            // 音声フォーカスを得られなければ（電話中など）止める。「もう一度読む」で読み直せる
            if (!player.resume()) stop()
        }
    }

    override fun onCleared() {
        job?.cancel()
        player.release()
    }

    private companion object {
        /** 鳴っている間に、聞こえている位置を見て文字を出す間隔（ms）。 */
        const val POSITION_POLL_MILLIS = 20L
    }
}

/** 練習の上の句の読み手（[QuizReader]）を、練習の ViewModelStore から得る。 */
@Composable
internal fun rememberQuizReader(): QuizReader {
    val context = LocalContext.current.applicationContext
    val factory = LocalVoicePreviewFactory.current
    return viewModel { QuizReader { onStopped -> factory.create(context, onStopped) } }
}

/** [voice] にある、[state] の今の問題の上の句のクリップ。ないか、時刻表が読みと合わなければ null（文字だけで読む）。 */
internal fun kamiClipOf(state: QuizUiState, voice: VoiceBank?): VoiceClip? {
    val poem = state.question.poem
    return voice?.poem(poem.no)?.kami?.takeIf { it.fits(poem.kamiYomi) }
}

/**
 * 答える前（[QuizPhase.Asking]）の間、今の問題の上の句を読み（[QuizUiState.readId]）ごとに一度だけ読み始める。
 * 読んだ仮名の数は [QuizViewModel.reveal] で渡す。
 *
 * @param voice 読み上げ音声。null なら文字だけで読む
 * @param volume 読み上げの音量（設定の「音量」）
 */
@Composable
internal fun QuizReading(state: QuizUiState, viewModel: QuizViewModel, reader: QuizReader, voice: VoiceBank?, volume: Float) {
    val asking = state.phase == QuizPhase.Asking
    LaunchedEffect(reader, viewModel, state.readId, asking) {
        if (!asking || reader.startedReadId == state.readId) return@LaunchedEffect
        reader.start(state.readId, kamiClipOf(state, voice), state.question.kamiKanaCount, volume, viewModel::reveal)
    }
}
