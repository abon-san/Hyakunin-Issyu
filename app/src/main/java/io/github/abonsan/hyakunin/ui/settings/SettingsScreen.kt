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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.abonsan.hyakunin.R
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.game.voice.VoiceClip
import io.github.abonsan.hyakunin.ui.components.ChoiceRow
import io.github.abonsan.hyakunin.ui.components.PanelDivider
import io.github.abonsan.hyakunin.ui.components.ScreenHeader
import io.github.abonsan.hyakunin.ui.components.SecondaryButton
import io.github.abonsan.hyakunin.ui.components.SectionLabel
import io.github.abonsan.hyakunin.ui.components.SelectedDescription
import io.github.abonsan.hyakunin.ui.components.SettingBlock
import io.github.abonsan.hyakunin.ui.components.SettingSpacing
import io.github.abonsan.hyakunin.ui.components.SettingsCard
import io.github.abonsan.hyakunin.ui.components.SwitchSetting
import io.github.abonsan.hyakunin.ui.components.WafuSlider
import io.github.abonsan.hyakunin.ui.components.fadingBottomEdge
import io.github.abonsan.hyakunin.ui.components.isWideLayout
import io.github.abonsan.hyakunin.ui.theme.Sumi
import io.github.abonsan.hyakunin.ui.theme.UsuzumiText
import io.github.abonsan.hyakunin.ui.theme.Washi
import io.github.abonsan.hyakunin.ui.theme.noLineBreak
import kotlin.math.roundToInt

/** 縦に積むときの設定の板の幅の上限。 */
private val NarrowCardMaxWidth = 560.dp

/** 左右に並べるときの、2枚の板を合わせた幅の上限。 */
private val WideCardsMaxWidth = 960.dp

