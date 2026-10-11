package io.github.abonsan.hyakunin.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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

/**
 * 設定の画面（画面の向き・敵陣の札の向き）。変えた設定は [onSettingsChange] ですぐに渡す。
 * 横に広い画面では、設定の板を中央に寄せて上下に詰める。
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
        val spacing = if (isWideLayout(maxWidth, maxHeight)) SettingSpacing.Dense else SettingSpacing.Regular
        val dense = spacing != SettingSpacing.Regular
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
                    .padding(top = if (dense) 4.dp else 8.dp, bottom = if (dense) 12.dp else 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                SettingsCard(
                    Modifier
                        .widthIn(max = 560.dp)
                        .padding(horizontal = 20.dp),
                ) {
                    OrientationSetting(
                        selected = settings.orientation,
                        onSelect = { onSettingsChange(settings.copy(orientation = it)) },
                        spacing = spacing,
                    )
                    PanelDivider()
                    SwitchSetting(
                        title = "敵陣の札を逆さに表示",
                        description = "相手から見た向きで敵陣の札を並べます",
                        checked = settings.flipOpponentCards,
                        onCheckedChange = { onSettingsChange(settings.copy(flipOpponentCards = it)) },
                        spacing = spacing,
                    )
                }
            }
        }
    }
}

@Composable
private fun OrientationSetting(
    selected: ScreenOrientation,
    onSelect: (ScreenOrientation) -> Unit,
    spacing: SettingSpacing,
) {
    SettingBlock(spacing = spacing) {
        SectionLabel("画面の向き")
        Spacer(Modifier.height(spacing.label))
        ChoiceRow(
            options = ScreenOrientation.entries,
            selected = selected,
            label = { it.label },
            onSelect = onSelect,
        )
        Spacer(Modifier.height(8.dp))
        SelectedDescription(ScreenOrientation.entries, selected, { it.description })
        Spacer(Modifier.height(4.dp))
        Text(
            text = "大きな画面の端末では、向きの指定が効かないことがあります",
            modifier = Modifier.align(Alignment.Start),
            style = MaterialTheme.typography.labelSmall,
            color = UsuzumiText,
        )
    }
}
