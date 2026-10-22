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
import io.github.abonsan.hyakunin.game.quiz.TEXT_KAMI_SHIMO_GAP_MILLIS
import io.github.abonsan.hyakunin.game.quiz.TEXT_KANA_MILLIS
import io.github.abonsan.hyakunin.game.quiz.VOICE_KAMI_SHIMO_GAP_MILLIS
import io.github.abonsan.hyakunin.game.quiz.revealedKanaAt
import io.github.abonsan.hyakunin.game.voice.VoiceBank
import io.github.abonsan.hyakunin.game.voice.VoiceClip
import io.github.abonsan.hyakunin.ui.settings.LocalVoicePreviewFactory
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 読む句1つ。[clip] がその句の音声（なければ文字だけ）、[kanaCount] がその句の仮名の数。 */
internal class ReadPart(val clip: VoiceClip?, val kanaCount: Int)

/**
 * 「札を覚える」の読み手。上の句（設定によっては続けて下の句）を読む。句ごとに、音声があれば鳴らして聞こえている位置に
 * 合わせて文字を出し、なければ（鳴らせなければ）[TEXT_KANA_MILLIS] ごとに1字ずつ出す。句と句の間は対戦と同じだけ空ける。
 * 練習の ViewModel と同じ ViewModelStore に置き、同じ間だけ持つ（画面を回しても読み続け、練習を抜けて破棄されると
 * 再生器を手放す）。メインスレッドからだけ呼ぶ。
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
     * 読み [readId] の句 [parts]（上の句、続けて下の句）を初めから読み、読んだ仮名の数（句を通して数える）を [reveal] で
     * 知らせる。音声は音量 [volume] で鳴らす。鳴っている途中で外から止められた（電話など）ら、そこで読むのをやめる
     * （「もう一度読む」で読み直せる）。
     */
    fun start(readId: Long, parts: List<ReadPart>, volume: Float, reveal: (Int) -> Unit) {
        stop()
        startedReadId = readId
        job = viewModelScope.launch {
            var before = 0
            var voicedBefore = false
            for ((index, part) in parts.withIndex()) {
                if (index > 0) holdableDelay(if (voicedBefore) VOICE_KAMI_SHIMO_GAP_MILLIS else TEXT_KAMI_SHIMO_GAP_MILLIS)
                val base = before
                val played = part.clip?.let { play(it, volume) { kana -> reveal(base + kana) } }
                when (played) {
                    ClipPlayback.FINISHED -> voicedBefore = true
                    ClipPlayback.INTERRUPTED -> return@launch
                    // 音声がない・鳴らせない（電話中など）句は、文字だけで読む
                    ClipPlayback.NOT_STARTED, null -> {
                        showText(part.kanaCount) { kana -> reveal(base + kana) }
                        voicedBefore = false
                    }
                }
                before += part.kanaCount
            }
        }
    }

    /** クリップ [clip] を鳴らし、聞こえている位置までの仮名の数を [reveal] で知らせる。鳴り終えるか止められるまで待つ。 */
    private suspend fun play(clip: VoiceClip, volume: Float, reveal: (Int) -> Unit): ClipPlayback {
        sounding = player.play(clip.asset, volume)
        if (!sounding) return ClipPlayback.NOT_STARTED
        // 止めているべきときに鳴らし始めたら（めったにない）、すぐ止める
        syncHold()
        var heard = 0
        while (sounding) {
            player.positionMillis?.let {
                heard = revealedKanaAt(it, clip.kanaStartMs)
                reveal(heard)
            }
            delay(POSITION_POLL_MILLIS)
        }
        // 最後の仮名まで聞こえていれば鳴り終えた。そうでなければ外から止められた
        return if (heard >= clip.kanaStartMs.size) ClipPlayback.FINISHED else ClipPlayback.INTERRUPTED
    }

    /** [kanaCount] 字を、[TEXT_KANA_MILLIS] ごとに1字ずつ [reveal] で知らせる（止めている間は進めない）。 */
    private suspend fun showText(kanaCount: Int, reveal: (Int) -> Unit) {
        var shown = 0
        while (shown < kanaCount) {
            if (!holding) reveal(++shown)
            delay(TEXT_KANA_MILLIS)
        }
    }

    /** [millis] だけ待つ。止めている間は数えない。 */
    private suspend fun holdableDelay(millis: Long) {
        var left = millis
        while (left > 0) {
            delay(POSITION_POLL_MILLIS)
            if (!holding) left -= POSITION_POLL_MILLIS
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

    /** クリップを鳴らした結果。 */
    private enum class ClipPlayback {
        /** 鳴らせなかった（音声フォーカスを得られないなど）。 */
        NOT_STARTED,

        /** 最後まで鳴った。 */
        FINISHED,

        /** 途中で外から止められた。 */
        INTERRUPTED,
    }

    private companion object {
        /** 鳴っている間に、聞こえている位置を見て文字を出す間隔（ms）。 */
        const val POSITION_POLL_MILLIS = 20L
    }
}

/** 練習の読み手（[QuizReader]）を、練習の ViewModelStore から得る。 */
@Composable
internal fun rememberQuizReader(): QuizReader {
    val context = LocalContext.current.applicationContext
    val factory = LocalVoicePreviewFactory.current
    return viewModel { QuizReader { onStopped -> factory.create(context, onStopped) } }
}

/**
 * [state] の今の問題で読む句（上の句、下の句まで読むなら続けて下の句）。音声は [voice] から取る。
 * 音声がない・時刻表が読みと合わない句は、文字だけで読む。
 */
internal fun readPartsOf(state: QuizUiState, voice: VoiceBank?): List<ReadPart> {
    val question = state.question
    val poem = question.poem
    val clips = voice?.poem(poem.no)
    val kami = ReadPart(clips?.kami?.takeIf { it.fits(poem.kamiYomi) }, question.kamiKanaCount)
    if (!state.readsShimo) return listOf(kami)
    return listOf(kami, ReadPart(clips?.shimo?.takeIf { it.fits(poem.shimoYomi) }, question.shimoKanaCount))
}

/**
 * 答える前（[QuizPhase.Asking]）の間、今の問題の句を読み（[QuizUiState.readId]）ごとに一度だけ読み始める。
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
        reader.start(state.readId, readPartsOf(state, voice), volume, viewModel::reveal)
    }
}
