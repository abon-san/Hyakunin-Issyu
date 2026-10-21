package io.github.abonsan.hyakunin.ui.quiz

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.abonsan.hyakunin.game.quiz.KimarijiGroup
import io.github.abonsan.hyakunin.game.quiz.PoemNumberRange
import io.github.abonsan.hyakunin.game.quiz.QuizConfig
import io.github.abonsan.hyakunin.ui.components.ChoiceRow
import io.github.abonsan.hyakunin.ui.components.PanelDivider
import io.github.abonsan.hyakunin.ui.components.PrimaryButton
import io.github.abonsan.hyakunin.ui.components.ScreenHeader
import io.github.abonsan.hyakunin.ui.components.SectionLabel
import io.github.abonsan.hyakunin.ui.components.SettingBlock
import io.github.abonsan.hyakunin.ui.components.SettingSpacing
import io.github.abonsan.hyakunin.ui.components.SettingsCard
import io.github.abonsan.hyakunin.ui.components.SwitchSetting
import io.github.abonsan.hyakunin.ui.components.fadingBottomEdge
import io.github.abonsan.hyakunin.ui.components.isWideLayout
import io.github.abonsan.hyakunin.ui.theme.Sumi
import io.github.abonsan.hyakunin.ui.theme.UsuzumiText
import io.github.abonsan.hyakunin.ui.theme.Washi

/** 「札を覚える」の画面の見出し。 */
internal const val QUIZ_TITLE = "札を覚える"

/** 練習を始めるボタンの文字。 */
internal const val QUIZ_START_LABEL = "練習開始"

/** 練習のしかたの説明。 */
internal const val QUIZ_DESCRIPTION = "上の句の読み上げを聞いて、下の句の取り札を4枚から選びます。範囲の札を1回ずつ出します。"

/** 「間違えた札だけ」の見出し。 */
internal const val MISSED_ONLY_LABEL = "間違えた札だけ"

/** 出題する札の数の表示。 */
internal fun quizCountText(count: Int): String = if (count > 0) "出題する札：${count}首" else "条件に合う札がありません"

/** 間違えた札の記録の説明。[count] は記録している札の数。 */
internal fun missedDescription(count: Int): String =
    if (count > 0) "これまでに間違えて、まだ正解していない札（${count}首）から出します" else "間違えた札はまだありません"

/** 歌番号の選択肢を並べる段（1段に3つ）。狭い画面でも「81〜100」が小さくなりすぎないように2段にする。 */
private val NumberRangeRows = PoemNumberRange.entries.chunked(3)

/** 横に広い画面で、見出しと「練習開始」の欄が占める割合。 */
private const val WIDE_SIDE_WEIGHT = 0.36f

/**
 * 「札を覚える」の設定の画面。歌番号・決まり字・間違えた札だけかで出題範囲を選び、「練習開始」で始める。
 * 範囲の保存は呼び出し側が [onConfigChange] で行う。範囲に合う札がない（[poemCount] が 0）ときは始められない。
 * 横に広い画面では、左に見出しと「練習開始」、右に設定を置く。低い画面ではスクロールする。
 *
 * @param missedCount 間違えた札として記録している札の数
 * @param poemCount 今の範囲で出題する札の数
 */
@Composable
fun QuizSetupScreen(
    config: QuizConfig,
    missedCount: Int,
    poemCount: Int,
    onConfigChange: (QuizConfig) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Washi),
    ) {
        val wide = isWideLayout(maxWidth, maxHeight)
        val spacing = if (wide) SettingSpacing.Dense else SettingSpacing.Regular
        val settings: @Composable ColumnScope.() -> Unit = {
            QuizRangeSettings(config, missedCount, onConfigChange, spacing)
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .weight(WIDE_SIDE_WEIGHT)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
                ) {
                    ScreenHeader(title = QUIZ_TITLE, onBack = onBack)
                    val scroll = rememberScrollState()
                    Column(
                        Modifier
                            .weight(1f)
                            .fadingBottomEdge(scroll, Washi)
                            .verticalScroll(scroll)
                            .padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 12.dp),
                    ) {
                        Description()
                    }
                    StartArea(poemCount, onStart, Modifier.padding(start = 16.dp, end = 8.dp, bottom = 12.dp))
                }
                val scroll = rememberScrollState()
                Column(
                    Modifier
                        .weight(1f - WIDE_SIDE_WEIGHT)
                        .fillMaxHeight()
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
                        .fadingBottomEdge(scroll, Washi)
                        .verticalScroll(scroll)
                        .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                ) {
                    SettingsCard(content = settings)
                }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ScreenHeader(title = QUIZ_TITLE, onBack = onBack)
                val scroll = rememberScrollState()
                Column(
                    Modifier
                        .weight(1f)
                        .widthIn(max = 560.dp)
                        .fadingBottomEdge(scroll, Washi)
                        .verticalScroll(scroll)
                        .padding(horizontal = 20.dp),
                ) {
                    Description(Modifier.padding(start = 4.dp, top = 2.dp, bottom = 14.dp))
                    SettingsCard(content = settings)
                    Spacer(Modifier.height(12.dp))
                }
                StartArea(
                    poemCount = poemCount,
                    onStart = onStart,
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun Description(modifier: Modifier = Modifier) {
    Text(text = QUIZ_DESCRIPTION, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = UsuzumiText)
}

/** 出題する札の数と「練習開始」。札がなければ押せない。 */
@Composable
private fun StartArea(poemCount: Int, onStart: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = quizCountText(poemCount),
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            style = MaterialTheme.typography.bodyMedium,
            color = if (poemCount > 0) Sumi else UsuzumiText,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        PrimaryButton(
            text = QUIZ_START_LABEL,
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = poemCount > 0,
        )
    }
}

/** 歌番号・決まり字・間違えた札だけか。 */
@Composable
private fun QuizRangeSettings(
    config: QuizConfig,
    missedCount: Int,
    onConfigChange: (QuizConfig) -> Unit,
    spacing: SettingSpacing,
) {
    SettingBlock(spacing = spacing) {
        SectionLabel("歌番号")
        Spacer(Modifier.height(spacing.label))
        NumberRangeRows.forEachIndexed { index, options ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            ChoiceRow(
                options = options,
                selected = config.numberRange,
                label = { it.label },
                onSelect = { onConfigChange(config.copy(numberRange = it)) },
            )
        }
    }
    PanelDivider()
    SettingBlock(spacing = spacing) {
        SectionLabel("決まり字")
        Spacer(Modifier.height(spacing.label))
        ChoiceRow(
            options = KimarijiGroup.entries,
            selected = config.kimarijiGroup,
            label = { it.label },
            onSelect = { onConfigChange(config.copy(kimarijiGroup = it)) },
        )
    }
    PanelDivider()
    SwitchSetting(
        title = MISSED_ONLY_LABEL,
        description = missedDescription(missedCount),
        checked = config.missedOnly,
        onCheckedChange = { onConfigChange(config.copy(missedOnly = it)) },
        spacing = spacing,
    )
}
