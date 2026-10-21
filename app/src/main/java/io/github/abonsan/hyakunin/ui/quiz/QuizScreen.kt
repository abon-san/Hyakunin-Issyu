package io.github.abonsan.hyakunin.ui.quiz

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import io.github.abonsan.hyakunin.R
import io.github.abonsan.hyakunin.data.Poem
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.game.quiz.QuizPhase
import io.github.abonsan.hyakunin.game.quiz.QuizUiState
import io.github.abonsan.hyakunin.ui.card.Torifuda
import io.github.abonsan.hyakunin.ui.components.CenteredScrollColumn
import io.github.abonsan.hyakunin.ui.components.PrimaryButton
import io.github.abonsan.hyakunin.ui.components.ScreenHeader
import io.github.abonsan.hyakunin.ui.components.SecondaryButton
import io.github.abonsan.hyakunin.ui.components.SectionLabel
import io.github.abonsan.hyakunin.ui.components.WashiPanel
import io.github.abonsan.hyakunin.ui.components.fadingBottomEdge
import io.github.abonsan.hyakunin.ui.components.isWideLayout
import io.github.abonsan.hyakunin.ui.match.Hidden
import io.github.abonsan.hyakunin.ui.match.Revealed
import io.github.abonsan.hyakunin.ui.match.TakeMarks
import io.github.abonsan.hyakunin.ui.match.VersePanel
import io.github.abonsan.hyakunin.ui.match.rememberKanaFadeIn
import io.github.abonsan.hyakunin.ui.match.rememberVerse
import io.github.abonsan.hyakunin.ui.match.spanStyle
import io.github.abonsan.hyakunin.ui.match.toneOf
import io.github.abonsan.hyakunin.ui.theme.KimarijiGreen
import io.github.abonsan.hyakunin.ui.theme.Shu
import io.github.abonsan.hyakunin.ui.theme.ShuDeep
import io.github.abonsan.hyakunin.ui.theme.Sumi
import io.github.abonsan.hyakunin.ui.theme.UsuzumiText
import io.github.abonsan.hyakunin.ui.theme.Washi

/** 上の句を初めから読み直すボタンの文字。 */
internal const val REPLAY_LABEL = "もう一度読む"

/** 答えた後に次の問題へ進むボタンの文字。最後の問題では [SEE_RESULT_LABEL]。 */
internal const val NEXT_LABEL = "次へ"

/** 最後の問題に答えた後に、結果を出すボタンの文字。 */
internal const val SEE_RESULT_LABEL = "結果を見る"

/** 正解したときの知らせ。 */
internal const val CORRECT_VERDICT = "○ 正解"

/** 間違えたときの知らせ。 */
internal const val WRONG_VERDICT = "× 不正解"

/** 答えた後に、正解の札に添える文字。 */
internal const val ANSWER_CARD_LABEL = "正解"

/** 答えた後に、選んだ誤答の札に添える文字。 */
internal const val CHOSEN_CARD_LABEL = "選んだ札"

/** 結果の画面で、間違えた札だけをもう一度出すボタンの文字。 */
internal const val RETRY_MISSED_LABEL = "間違えた札をもう一度"

/** 結果の画面で、同じ札をもう一度出すボタンの文字。 */
internal const val RETRY_LABEL = "もう一度"

/** 結果の画面で、練習を終えるボタンの文字。 */
internal const val FINISH_LABEL = "終わる"

/** 答える前の句の欄の見出し。 */
private const val KAMI_TITLE = "上の句"

/** 何問目か（例:「第 3 問 ／ 全 20 問」）。 */
internal fun questionCounterText(number: Int, total: Int) = "第 $number 問 ／ 全 $total 問"

/** 正答数（例:「20問中 15問正解」）。 */
internal fun scoreText(correct: Int, total: Int) = "${total}問中 ${correct}問正解"

/** 歌番号と作者（例:「18番　藤原敏行朝臣」）。 */
internal fun poemCaption(poem: Poem) = "${poem.no}番　${poem.author}"

/** 決まり字の知らせ（例:「決まり字「すみ」」）。[kimariji] は表示の仮名遣いの文字。 */
internal fun kimarijiNote(kimariji: String) = "決まり字「$kimariji」"

/** 取り札の幅の上限（大きな画面で札が大きくなりすぎないように）。 */
private val ChoiceCardMaxWidth = 120.dp

/** 札と札の間。 */
private val ChoiceCardGap = 10.dp

/**
 * 「札を覚える」の画面。表示は [state] だけから決める。
 *
 * 答える前は上の句を読んだところまで書き、4枚の取り札のどれかに触れると [onChoose] で答える。答えた後は歌全体を
 * 決まり字（緑）・答えたときに読まれていた部分（赤）・読まれていなかった部分（薄墨）に分けて書き、正解の札を金の縁にする。
 * 縦長の画面では上に句、下に札（指の届きやすいところ）を置き、横に広い画面では左に句、右に札を置く。
 * すべて答え終えたら、正答数と間違えた札の一覧を出す。
 *
 * @param verseStyle 句の仮名遣い（設定の「句の表示」）
 * @param onBack 戻るボタン（答えている途中なら、呼び出し側がやめるかを確かめる）
 * @param onExit 結果の画面の「終わる」
 */
