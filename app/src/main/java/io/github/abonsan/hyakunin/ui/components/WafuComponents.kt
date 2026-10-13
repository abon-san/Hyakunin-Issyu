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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.abonsan.hyakunin.R
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
        ButtonLabel(text)
    }
}

/** 金茶の枠の控えめなボタン。[icon] を付けると文字の前に置く。[enabled] が false なら淡くして押せなくする。 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    minHeight: Dp = 52.dp,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = minHeight),
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = Sumi, disabledContentColor = Usuzumi),
        border = BorderStroke(1.dp, if (enabled) Kincha else Kincha.copy(alpha = 0.4f)),
        contentPadding = PaddingValues(
            start = if (icon != null) 18.dp else 24.dp,
            end = 24.dp,
            top = if (minHeight < 52.dp) 8.dp else 12.dp,
            bottom = if (minHeight < 52.dp) 8.dp else 12.dp,
        ),
    ) {
        if (icon != null) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (enabled) Sumi else Usuzumi,
            )
            Spacer(Modifier.width(8.dp))
        }
        ButtonLabel(text)
    }
}

/** ボタンの文字。折り返さず、入り切らないときは少し小さくして1行に収める。 */
@Composable
private fun ButtonLabel(text: String) {
    val style = MaterialTheme.typography.titleMedium
    Text(
        text = text,
        style = style,
        letterSpacing = 0.12.em,
        softWrap = false,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 12.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
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

/**
 * 横に並べた選択肢から1つを選ぶ。選択中は紺地に胡粉の文字。
 * 文字が欄に入り切らないときは、小さくして1行に収める。
 *
 * @param enabled false なら選べない（選んである項目は淡く示す）。
 */
@Composable
fun <T> ChoiceRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 46.dp,
) {
    val shape = MaterialTheme.shapes.medium
    val line = if (enabled) Kincha else Kincha.copy(alpha = 0.4f)
    val textStyle = MaterialTheme.typography.bodyLarge
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .border(1.dp, line, shape)
            .selectableGroup(),
    ) {
        options.forEachIndexed { index, option ->
            if (index > 0) VerticalDivider(color = line.copy(alpha = line.alpha * 0.6f))
            val isSelected = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(
                        when {
                            !isSelected -> Color.Transparent
                            enabled -> Kon
                            else -> WashiDeep
                        },
                    )
                    .selectable(
                        selected = isSelected,
                        enabled = enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(option) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    modifier = Modifier.padding(horizontal = 4.dp),
                    style = textStyle,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        isSelected && enabled -> Gofun
                        enabled -> Sumi
                        isSelected -> UsuzumiText
                        else -> Usuzumi
                    },
                    softWrap = false,
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 11.sp,
                        maxFontSize = textStyle.fontSize,
                        stepSize = 0.5.sp,
                    ),
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

/**
 * 戻るボタンと見出しの帯。上端はステータスバーの分だけ空ける。
 * [caption] は見出しの上に小さく添える（例:「対戦設定」）。
 */
@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 4.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = "戻る", tint = Sumi)
        }
        Spacer(Modifier.width(4.dp))
        Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.labelMedium, color = UsuzumiText)
            }
            Text(title, style = MaterialTheme.typography.titleLarge, color = Sumi, letterSpacing = 0.06.em)
        }
    }
}

/**
 * 左右に金茶の飾り罫を付けた小見出し（例:「第 3 首 ／ 全 20 首」）。
 * 狭い欄では飾り罫から縮め、それでも入らなければ文字を小さくして1行に収める。
 */
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
            softWrap = false,
            maxLines = 1,
            autoSize = if (style.fontSize.isSpecified) {
                TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp)
            } else {
                null
            },
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
                .padding(horizontal = 28.dp, vertical = 16.dp)
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
