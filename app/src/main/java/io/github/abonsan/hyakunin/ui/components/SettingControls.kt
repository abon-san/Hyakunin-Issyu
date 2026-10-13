package io.github.abonsan.hyakunin.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.abonsan.hyakunin.ui.theme.Gofun
import io.github.abonsan.hyakunin.ui.theme.Kincha
import io.github.abonsan.hyakunin.ui.theme.Kon
import io.github.abonsan.hyakunin.ui.theme.Shu
import io.github.abonsan.hyakunin.ui.theme.Sumi
import io.github.abonsan.hyakunin.ui.theme.Usuzumi
import io.github.abonsan.hyakunin.ui.theme.UsuzumiText
import io.github.abonsan.hyakunin.ui.theme.WashiDeep
import io.github.abonsan.hyakunin.ui.theme.WashiLight

/** 設定の項目を並べる、明るい和紙の板。 */
@Composable
fun SettingsCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = WashiLight,
        border = BorderStroke(1.dp, Kincha.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp), content = content)
    }
}

/**
 * 設定の項目の詰め具合。[block] は項目の上下の余白、[label] は見出しと選択肢の間、[row] は選択肢の高さ。
 */
enum class SettingSpacing(val block: Dp, val label: Dp, val row: Dp) {
    /** 縦長の画面。 */
    Regular(block = 14.dp, label = 10.dp, row = 46.dp),

    /** 横に広い画面（高さが限られる）。 */
    Dense(block = 10.dp, label = 8.dp, row = 44.dp),

    /** 横に広く、高さがさらに限られる画面（小さめのスマホの横画面など）。 */
    Compact(block = 6.dp, label = 6.dp, row = 44.dp),
}

/** 設定の1項目。上下の余白は [spacing] で詰める（横画面など高さが限られるとき）。 */
@Composable
fun SettingBlock(
    modifier: Modifier = Modifier,
    spacing: SettingSpacing = SettingSpacing.Regular,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.padding(vertical = spacing.block), content = content)
}

/** 設定の項目どうしの区切り線。 */
@Composable
fun PanelDivider() {
    HorizontalDivider(thickness = 1.dp, color = Kincha.copy(alpha = 0.3f))
}

/**
 * 選んでいる項目の説明。どれを選んでも高さが変わらないよう、すべての説明を重ねて一番高いものに合わせ、
 * 選んでいないものは透明にする。
 */
@Composable
fun <T> SelectedDescription(
    options: List<T>,
    selected: T,
    description: (T) -> String,
    modifier: Modifier = Modifier,
) {
    Box(modifier) {
        options.forEach { option ->
            val visible = option == selected
            Text(
                text = description(option),
                modifier = Modifier
                    .alpha(if (visible) 1f else 0f)
                    .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
                style = MaterialTheme.typography.bodySmall,
                color = UsuzumiText,
            )
        }
    }
}

/** 見出しと説明つきのスイッチ。行全体に触れて切り替えられる。 */
@Composable
fun SwitchSetting(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    spacing: SettingSpacing = SettingSpacing.Regular,
) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = spacing.block),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = Sumi)
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = UsuzumiText,
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Gofun,
                checkedTrackColor = Kon,
                checkedBorderColor = Kon,
                uncheckedThumbColor = Usuzumi,
                uncheckedTrackColor = WashiDeep,
                uncheckedBorderColor = Usuzumi,
            ),
        )
    }
}

/**
 * 朱の珠のつまみと細い溝のスライダー。選んだところまでの溝を朱にする。[steps] は両端の間の刻みの数（0 なら連続）。
 * [enabled] が false なら、つまみと溝を薄墨にして動かせなくする。読み上げ機能向けの説明は [modifier] の semantics で付ける。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WafuSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        valueRange = valueRange,
        steps = steps,
        thumb = { SliderKnob(enabled) },
        track = { SliderLine(it, enabled) },
    )
}

/** スライダーのつまみ。朱の珠に胡粉の縁（動かせないときは薄墨の珠）。 */
@Composable
private fun SliderKnob(enabled: Boolean) {
    Box(
        Modifier
            .size(26.dp)
            .then(if (enabled) Modifier.shadow(2.dp, CircleShape) else Modifier)
            .background(if (enabled) Shu else Usuzumi, CircleShape)
            .border(2.5.dp, Gofun, CircleShape),
    )
}

/** スライダーの溝。細い線で、選んだところまでを朱にする（動かせないときは薄墨）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderLine(state: SliderState, enabled: Boolean) {
    val range = state.valueRange
    val span = range.endInclusive - range.start
    val fraction = if (span > 0f) ((state.value - range.start) / span).coerceIn(0f, 1f) else 0f
    val active = if (enabled) Shu else Usuzumi.copy(alpha = 0.6f)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(6.dp),
    ) {
        val y = size.height / 2
        val stroke = 4.dp.toPx()
        val rtl = layoutDirection == LayoutDirection.Rtl
        val startX = if (rtl) size.width else 0f
        val endX = if (rtl) 0f else size.width
        val activeX = startX + (endX - startX) * fraction
        drawLine(WashiDeep, Offset(startX, y), Offset(endX, y), stroke, StrokeCap.Round)
        drawLine(active, Offset(startX, y), Offset(activeX, y), stroke, StrokeCap.Round)
    }
}
