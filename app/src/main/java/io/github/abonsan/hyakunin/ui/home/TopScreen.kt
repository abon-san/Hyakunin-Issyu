package io.github.abonsan.hyakunin.ui.home

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.abonsan.hyakunin.BuildConfig
import io.github.abonsan.hyakunin.R
import io.github.abonsan.hyakunin.data.Poems
import io.github.abonsan.hyakunin.game.RuleMode
import io.github.abonsan.hyakunin.ui.card.Torifuda
import io.github.abonsan.hyakunin.ui.components.CenteredScrollColumn
import io.github.abonsan.hyakunin.ui.components.OrnamentTitle
import io.github.abonsan.hyakunin.ui.components.SecondaryButton
import io.github.abonsan.hyakunin.ui.components.drawSeigaiha
import io.github.abonsan.hyakunin.ui.components.fadingBottomEdge
import io.github.abonsan.hyakunin.ui.components.isWideLayout
import io.github.abonsan.hyakunin.ui.settings.VOICE_CREDIT
import io.github.abonsan.hyakunin.ui.theme.Kincha
import io.github.abonsan.hyakunin.ui.theme.Kon
import io.github.abonsan.hyakunin.ui.theme.Shu
import io.github.abonsan.hyakunin.ui.theme.Sumi
import io.github.abonsan.hyakunin.ui.theme.UsuzumiText
import io.github.abonsan.hyakunin.ui.theme.Washi
import io.github.abonsan.hyakunin.ui.theme.WashiLight
import kotlin.math.abs

/** 画面に出すモードの名前。 */
internal val RuleMode.modeName: String
    get() = when (this) {
        RuleMode.COMPETITIVE -> "競技モード"
        RuleMode.SIMPLE -> "簡易モード"
    }

/** トップ画面の下に小さく出す版（例:「Ver. 0.5.0」）。 */
internal val VERSION_LABEL = "Ver. ${BuildConfig.VERSION_NAME}"

/** 版の、読み上げ機能向けの読み方。 */
private val VERSION_DESCRIPTION = "バージョン ${BuildConfig.VERSION_NAME}"

/** モードの印の色（競技は朱、簡易は紺）。 */
internal val RuleMode.accent: Color
    get() = when (this) {
        RuleMode.COMPETITIVE -> Shu
        RuleMode.SIMPLE -> Kon
    }

/**
 * トップ画面。モードを選ぶと [onSelectMode]、「設定」で [onOpenSettings] を呼ぶ。
 * 横に広い画面では、左に題字、右にモードの選択を並べる。読み上げ音声のクレジットと版（[VERSION_LABEL]）は
 * 「設定」の下に1行ずつ小さく添える。
 */
@Composable
fun TopScreen(
    onSelectMode: (RuleMode) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Washi),
    ) {
        if (isWideLayout(maxWidth, maxHeight)) {
            val compact = maxHeight < 400.dp
            Row(Modifier.fillMaxSize()) {
                TitleBanner(
                    modifier = Modifier
                        .weight(0.9f)
                        .fillMaxHeight(),
                    wide = true,
                    compact = compact,
                )
                CenteredScrollColumn(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .windowInsetsPadding(
                            WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.End),
                        ),
                ) {
                    ModeMenu(
                        onSelectMode = onSelectMode,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier
                            .widthIn(max = 480.dp)
                            .padding(
                                start = 8.dp,
                                end = 28.dp,
                                top = if (compact) 10.dp else 16.dp,
                                bottom = if (compact) 10.dp else 16.dp,
                            ),
                        compact = compact,
                    )
                }
            }
        } else {
            // 題字を上に、モードの選択を下（指の届きやすいところ）に置き、余った高さは題字の欄に回す。
            // 低い画面（分割画面など）では全体をスクロールする
            val compact = maxHeight < CompactHeight
            val viewport = maxHeight
            val scroll = rememberScrollState()
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
                    )
                    .fadingBottomEdge(scroll, Washi)
                    .verticalScroll(scroll)
                    .heightIn(min = viewport),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TitleBanner(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = (viewport - MenuReservedHeight).coerceAtLeast(0.dp)),
                    wide = false,
                    compact = compact,
                )
                ModeMenu(
                    onSelectMode = onSelectMode,
                    onOpenSettings = onOpenSettings,
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = if (compact) 10.dp else 16.dp),
                    compact = compact,
                )
            }
        }
    }
}

/** これより低い画面では、題字とモードの札を小さめにする。 */
private val CompactHeight = 600.dp

/** 縦長の画面で、モードの選択に残しておく高さ（残りを題字の欄にする）。 */
private val MenuReservedHeight = 420.dp

