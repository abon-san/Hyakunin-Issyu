package io.github.abonsan.hyakunin.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.abonsan.hyakunin.ui.theme.Gofun
import io.github.abonsan.hyakunin.ui.theme.Kincha
import io.github.abonsan.hyakunin.ui.theme.Kon
import io.github.abonsan.hyakunin.ui.theme.Shu
import io.github.abonsan.hyakunin.ui.theme.Sumi
import io.github.abonsan.hyakunin.ui.theme.Usuzumi
import io.github.abonsan.hyakunin.ui.theme.UsuzumiText
import io.github.abonsan.hyakunin.ui.theme.WashiDeep
import io.github.abonsan.hyakunin.ui.theme.WashiLight

/** 朱の主ボタン。 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    minHeight: Dp = 52.dp,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = minHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.buttonColors(
            containerColor = Shu,
            contentColor = Gofun,
            disabledContainerColor = WashiDeep,
            disabledContentColor = UsuzumiText,
        ),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = if (minHeight < 52.dp) 8.dp else 12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, letterSpacing = 0.12.em)
    }
}

/** 金茶の枠の控えめなボタン。 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Sumi),
        border = BorderStroke(1.dp, Kincha),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, letterSpacing = 0.12.em)
    }
}

/** 朱の短い縦線を添えた見出し。[trailing] は右端に置く。 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 3.dp, height = 16.dp)
                .background(Shu),
        )
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = Sumi)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** 横に並べた選択肢から1つを選ぶ。選択中は紺地に胡粉の文字。 */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = MaterialTheme.shapes.medium
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .clip(shape)
            .border(1.dp, Kincha, shape)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, option ->
            if (index > 0) VerticalDivider(color = Kincha.copy(alpha = 0.6f))
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (isSelected) Kon else Color.Transparent)
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = if (isSelected) Gofun else Sumi,
                )
            }
        }
    }
}

/** 丸い枠のアイコンボタン（増減ボタンなど）。 */
@Composable
fun RoundIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(CircleShape)
            .border(1.dp, if (enabled) Kincha else WashiDeep, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = contentDescription,
            tint = if (enabled) Sumi else Usuzumi.copy(alpha = 0.5f),
            modifier = Modifier.size(20.dp),
        )
    }
}

/** 左右に金茶の飾り罫を付けた小見出し（例:「第 3 首 ／ 全 20 首」）。 */
@Composable
fun OrnamentTitle(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    color: Color = UsuzumiText,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OrnamentLine(Modifier.weight(1f), towardEnd = true)
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp),
            style = style,
            color = color,
        )
        OrnamentLine(Modifier.weight(1f), towardEnd = false)
    }
}

/** 文字に近い側に菱形を置き、遠い側へ薄くなる罫線。 */
@Composable
private fun OrnamentLine(modifier: Modifier, towardEnd: Boolean) {
    Canvas(modifier.height(8.dp)) {
        val y = size.height / 2
        val diamond = 3.dp.toPx()
        val innerX = if (towardEnd) size.width - diamond else diamond
        val outerX = if (towardEnd) 0f else size.width
        drawLine(
            brush = Brush.horizontalGradient(
                listOf(Kincha.copy(alpha = 0f), Kincha),
                startX = outerX,
                endX = innerX,
            ),
            start = Offset(outerX, y),
            end = Offset(innerX, y),
            strokeWidth = 1.dp.toPx(),
        )
        val path = Path().apply {
            moveTo(innerX, y - diamond)
            lineTo(innerX + diamond, y)
            lineTo(innerX, y + diamond)
            lineTo(innerX - diamond, y)
            close()
        }
        drawPath(path, Kincha)
    }
}

/**
 * 明るい和紙の面に金茶の細い枠を付けた板。対戦画面の中央（読み上げ・結果）に使う。
 */
@Composable
fun WashiPanel(
    modifier: Modifier = Modifier,
    shadowElevation: Dp = 0.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = WashiLight,
        contentColor = Sumi,
        border = BorderStroke(1.dp, Kincha.copy(alpha = 0.6f)),
        shadowElevation = shadowElevation,
    ) {
        Box(Modifier.washiFibers()) { content() }
    }
}

/** 和紙の面と金茶の枠のダイアログ。幅は画面の端から少し空け、広い画面では上限で止める。 */
@Composable
fun WafuDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    dismissLabel: String,
    onDismiss: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 28.dp)
                .widthIn(max = 400.dp)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = WashiLight,
            contentColor = Sumi,
            border = BorderStroke(1.dp, Kincha),
            shadowElevation = 8.dp,
        ) {
            Column(Modifier.padding(horizontal = 24.dp, vertical = 22.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium, color = UsuzumiText)
                Spacer(Modifier.height(22.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                ) {
                    SecondaryButton(dismissLabel, onDismiss)
                    PrimaryButton(confirmLabel, onConfirm)
                }
            }
        }
    }
}