@Composable
fun QuizScreen(
    state: QuizUiState,
    verseStyle: VerseStyle,
    onChoose: (Int) -> Unit,
    onReplay: () -> Unit,
    onNext: () -> Unit,
    onRestart: () -> Unit,
    onRestartMissed: () -> Unit,
    onBack: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Washi),
    ) {
        val wide = isWideLayout(maxWidth, maxHeight)
        if (state.phase == QuizPhase.Finished) {
            QuizResult(state, wide, onRestart, onRestartMissed, onExit)
            return@BoxWithConstraints
        }
        val header = @Composable {
            ScreenHeader(
                title = questionCounterText(state.index + 1, state.questions.size),
                caption = QUIZ_TITLE,
                onBack = onBack,
            )
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
                ) {
                    header()
                    QuizVerse(state, verseStyle, Modifier.weight(1f).fillMaxWidth())
                }
                CenteredScrollColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
                        .padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                ) {
                    ChoiceCards(state, onChoose)
                    Spacer(Modifier.height(12.dp))
                    AnswerBar(state, verseStyle, onReplay, onNext)
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                header()
                QuizVerse(state, verseStyle, Modifier.weight(1f).fillMaxWidth())
                AnswerBar(state, verseStyle, onReplay, onNext, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                ChoiceCards(state, onChoose, Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 14.dp))
            }
        }
    }
}

/**
 * 句の欄。答える前は上の句を、読んだ文字まで浮かび上がらせて書く（まだ読んでいない文字は場所だけ取る）。
 * 答えた後は歌全体を、決まり字・答えたときに読まれていた部分・読まれていなかった部分に分けて書く。
 */
@Composable
private fun QuizVerse(state: QuizUiState, verseStyle: VerseStyle, modifier: Modifier) {
    val question = state.question
    val verse = rememberVerse(question.poem, verseStyle)
    val phase = state.phase
    if (phase is QuizPhase.Answered) {
        val marks = TakeMarks(
            kimariji = verse.displayCount(question.kimarijiLength),
            taken = verse.displayCount(phase.revealedAtAnswer),
        )
        VersePanel(
            title = poemCaption(question.poem),
            verse = verse,
            spanFor = { marks.toneOf(it).spanStyle() },
            fade = null,
            description = verse.text,
            notice = null,
            modifier = modifier,
            noticeSlot = false,
        )
    } else {
        val kami = remember(verse) { verse.kamiOnly() }
        val revealed = verse.displayCount(state.revealedKana)
        VersePanel(
            title = KAMI_TITLE,
            verse = kami,
            spanFor = { index -> if (index < revealed) Revealed else Hidden },
            fade = rememberKanaFadeIn(state.readId, revealed),
            // 読んだ部分（表示している文字）だけを読み上げ機能に渡す
            description = kami.text.take(revealed),
            notice = null,
            modifier = modifier,
            noticeSlot = false,
        )
    }
}

/** 答える前は「もう一度読む」、答えた後は正誤・決まり字と「次へ」（最後の問題では「結果を見る」）。 */
@Composable
private fun AnswerBar(
    state: QuizUiState,
    verseStyle: VerseStyle,
    onReplay: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .widthIn(max = 560.dp)
            .heightIn(min = 56.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        when (val phase = state.phase) {
            is QuizPhase.Answered -> {
                val verse = rememberVerse(state.question.poem, verseStyle)
                Column(
                    Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                ) {
                    Text(
                        text = if (phase.correct) CORRECT_VERDICT else WRONG_VERDICT,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (phase.correct) KimarijiGreen else ShuDeep,
                        letterSpacing = 0.06.em,
                    )
                    Text(
                        text = kimarijiNote(verse.text.take(verse.displayCount(state.question.kimarijiLength))),
                        style = MaterialTheme.typography.bodySmall,
                        color = UsuzumiText,
                    )
                }
                Spacer(Modifier.width(12.dp))
                PrimaryButton(
                    text = if (state.isLast) SEE_RESULT_LABEL else NEXT_LABEL,
                    onClick = onNext,
                    minHeight = 48.dp,
                )
            }
            else -> SecondaryButton(
                text = REPLAY_LABEL,
                onClick = onReplay,
                icon = R.drawable.ic_play,
                minHeight = 44.dp,
            )
        }
    }
}

/**
 * 4枚の取り札。答える前は触れると答える。答えた後は正解の札を金の縁にして「正解」と添え、ほかの札は暗くする
 * （選んだ誤答には「選んだ札」と添える）。添える文字の場所は、答える前から空けておく（札が動かないように）。
 */
