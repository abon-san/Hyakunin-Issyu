package io.github.abonsan.hyakunin.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.abonsan.hyakunin.R
import io.github.abonsan.hyakunin.game.CpuLevel
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.MemorizeTime
import io.github.abonsan.hyakunin.game.ReadInterval
import io.github.abonsan.hyakunin.game.ReadStartMode
import io.github.abonsan.hyakunin.game.ReadingSpeed
import io.github.abonsan.hyakunin.game.ReadingStyle
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.ui.components.ChoiceRow
import io.github.abonsan.hyakunin.ui.components.PanelDivider
import io.github.abonsan.hyakunin.ui.components.PrimaryButton
import io.github.abonsan.hyakunin.ui.components.RoundIconButton
import io.github.abonsan.hyakunin.ui.components.ScreenHeader
import io.github.abonsan.hyakunin.ui.components.SectionLabel
import io.github.abonsan.hyakunin.ui.components.SelectedDescription
import io.github.abonsan.hyakunin.ui.components.SettingBlock
import io.github.abonsan.hyakunin.ui.components.SettingSpacing
import io.github.abonsan.hyakunin.ui.components.SettingsCard
import io.github.abonsan.hyakunin.ui.components.WafuSlider
import io.github.abonsan.hyakunin.ui.components.fadingBottomEdge
import io.github.abonsan.hyakunin.ui.components.isWideLayout
import io.github.abonsan.hyakunin.ui.theme.Sumi
import io.github.abonsan.hyakunin.ui.theme.UsuzumiText
import io.github.abonsan.hyakunin.ui.theme.Washi
import io.github.abonsan.hyakunin.ui.theme.noLineBreak
import kotlin.math.roundToInt

/** 対戦設定の画面の見出し。 */
internal const val MATCH_SETUP_CAPTION = "対戦設定"

/**
 * 対戦設定の画面。[config] のモードで、空札・CPU・読み上げ・読み方・読み始めの設定を変えて対戦を始める。
 * 設定の保存は呼び出し側が [onConfigChange] で行う。
 * 横に広い画面では設定を2列で並べ、十分に広ければ左に見出しと「対戦開始」を置く
 * （広さが足りなければ、見出しと「対戦開始」を上の帯にまとめる）。
 */
@Composable
fun MatchSetupScreen(
    config: GameConfig,
    onConfigChange: (GameConfig) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Washi),
    ) {
        // 高さがさらに限られる横画面では、設定の項目をもう一段詰める
        val spacing = if (maxHeight < CompactSetupHeight) SettingSpacing.Compact else SettingSpacing.Dense
        when {
            !isWideLayout(maxWidth, maxHeight) -> NarrowSetup(config, onConfigChange, onStart, onBack)
            maxWidth >= SidePaneMinWidth -> WideSetup(config, onConfigChange, onStart, onBack, spacing)
            else -> TopBarSetup(config, onConfigChange, onStart, onBack, spacing)
        }
    }
}

/** 見出しと「対戦開始」を左の欄に置ける幅。 */
private val SidePaneMinWidth = 760.dp

/** 横に広い画面で、これより低ければ設定の項目を [SettingSpacing.Compact] に詰める。 */
private val CompactSetupHeight = 380.dp

@Composable
private fun NarrowSetup(
    config: GameConfig,
    onConfigChange: (GameConfig) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenHeader(title = config.ruleMode.modeName, caption = MATCH_SETUP_CAPTION, onBack = onBack)
        val scroll = rememberScrollState()
        Column(
            Modifier
                .weight(1f)
                .widthIn(max = 560.dp)
                .fadingBottomEdge(scroll, Washi)
                .verticalScroll(scroll)
                .padding(horizontal = 20.dp),
        ) {
            RuleSummary(config.ruleMode, Modifier.padding(start = 4.dp, top = 2.dp, bottom = 14.dp))
            SettingsCard { MatchSettings(config, onConfigChange, SettingSpacing.Regular) }
            Spacer(Modifier.height(14.dp))
            SettingsCard { ReadStartSettings(config, onConfigChange, SettingSpacing.Regular) }
            Spacer(Modifier.height(12.dp))
        }
        PrimaryButton(
            text = "対戦開始",
            onClick = onStart,
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
                .height(56.dp),
        )
    }
}

