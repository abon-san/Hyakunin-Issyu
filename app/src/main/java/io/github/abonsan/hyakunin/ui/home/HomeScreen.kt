package io.github.abonsan.hyakunin.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.abonsan.hyakunin.R
import io.github.abonsan.hyakunin.data.Poems
import io.github.abonsan.hyakunin.game.CpuLevel
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.ReadingSpeed
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.ui.card.Torifuda
import io.github.abonsan.hyakunin.ui.components.ChoiceRow
import io.github.abonsan.hyakunin.ui.components.OrnamentTitle
import io.github.abonsan.hyakunin.ui.components.PrimaryButton
import io.github.abonsan.hyakunin.ui.components.RoundIconButton
import io.github.abonsan.hyakunin.ui.components.SectionLabel
import io.github.abonsan.hyakunin.ui.components.drawSeigaiha
import io.github.abonsan.hyakunin.ui.components.fadingBottomEdge
import io.github.abonsan.hyakunin.ui.theme.Gofun
import io.github.abonsan.hyakunin.ui.theme.Kincha
import io.github.abonsan.hyakunin.ui.theme.Kon
import io.github.abonsan.hyakunin.ui.theme.Shu
import io.github.abonsan.hyakunin.ui.theme.Sumi
import io.github.abonsan.hyakunin.ui.theme.Usuzumi
import io.github.abonsan.hyakunin.ui.theme.UsuzumiText
import io.github.abonsan.hyakunin.ui.theme.Washi
import io.github.abonsan.hyakunin.ui.theme.WashiDeep
import io.github.abonsan.hyakunin.ui.theme.WashiLight
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * ホーム画面（対戦設定）。設定の保存は呼び出し側が [onConfigChange] で行う。
 */
@Composable
fun HomeScreen(
    config: GameConfig,
    onConfigChange: (GameConfig) -> Unit,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier
            .fillMaxSize()
            .background(Washi)
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
            ),
    ) {
        val scroll = rememberScrollState()
        Column(
            Modifier
                .weight(1f)
                .fadingBottomEdge(scroll, Washi)
                .verticalScroll(scroll),
        ) {
            HomeHeader(Modifier.fillMaxWidth())
            SettingsPanel(
                config = config,
                onConfigChange = onConfigChange,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(12.dp))
        }
        PrimaryButton(
            text = "対戦開始",
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .height(56.dp),
        )
    }
}