@Composable
private fun ChoiceCards(state: QuizUiState, onChoose: (Int) -> Unit, modifier: Modifier = Modifier) {
    val question = state.question
    val answered = state.phase as? QuizPhase.Answered
    Row(
        modifier
            .fillMaxWidth()
            .widthIn(max = ChoiceCardMaxWidth * 4 + ChoiceCardGap * 3),
        horizontalArrangement = Arrangement.spacedBy(ChoiceCardGap),
    ) {
        question.choices.forEach { poem ->
            val isAnswer = poem.no == question.poem.no
            val label = when {
                answered == null -> null
                isAnswer -> ANSWER_CARD_LABEL
                answered.chosenNo == poem.no -> CHOSEN_CARD_LABEL
                else -> null
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Torifuda(
                    shimoKana = poem.shimoKana,
                    modifier = Modifier.fillMaxWidth(),
                    isNew = answered != null && isAnswer,
                    dimmed = answered != null && !isAnswer,
                    onTap = if (answered == null) {
                        { onChoose(poem.no) }
                    } else {
                        null
                    },
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = label ?: "",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = when (label) {
                        ANSWER_CARD_LABEL -> KimarijiGreen
                        CHOSEN_CARD_LABEL -> Shu
                        else -> Color.Transparent
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * すべて答え終えた後の結果。正答数と間違えた札の一覧、「間違えた札をもう一度」（間違えた札があるときだけ）・
 * 「もう一度」・「終わる」。横に広い画面では、左に正答数とボタン、右に一覧を置く。一覧は長ければスクロールする。
 */
@Composable
private fun QuizResult(
    state: QuizUiState,
    wide: Boolean,
    onRestart: () -> Unit,
    onRestartMissed: () -> Unit,
    onExit: () -> Unit,
) {
    val header = @Composable { ScreenHeader(title = "結果", caption = QUIZ_TITLE, onBack = onExit) }
    val summary = @Composable { modifier: Modifier -> ScoreSummary(state, modifier) }
    val buttons = @Composable { modifier: Modifier -> ResultButtons(state.missed.isNotEmpty(), onRestart, onRestartMissed, onExit, modifier) }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .weight(0.42f)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
            ) {
                header()
                CenteredScrollColumn(Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    summary(Modifier.fillMaxWidth())
                }
                buttons(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 12.dp))
            }
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .weight(0.58f)
                    .fillMaxHeight()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
                    .fadingBottomEdge(scroll, Washi)
                    .verticalScroll(scroll)
                    .padding(start = 8.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
            ) {
                MissedList(state.missed)
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            header()
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .weight(1f)
                    .widthIn(max = 560.dp)
                    .fadingBottomEdge(scroll, Washi)
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp),
            ) {
                summary(Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                MissedList(state.missed)
            }
            buttons(
                Modifier
                    .widthIn(max = 560.dp)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            )
        }
    }
}

/** 正答数と、ひとこと。 */
@Composable
private fun ScoreSummary(state: QuizUiState, modifier: Modifier) {
    WashiPanel(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp)
                .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("練習終了", style = MaterialTheme.typography.labelLarge, color = UsuzumiText, letterSpacing = 0.3.em)
            Spacer(Modifier.height(10.dp))
            Text(
                text = scoreText(state.correctCount, state.questions.size),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Sumi,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (state.missed.isEmpty()) {
                    "すべて正解です"
                } else {
                    "間違えた札は「$MISSED_ONLY_LABEL」で復習できます"
                },
                style = MaterialTheme.typography.bodySmall,
                color = UsuzumiText,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 間違えた札の一覧（答えた順）。小さな取り札と、歌番号・作者・歌。間違えた札がなければ何も出さない。 */
@Composable
private fun ColumnScope.MissedList(missed: List<Poem>) {
    if (missed.isEmpty()) return
    SectionLabel("間違えた札")
    Spacer(Modifier.height(8.dp))
    missed.forEach { poem ->
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Torifuda(shimoKana = poem.shimoKana, modifier = Modifier.width(40.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(poemCaption(poem), style = MaterialTheme.typography.labelMedium, color = UsuzumiText)
                Text(poem.kami, style = MaterialTheme.typography.bodyMedium, color = Sumi)
                Text(poem.shimo, style = MaterialTheme.typography.bodyMedium, color = Sumi)
            }
        }
    }
}

/** 結果の画面のボタン。間違えた札があれば「間違えた札をもう一度」を主にし、なければ「もう一度」を主にする。 */
@Composable
private fun ResultButtons(
    hasMissed: Boolean,
    onRestart: () -> Unit,
    onRestartMissed: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PrimaryButton(
            text = if (hasMissed) RETRY_MISSED_LABEL else RETRY_LABEL,
            onClick = if (hasMissed) onRestartMissed else onRestart,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (hasMissed) {
                SecondaryButton(RETRY_LABEL, onRestart, Modifier.weight(1f), minHeight = 48.dp)
            }
            SecondaryButton(FINISH_LABEL, onExit, Modifier.weight(1f), minHeight = 48.dp)
        }
    }
}
