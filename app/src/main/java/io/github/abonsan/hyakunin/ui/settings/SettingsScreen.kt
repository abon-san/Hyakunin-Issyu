package io.github.abonsan.hyakunin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
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
import androidx.compose.ui.unit.dp
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.ui.components.ChoiceRow
import io.github.abonsan.hyakunin.ui.components.PanelDivider
import io.github.abonsan.hyakunin.ui.components.ScreenHeader
import io.github.abonsan.hyakunin.ui.components.SectionLabel
import io.github.abonsan.hyakunin.ui.components.SelectedDescription
import io.github.abonsan.hyakunin.ui.components.SettingBlock
import io.github.abonsan.hyakunin.ui.components.SettingSpacing
import io.github.abonsan.hyakunin.ui.components.SettingsCard
import io.github.abonsan.hyakunin.ui.components.SwitchSetting
import io.github.abonsan.hyakunin.ui.components.fadingBottomEdge
import io.github.abonsan.hyakunin.ui.components.isWideLayout
import io.github.abonsan.hyakunin.ui.theme.UsuzumiText
import io.github.abonsan.hyakunin.ui.theme.Washi
import io.github.abonsan.hyakunin.ui.theme.noLineBreak

/** 縦に積むときの設定の板の幅の上限。 */
private val NarrowCardMaxWidth = 560.dp

/** 左右に並べるときの、2枚の板を合わせた幅の上限。 */
private val WideCardsMaxWidth = 960.dp

/**
 * 設定の画面。画面の向きの板と、対戦画面の表示（句の表示・敵陣の札の向き）の板を、
 * 縦長の画面では縦に積み、横に広い画面では左右に並べて上下に詰める。
 * 変えた設定は [onSettingsChange] ですぐに渡す。
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
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
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
        ) {
            ScreenHeader(title = "設定", onBack = onBack)
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .fadingBottomEdge(scroll, Washi)
                    .verticalScroll(scroll)
                    .padding(top = if (wide) 4.dp else 8.dp, bottom = if (wide) 12.dp else 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (wide) {
                    Row(
                        Modifier
                            .widthIn(max = WideCardsMaxWidth)
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsCard(Modifier.weight(1f)) { OrientationSetting(settings, onSettingsChange, spacing) }
                        SettingsCard(Modifier.weight(1f)) { MatchDisplaySettings(settings, onSettingsChange, spacing) }
                    }
                } else {
                    Column(
                        Modifier
                            .widthIn(max = NarrowCardMaxWidth)
                            .padding(horizontal = 20.dp),
                    ) {
                        SettingsCard { OrientationSetting(settings, onSettingsChange, spacing) }
                        Spacer(Modifier.height(14.dp))
                        SettingsCard { MatchDisplaySettings(settings, onSettingsChange, spacing) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrientationSetting(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    spacing: SettingSpacing,
) {
    SettingBlock(spacing = spacing) {
        SectionLabel("画面の向き")
        Spacer(Modifier.height(spacing.label))
        ChoiceRow(
            options = ScreenOrientation.entries,
            selected = settings.orientation,
            label = { it.label },
            onSelect = { onSettingsChange(settings.copy(orientation = it)) },
        )
        Spacer(Modifier.height(8.dp))
        SelectedDescription(ScreenOrientation.entries, settings.orientation, { it.description })
        Spacer(Modifier.height(4.dp))
        Text(
            text = "大きな画面の端末では、向きの指定が効かないことがあります",
            modifier = Modifier.align(Alignment.Start),
            style = MaterialTheme.typography.labelSmall,
            color = UsuzumiText,
        )
    }
}

/** 対戦画面の表示の設定（句の表示・敵陣の札の向き）。 */
@Composable
private fun MatchDisplaySettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    spacing: SettingSpacing,
) {
    SettingBlock(spacing = spacing) {
        SectionLabel("句の表示")
        Spacer(Modifier.height(spacing.label))
        ChoiceRow(
            options = VerseStyle.entries,
            selected = settings.verseStyle,
            label = { it.label },
            onSelect = { onSettingsChange(settings.copy(verseStyle = it)) },
        )
        Spacer(Modifier.height(8.dp))
        SelectedDescription(VerseStyle.entries, settings.verseStyle, { it.settingsDescription })
    }
    PanelDivider()
    SwitchSetting(
        title = "敵陣の札を逆さに表示",
        description = "相手から見た向きで敵陣の札を並べます",
        checked = settings.flipOpponentCards,
        onCheckedChange = { onSettingsChange(settings.copy(flipOpponentCards = it)) },
        spacing = spacing,
    )
}

/** 設定の画面での句の表示の説明。読み上げ中と結果の句に使う表記を、例を添えて示す（例は行をまたいで分けない）。 */
internal val VerseStyle.settingsDescription: String
    get() = when (this) {
        VerseStyle.READING -> "読み上げるとおりの現代仮名遣いで表示します" + noLineBreak("（例：きょう、ちょう）")
        VerseStyle.KANA -> "漢字をひらがなに直しただけの、取り札と同じ表記で表示します" + noLineBreak("（例：けふ、てふ）")
    }