@Composable
private fun WideSetup(
    config: GameConfig,
    onConfigChange: (GameConfig) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
    spacing: SettingSpacing,
) {
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .weight(0.34f)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Start + WindowInsetsSides.Vertical)),
        ) {
            ScreenHeader(title = config.ruleMode.modeName, caption = MATCH_SETUP_CAPTION, onBack = onBack)
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .weight(1f)
                    .fadingBottomEdge(scroll, Washi)
                    .verticalScroll(scroll),
            ) {
                RuleSummary(config.ruleMode, Modifier.padding(start = 20.dp, end = 12.dp, top = 2.dp, bottom = 12.dp))
            }
            PrimaryButton(
                text = "対戦開始",
                onClick = onStart,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 8.dp, bottom = 12.dp)
                    .height(56.dp),
            )
        }
        val scroll = rememberScrollState()
        Row(
            Modifier
                .weight(0.66f)
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.End + WindowInsetsSides.Vertical))
                .fadingBottomEdge(scroll, Washi)
                .verticalScroll(scroll)
                .padding(start = 8.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard(Modifier.weight(1f)) { MatchSettings(config, onConfigChange, spacing) }
            SettingsCard(Modifier.weight(1f)) { ReadStartSettings(config, onConfigChange, spacing) }
        }
    }
}

/** 横に広いが幅に余裕のない画面: 上の帯に見出しと「対戦開始」、その下に設定を2列で並べる。 */
@Composable
private fun TopBarSetup(
    config: GameConfig,
    onConfigChange: (GameConfig) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
    spacing: SettingSpacing,
) {
    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScreenHeader(
                title = config.ruleMode.modeName,
                caption = MATCH_SETUP_CAPTION,
                onBack = onBack,
                modifier = Modifier.weight(1f),
            )
            PrimaryButton(
                text = "対戦開始",
                onClick = onStart,
                modifier = Modifier
                    .padding(end = 16.dp)
                    .widthIn(min = 180.dp),
                minHeight = 48.dp,
            )
        }
        val scroll = rememberScrollState()
        Row(
            Modifier
                .weight(1f)
                .fadingBottomEdge(scroll, Washi)
                .verticalScroll(scroll)
                .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsCard(Modifier.weight(1f)) { MatchSettings(config, onConfigChange, spacing) }
            SettingsCard(Modifier.weight(1f)) { ReadStartSettings(config, onConfigChange, spacing) }
        }
    }
}

/** ルールの短い説明。モードの色の縦線を添える。 */
@Composable
private fun RuleSummary(mode: RuleMode, modifier: Modifier = Modifier) {
    Row(modifier.height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(mode.accent),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = mode.description,
            style = MaterialTheme.typography.bodySmall,
            color = UsuzumiText,
        )
    }
}

/** 空札・CPU の強さ・読み上げ速度。 */
@Composable
private fun MatchSettings(config: GameConfig, onConfigChange: (GameConfig) -> Unit, spacing: SettingSpacing) {
    EmptyCardSetting(
        count = config.emptyCardCount,
        onCountChange = { count ->
            val value = count.coerceIn(0, GameConfig.MAX_EMPTY_CARDS)
            if (value != config.emptyCardCount) onConfigChange(config.copy(emptyCardCount = value))
        },
        spacing = spacing,
    )
    PanelDivider()
    ChoiceSetting(
        title = "CPUの強さ",
        options = CpuLevel.entries,
        selected = config.cpuLevel,
        label = { it.label },
        onSelect = { onConfigChange(config.copy(cpuLevel = it)) },
        spacing = spacing,
    )
    PanelDivider()
    ChoiceSetting(
        title = "読み上げ速度",
        options = ReadingSpeed.entries,
        selected = config.readingSpeed,
        label = { it.label },
        onSelect = { onConfigChange(config.copy(readingSpeed = it)) },
        spacing = spacing,
    )
}

/**
 * 読み方（序歌・前の歌の下の句を読むか）、読み始め方と、自動のときの待ち時間。
 * タップで読み始めるときは待ち時間を選べなくする。
 */
@Composable
private fun ReadStartSettings(config: GameConfig, onConfigChange: (GameConfig) -> Unit, spacing: SettingSpacing) {
    val auto = config.readStartMode == ReadStartMode.AUTO
    DescribedChoiceSetting(
        title = "読み方",
        options = ReadingStyle.entries,
        selected = config.readingStyle,
        label = { it.label },
        description = { it.setupDescription },
        onSelect = { onConfigChange(config.copy(readingStyle = it)) },
        spacing = spacing,
    )
    PanelDivider()
    DescribedChoiceSetting(
        title = "読み始め方",
        options = ReadStartMode.entries,
        selected = config.readStartMode,
        label = { it.label },
        description = { it.setupDescription },
        onSelect = { onConfigChange(config.copy(readStartMode = it)) },
        spacing = spacing,
    )
    PanelDivider()
    ChoiceSetting(
        title = "開始前の時間",
        caption = "札を覚える時間",
        options = MemorizeTime.entries,
        selected = config.memorizeTime,
        label = { it.label },
        onSelect = { onConfigChange(config.copy(memorizeTime = it)) },
        spacing = spacing,
        enabled = auto,
    )
    PanelDivider()
    ChoiceSetting(
        title = "読みの間",
        caption = "次の札を読むまで",
        options = ReadInterval.entries,
        selected = config.readInterval,
        label = { it.label },
        onSelect = { onConfigChange(config.copy(readInterval = it)) },
        spacing = spacing,
        enabled = auto,
    )
}