/**
 * 設定の画面。画面の向きの板、対戦の板（句の表示・敵陣の札の向き・読み上げ音声とその音量）、クレジットの板を、
 * 縦長の画面では縦に積み、横に広い画面では左右の列に分けて上下に詰める。
 * 変えた設定は [onSettingsChange] ですぐに渡す。
 *
 * @param previewClip 音量の「試聴」で鳴らすクリップ（序歌の上の句）。読み込み中・音声がないときは null で、「試聴」を押せない
 */
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    previewClip: VoiceClip? = null,
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
                    // 左に画面の向きとクレジット、右に対戦の設定（読み上げ音声の音量まで。右の方が少し高い）
                    Row(
                        Modifier
                            .widthIn(max = WideCardsMaxWidth)
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            SettingsCard { OrientationSetting(settings, onSettingsChange, spacing) }
                            SettingsCard { Credits(spacing) }
                        }
                        SettingsCard(Modifier.weight(1f)) {
                            MatchDisplaySettings(settings, onSettingsChange, spacing, previewClip)
                        }
                    }
                } else {
                    Column(
                        Modifier
                            .widthIn(max = NarrowCardMaxWidth)
                            .padding(horizontal = 20.dp),
                    ) {
                        SettingsCard { OrientationSetting(settings, onSettingsChange, spacing) }
                        Spacer(Modifier.height(14.dp))
                        SettingsCard { MatchDisplaySettings(settings, onSettingsChange, spacing, previewClip) }
                        Spacer(Modifier.height(14.dp))
                        SettingsCard { Credits(spacing) }
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

/** 対戦の設定（句の表示・敵陣の札の向き・読み上げ音声とその音量）。 */
@Composable
private fun MatchDisplaySettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    spacing: SettingSpacing,
    previewClip: VoiceClip?,
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
    PanelDivider()
    SwitchSetting(
        title = "読み上げ音声",
        description = "歌を声で読み上げます。" + noLineBreak("オフにすると") + noLineBreak("文字だけで進みます"),
        checked = settings.voiceEnabled,
        onCheckedChange = { onSettingsChange(settings.copy(voiceEnabled = it)) },
        spacing = spacing,
    )
    VolumeSetting(
        volume = settings.voiceVolume,
        onVolumeChange = { onSettingsChange(settings.copy(voiceVolume = it)) },
        enabled = settings.voiceEnabled,
        previewClip = previewClip,
        spacing = spacing,
    )
}

/** 読み上げの音量の見出し（スライダーの読み上げ機能向けの名前にも使う）。 */
internal const val VOLUME_LABEL = "音量"

/** 「試聴」のボタンの文字。鳴っている間は [PREVIEW_STOP_LABEL]。 */
internal const val PREVIEW_LABEL = "試聴"

/** 「試聴」で鳴らしている間のボタンの文字。 */
internal const val PREVIEW_STOP_LABEL = "停止"

/** 100%（クリップそのままの大きさ）の中の、音量の刻み（5%）の数。 */
private const val STEPS_PER_FULL_VOLUME = 20

/** 音量の刻みの数（0〜200% を 5% ずつ）。 */
private val VOLUME_STEPS = (GameConfig.MAX_VOICE_VOLUME * STEPS_PER_FULL_VOLUME).roundToInt()

/** スライダーの真ん中に目盛りを付ける音量（100%、クリップそのままの大きさ。以前の版の最大で、既定）。 */
private const val FULL_VOLUME = 1f

/** 読み上げ音声を切っているときの、音量の見出しと値の濃さ。 */
private const val DISABLED_ALPHA = 0.5f

/** 音量の見出し・スライダー・値・「試聴」を1行に並べられる幅。これより狭ければ、見出しと値を上の行に分ける。 */
private val VolumeOneLineMinWidth = 340.dp

/** 音量 [volume]（倍率、0〜2）の表示（例:「80%」「150%」）。 */
internal fun volumeText(volume: Float): String = "${(volume * 100).roundToInt()}%"

/** スライダーの値 [value] を、音量の刻み（5%）にそろえる（0〜200%）。 */
internal fun snapVolume(value: Float): Float =
    (value * STEPS_PER_FULL_VOLUME).roundToInt().coerceIn(0, VOLUME_STEPS) / STEPS_PER_FULL_VOLUME.toFloat()

/**
 * 読み上げ音声の音量（0〜200%、5% 刻み）のスライダーと今の値、その音量で序歌の上の句を鳴らす「試聴」。
 * 100%（クリップそのままの大きさ、既定）はスライダーの真ん中で、目盛りを付ける。それより右は 200% まで大きくできる。
 * 読み上げ音声を切っているときは淡くして動かせなくする（「試聴」は [previewClip] がないときも押せない）。
 * 「試聴」は、もう一度押すと止まる。鳴っている間にスライダーを動かすと、すぐその音量に変わる。
 * 見出しと値は読み上げ機能では読まない（スライダーが名前と値を持つ）。
 */
@Composable
private fun VolumeSetting(
    volume: Float,
    onVolumeChange: (Float) -> Unit,
    enabled: Boolean,
    previewClip: VoiceClip?,
    spacing: SettingSpacing,
) {
    val preview = rememberVoicePreview()
    val canPreview = enabled && previewClip != null
    // 読み上げ音声を切った・クリップがなくなったら止める
    LaunchedEffect(preview, canPreview) {
        if (!canPreview) preview.stop()
    }
    // 鳴っている間にスライダーを動かしたら、すぐその音量にする
    LaunchedEffect(preview, volume) {
        preview.setVolume(volume)
    }
    val text = volumeText(volume)
    val dimmed = Modifier
        .alpha(if (enabled) 1f else DISABLED_ALPHA)
        .semantics { hideFromAccessibility() }
    val label = @Composable {
        Text(VOLUME_LABEL, modifier = dimmed, style = MaterialTheme.typography.titleSmall, color = Sumi)
    }
    val value = @Composable {
        Text(
            text = text,
            modifier = dimmed.widthIn(min = 44.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = Sumi,
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
    val slider = @Composable { sliderModifier: Modifier ->
        WafuSlider(
            value = volume,
            onValueChange = { snapVolume(it).let { new -> if (new != volume) onVolumeChange(new) } },
            modifier = sliderModifier.semantics {
                contentDescription = VOLUME_LABEL
                stateDescription = text
            },
            enabled = enabled,
            valueRange = 0f..GameConfig.MAX_VOICE_VOLUME,
            steps = VOLUME_STEPS - 1,
            marks = listOf(FULL_VOLUME),
        )
    }
    val button = @Composable {
        SecondaryButton(
            text = if (preview.playing) PREVIEW_STOP_LABEL else PREVIEW_LABEL,
            onClick = {
                if (preview.playing) {
                    preview.stop()
                } else if (previewClip != null) {
                    preview.play(previewClip.asset, volume)
                }
            },
            icon = if (preview.playing) R.drawable.ic_stop else R.drawable.ic_play,
            minHeight = 40.dp,
            enabled = canPreview,
        )
    }
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .padding(bottom = spacing.block),
    ) {
        if (maxWidth >= VolumeOneLineMinWidth) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                label()
                Spacer(Modifier.width(12.dp))
                slider(Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                value()
                Spacer(Modifier.width(12.dp))
                button()
            }
        } else {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    label()
                    Spacer(Modifier.weight(1f))
                    value()
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    slider(Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    button()
                }
            }
        }
    }
}

/** 読み上げ音声の表記（VOICEVOX の利用規約で求められる形）。 */
internal const val VOICE_CREDIT = "音声：VOICEVOX:冥鳴ひまり"

/** 読み上げ音声の作り方と利用規約の説明。 */
internal const val VOICE_CREDIT_NOTE = "読み上げの音声は、VOICEVOX と冥鳴ひまりの利用規約に従い、VOICEVOX で作成しています。"

/** クレジット。読み上げ音声の作り方と利用規約。 */
@Composable
private fun Credits(spacing: SettingSpacing) {
    SettingBlock(spacing = spacing) {
        SectionLabel("クレジット")
        Spacer(Modifier.height(spacing.label))
        Text(text = VOICE_CREDIT, style = MaterialTheme.typography.bodyMedium, color = Sumi)
        Spacer(Modifier.height(4.dp))
        Text(
            text = VOICE_CREDIT_NOTE,
            style = MaterialTheme.typography.bodySmall,
            color = UsuzumiText,
        )
    }
}

/** 設定の画面での句の表示の説明。読み上げ中と結果の句に使う表記を、例を添えて示す（例は行をまたいで分けない）。 */
internal val VerseStyle.settingsDescription: String
    get() = when (this) {
        VerseStyle.READING -> "読み上げるとおりの現代仮名遣いで表示します" + noLineBreak("（例：きょう、ちょう）")
        VerseStyle.KANA -> "漢字をひらがなに直しただけの、取り札と同じ表記で表示します" + noLineBreak("（例：けふ、てふ）")
    }