@Composable
private fun HomeHeader(modifier: Modifier = Modifier) {
    Column(
        modifier
            // 波の輪が下の設定欄へはみ出さないように切る
            .clipToBounds()
            .drawBehind {
                drawSeigaiha(
                    color = Kincha.copy(alpha = 0.22f),
                    background = Washi,
                    radius = 22.dp.toPx(),
                    strokeWidth = 1.dp.toPx(),
                )
                // 下へ向かって地の色に溶かす
                drawRect(
                    Brush.verticalGradient(
                        0f to Washi.copy(alpha = 0.35f),
                        0.75f to Washi,
                    ),
                )
            }
            .statusBarsPadding()
            .padding(top = 20.dp, bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FannedCards()
        Spacer(Modifier.height(16.dp))
        Text(
            text = "百人一首",
            // 字間の分だけ左に余白を足して中央に見せる
            modifier = Modifier.padding(start = 10.dp),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            fontSize = 40.sp,
            letterSpacing = 0.25.em,
            color = Sumi,
        )
        Spacer(Modifier.height(6.dp))
        OrnamentTitle(
            text = "かるた取り　コンピュータと対戦",
            modifier = Modifier.padding(horizontal = 36.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 扇のように重ねた3枚の取り札。 */
@Composable
private fun FannedCards(modifier: Modifier = Modifier) {
    val cards = Poems.all.take(3)
    Box(modifier.size(width = 190.dp, height = 96.dp), contentAlignment = Alignment.Center) {
        cards.forEachIndexed { index, poem ->
            val position = index - (cards.size - 1) / 2f
            Torifuda(
                shimoKana = poem.shimoKana,
                modifier = Modifier
                    .offset(x = (position * 44).dp, y = (abs(position) * 6).dp)
                    .rotate(position * 11f)
                    .width(60.dp),
            )
        }
    }
}

@Composable
private fun SettingsPanel(
    config: GameConfig,
    onConfigChange: (GameConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = WashiLight,
        border = BorderStroke(1.dp, Kincha.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
            SettingBlock {
                SectionLabel("ルール")
                Spacer(Modifier.height(10.dp))
                ChoiceRow(
                    options = RuleMode.entries,
                    selected = config.ruleMode,
                    label = { it.label },
                    onSelect = { onConfigChange(config.copy(ruleMode = it)) },
                )
                Spacer(Modifier.height(8.dp))
                RuleDescription(config.ruleMode)
            }
            PanelDivider()
            EmptyCardSetting(
                count = config.emptyCardCount,
                onCountChange = { count ->
                    val value = count.coerceIn(0, GameConfig.MAX_EMPTY_CARDS)
                    if (value != config.emptyCardCount) onConfigChange(config.copy(emptyCardCount = value))
                },
            )
            PanelDivider()
            SettingBlock {
                SectionLabel("CPUの強さ")
                Spacer(Modifier.height(10.dp))
                ChoiceRow(
                    options = CpuLevel.entries,
                    selected = config.cpuLevel,
                    label = { it.label },
                    onSelect = { onConfigChange(config.copy(cpuLevel = it)) },
                )
            }
            PanelDivider()
            SettingBlock {
                SectionLabel("読み上げ速度")
                Spacer(Modifier.height(10.dp))
                ChoiceRow(
                    options = ReadingSpeed.entries,
                    selected = config.readingSpeed,
                    label = { it.label },
                    onSelect = { onConfigChange(config.copy(readingSpeed = it)) },
                )
            }
            PanelDivider()
            FlipSetting(
                checked = config.flipOpponentCards,
                onCheckedChange = { onConfigChange(config.copy(flipOpponentCards = it)) },
            )
        }
    }
}

/**
 * ルールの説明。どのルールを選んでも高さが変わらないよう、すべての説明を重ねて一番高いものに合わせ、
 * 選んでいないものは透明にする。
 */
@Composable
private fun RuleDescription(selected: RuleMode) {
    Box {
        RuleMode.entries.forEach { mode ->
            val visible = mode == selected
            Text(
                text = mode.description,
                modifier = Modifier
                    .alpha(if (visible) 1f else 0f)
                    .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
                style = MaterialTheme.typography.bodySmall,
                color = UsuzumiText,
            )
        }
    }
}

@Composable
private fun SettingBlock(content: @Composable () -> Unit) {
    Column(Modifier.padding(vertical = 14.dp)) { content() }
}

@Composable
private fun PanelDivider() {
    HorizontalDivider(thickness = 1.dp, color = Kincha.copy(alpha = 0.3f))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyCardSetting(count: Int, onCountChange: (Int) -> Unit) {
    SettingBlock {
        SectionLabel("空札の枚数") {
            Row {
                Text(
                    text = "$count",
                    modifier = Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = Sumi,
                )
                Text(
                    text = " 枚",
                    modifier = Modifier.alignByBaseline(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Sumi,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(
                icon = R.drawable.ic_minus,
                contentDescription = "空札を1枚減らす",
                onClick = { onCountChange(count - 1) },
                enabled = count > 0,
            )
            Slider(
                value = count.toFloat(),
                onValueChange = { onCountChange(it.roundToInt()) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
                    .semantics { contentDescription = "空札の枚数" },
                valueRange = 0f..GameConfig.MAX_EMPTY_CARDS.toFloat(),
                thumb = { SliderKnob() },
                track = { SliderLine(it) },
            )
            RoundIconButton(
                icon = R.drawable.ic_plus,
                contentDescription = "空札を1枚増やす",
                onClick = { onCountChange(count + 1) },
                enabled = count < GameConfig.MAX_EMPTY_CARDS,
            )
        }
        val fieldCards = GameConfig.CARDS_PER_SIDE * 2
        Text(
            text = "読まれる札は全 ${fieldCards + count} 首（場の札 $fieldCards 枚と空札 $count 枚）",
            style = MaterialTheme.typography.bodySmall,
            color = UsuzumiText,
        )
    }
}

/** スライダーのつまみ。朱の珠に胡粉の縁。 */
@Composable
private fun SliderKnob() {
    Box(
        Modifier
            .size(26.dp)
            .shadow(2.dp, CircleShape)
            .background(Shu, CircleShape)
            .border(2.5.dp, Gofun, CircleShape),
    )
}

/** スライダーの溝。細い線で、選んだところまでを朱にする。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderLine(state: SliderState) {
    val range = state.valueRange
    val span = range.endInclusive - range.start
    val fraction = if (span > 0f) ((state.value - range.start) / span).coerceIn(0f, 1f) else 0f
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
        drawLine(Shu, Offset(startX, y), Offset(activeX, y), stroke, StrokeCap.Round)
    }
}

@Composable
private fun FlipSetting(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("敵陣の札を逆さに表示", style = MaterialTheme.typography.titleSmall, color = Sumi)
            Spacer(Modifier.height(2.dp))
            Text(
                text = "相手から見た向きで敵陣の札を並べます",
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