/** 対戦設定での読み始め方の説明。 */
internal val ReadStartMode.setupDescription: String
    get() = when (this) {
        ReadStartMode.AUTO -> "下の時間がたつと、自動で次の札を読みます"
        ReadStartMode.TAP -> "毎回「読み始める」を押すまで待ちます"
    }

/** 対戦設定での読み方の説明。句の名前の途中や文の終わりの短い部分では行を分けない。 */
internal val ReadingStyle.setupDescription: String
    get() = when (this) {
        ReadingStyle.COMPETITIVE ->
            "序歌から始め、" + noLineBreak("前の札の下の句") + "を読んでから" + noLineBreak("次の上の句を読みます")
        ReadingStyle.SIMPLE -> "上の句と下の句だけを読みます"
    }

/** 見出しと選択肢の列と、選んでいる項目の説明。 */
@Composable
private fun <T> DescribedChoiceSetting(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    description: (T) -> String,
    onSelect: (T) -> Unit,
    spacing: SettingSpacing,
) {
    SettingBlock(spacing = spacing) {
        SectionLabel(title)
        Spacer(Modifier.height(spacing.label))
        ChoiceRow(
            options = options,
            selected = selected,
            label = label,
            onSelect = onSelect,
            height = spacing.row,
        )
        Spacer(Modifier.height(if (spacing == SettingSpacing.Compact) 6.dp else 8.dp))
        SelectedDescription(options, selected, description)
    }
}

/** 見出しと選択肢の列。[caption] は見出しの右に小さく添える。 */
@Composable
private fun <T> ChoiceSetting(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    spacing: SettingSpacing,
    caption: String? = null,
    enabled: Boolean = true,
) {
    SettingBlock(spacing = spacing) {
        SectionLabel(
            text = title,
            modifier = Modifier.alpha(if (enabled) 1f else DISABLED_ALPHA),
            trailing = if (caption != null) {
                { Text(caption, style = MaterialTheme.typography.labelMedium, color = UsuzumiText) }
            } else {
                null
            },
        )
        Spacer(Modifier.height(spacing.label))
        ChoiceRow(
            options = options,
            selected = selected,
            label = label,
            onSelect = onSelect,
            enabled = enabled,
            height = spacing.row,
        )
    }
}

/** 選べない設定の見出しの濃さ。 */
private const val DISABLED_ALPHA = 0.5f

@Composable
private fun EmptyCardSetting(count: Int, onCountChange: (Int) -> Unit, spacing: SettingSpacing) {
    SettingBlock(spacing = spacing) {
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
            WafuSlider(
                value = count.toFloat(),
                onValueChange = { onCountChange(it.roundToInt()) },
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 6.dp)
                    .semantics { contentDescription = "空札の枚数" },
                valueRange = 0f..GameConfig.MAX_EMPTY_CARDS.toFloat(),
            )
            RoundIconButton(
                icon = R.drawable.ic_plus,
                contentDescription = "空札を1枚増やす",
                onClick = { onCountChange(count + 1) },
                enabled = count < GameConfig.MAX_EMPTY_CARDS,
            )
        }
        Text(
            text = readCountText(count, short = spacing == SettingSpacing.Compact),
            style = MaterialTheme.typography.bodySmall,
            color = UsuzumiText,
        )
    }
}

/**
 * 読まれる札の数の説明。[short] なら（高さが限られる画面）内訳を省いて1行に収める。
 * 内訳は行をまたいで分けない。
 */
internal fun readCountText(emptyCards: Int, short: Boolean): String {
    val fieldCards = GameConfig.CARDS_PER_SIDE * 2
    val total = "読まれる札は全 ${fieldCards + emptyCards} 首"
    return if (short) total else total + noLineBreak("（場の札 $fieldCards 枚と空札 $emptyCards 枚）")
}