/** 題字（扇に重ねた取り札と「百人一首」）。青海波の地を敷く。 */
@Composable
private fun TitleBanner(modifier: Modifier, wide: Boolean, compact: Boolean) {
    Box(
        modifier
            // 波の輪が隣の欄へはみ出さないように切る
            .clipToBounds()
            .drawBehind {
                drawSeigaiha(
                    color = Kincha.copy(alpha = 0.22f),
                    background = Washi,
                    radius = 22.dp.toPx(),
                    strokeWidth = 1.dp.toPx(),
                )
                // 縦画面は下へ、横に広い画面は右へ向かって地の色に溶かす
                drawRect(
                    if (wide) {
                        Brush.horizontalGradient(0f to Washi.copy(alpha = 0.3f), 0.7f to Washi.copy(alpha = 0.8f), 1f to Washi)
                    } else {
                        Brush.verticalGradient(0f to Washi.copy(alpha = 0.35f), 0.75f to Washi)
                    },
                )
            }
            .then(
                if (wide) {
                    Modifier.windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                    )
                } else {
                    Modifier.statusBarsPadding()
                },
            )
            .padding(top = if (wide) 12.dp else 20.dp, bottom = if (wide) 12.dp else 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            FannedCards(cardWidth = if (compact) 50.dp else 60.dp)
            Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
            Text(
                text = "百人一首",
                // 字間の分だけ左に余白を足して中央に見せる
                modifier = Modifier.padding(start = 10.dp),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                fontSize = if (compact) 34.sp else 40.sp,
                letterSpacing = 0.25.em,
                color = Sumi,
            )
            Spacer(Modifier.height(6.dp))
            OrnamentTitle(
                text = "かるた取り　コンピュータと対戦",
                modifier = Modifier
                    .widthIn(max = 360.dp)
                    .padding(horizontal = if (wide) 12.dp else 36.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** 扇のように重ねた3枚の取り札。 */
@Composable
private fun FannedCards(cardWidth: Dp, modifier: Modifier = Modifier) {
    val cards = Poems.all.take(3)
    val scale = cardWidth / 60.dp
    Box(modifier.size(width = 190.dp * scale, height = 96.dp * scale), contentAlignment = Alignment.Center) {
        cards.forEachIndexed { index, poem ->
            val position = index - (cards.size - 1) / 2f
            Torifuda(
                shimoKana = poem.shimoKana,
                modifier = Modifier
                    .offset(x = (position * 44 * scale).dp, y = (abs(position) * 6 * scale).dp)
                    .rotate(position * 11f)
                    .width(cardWidth),
            )
        }
    }
}

/** モードの選択と「設定」への入口、読み上げ音声のクレジットと版。 */
@Composable
private fun ModeMenu(
    onSelectMode: (RuleMode) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        OrnamentTitle("モードを選んでください", Modifier.padding(horizontal = 12.dp))
        Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
        RuleMode.entries.forEach { mode ->
            ModeCard(mode, onClick = { onSelectMode(mode) }, compact = compact)
            Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
        }
        Spacer(Modifier.height(if (compact) 2.dp else 10.dp))
        SecondaryButton(
            text = "設定",
            onClick = onOpenSettings,
            icon = R.drawable.ic_settings,
            minHeight = if (compact) 44.dp else 48.dp,
        )
        Spacer(Modifier.height(if (compact) 6.dp else 12.dp))
        Text(text = VOICE_CREDIT, style = MaterialTheme.typography.labelSmall, color = UsuzumiText)
        Spacer(Modifier.height(2.dp))
        // 版はクレジットの一部と読まれないよう、行を分けて同じく小さく添える
        Text(
            text = VERSION_LABEL,
            modifier = Modifier.semantics { contentDescription = VERSION_DESCRIPTION },
            style = MaterialTheme.typography.labelSmall,
            color = UsuzumiText,
        )
    }
}

/** モードの札。名前と、ルールの短い説明を書く。 */
@Composable
private fun ModeCard(mode: RuleMode, onClick: () -> Unit, compact: Boolean) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = WashiLight,
        border = BorderStroke(1.dp, Kincha.copy(alpha = 0.7f)),
        shadowElevation = 2.dp,
    ) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(5.dp)
                    .fillMaxHeight()
                    .background(mode.accent),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 16.dp, end = 4.dp, top = if (compact) 10.dp else 14.dp, bottom = if (compact) 10.dp else 14.dp),
                verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
            ) {
                Text(
                    text = mode.modeName,
                    style = MaterialTheme.typography.titleLarge,
                    color = Sumi,
                    letterSpacing = 0.08.em,
                )
                Text(
                    text = mode.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = UsuzumiText,
                )
            }
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                modifier = Modifier.padding(end = 10.dp),
                tint = Kincha,
            )
        }
    }
}
