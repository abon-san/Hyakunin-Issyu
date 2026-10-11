package io.github.abonsan.hyakunin.ui.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.abonsan.hyakunin.game.FieldCard
import io.github.abonsan.hyakunin.game.GameConfig
import io.github.abonsan.hyakunin.game.Side
import io.github.abonsan.hyakunin.ui.card.TORIFUDA_ASPECT_RATIO
import io.github.abonsan.hyakunin.ui.card.Torifuda
import io.github.abonsan.hyakunin.ui.card.torifudaHeight
import io.github.abonsan.hyakunin.ui.components.tatami

/** 札と札の間。 */
internal val CardGap = 6.dp

/** 陣の左右の余白。 */
private val FieldPaddingHorizontal = 12.dp

/** 陣の上下の余白。 */
internal val FieldPaddingVertical = 12.dp

/** 札の幅の上限（タブレットなどの広い画面で大きくなりすぎないように）。 */
private val MaxCardWidth = 84.dp

/** 1段に並べる札の数。 */
internal const val CARDS_PER_ROW = GameConfig.CARDS_PER_SIDE

/**
 * 1段に5枚と間隔が収まる札の幅。画面の高さに対しても大きくなりすぎないようにする
 * （札の高さが画面の高さの 12% まで）。縦に並べる配置で使う。
 */
internal fun fieldCardWidth(maxWidth: Dp, maxHeight: Dp): Dp {
    val byWidth = (maxWidth - FieldPaddingHorizontal * 2 - CardGap * (CARDS_PER_ROW - 1)) / CARDS_PER_ROW
    val byHeight = maxHeight * 0.12f * TORIFUDA_ASPECT_RATIO
    return minOf(byWidth, byHeight, MaxCardWidth)
}

/** 札が [cardCount] 枚の陣を並べる段の数（札が無くても1段分の場所を取る）。 */
internal fun fieldRows(cardCount: Int): Int = maxOf(1, (cardCount + CARDS_PER_ROW - 1) / CARDS_PER_ROW)

/** 札が [cardCount] 枚の陣の、札と上下の余白を合わせた高さ。 */
internal fun fieldHeight(cardCount: Int, cardWidth: Dp): Dp {
    val rows = fieldRows(cardCount)
    return torifudaHeight(cardWidth) * rows + CardGap * (rows - 1) + FieldPaddingVertical * 2
}

/** 横に広い画面の対戦の配置。左から成績の帯、読み上げの欄、場（上に敵陣、下に自陣）の幅。 */
internal data class LandscapeMetrics(
    val sidebarWidth: Dp,
    val panelWidth: Dp,
    val fieldWidth: Dp,
    val cardWidth: Dp,
)

/** 横に並べる配置での、敵陣と自陣の間（生成りの帯）。 */
internal val FieldSeparator = 8.dp

/** 横に並べる配置での札の幅の目安。縦に並べる配置の一般的なスマホと同じくらい。 */
private val LandscapeCardWidth = 72.dp

/** 指で押しやすい札の幅。読み上げの欄を [PanelFloorWidth] まで狭めてでも、この幅に近づける。 */
internal val TouchCardWidth = 48.dp

/** 横に並べる配置での札の幅の下限。 */
private val MinCardWidth = 36.dp

/** 横に並べる配置で、読み上げの欄に残したい幅。 */
private val PanelMinWidth = 236.dp

/** 横に並べる配置での読み上げの欄の幅の下限。これを残せない広さでは縦に並べる配置にする。 */
internal val PanelFloorWidth = 200.dp

/** 読み上げの欄がこの幅より広く余るなら（タブレットなど）、札を縦に並べる配置の上限まで大きくする。 */
private val PanelComfortWidth = 560.dp

/**
 * 横に広い画面（幅 [width]・高さ [height]。システムバーを除いた広さ）での配置を決める。
 *
 * 場の札は両陣で多くとも10枚なので、片方が2段になってももう片方は1段で済む。
 * 両陣を縦に重ねて合わせて3段が高さに収まり、読み上げの欄に最低限の幅が残る札の幅にする。
 * 札が押しにくいほど小さくなるなら、読み上げの欄を下限まで狭めて札を [TouchCardWidth] に近づける。
 * 余った幅は読み上げの欄に回す。札の大きさは対戦中に変えない（送り札で枚数が変わっても並びが動くだけ）。
 */
internal fun landscapeMetrics(width: Dp, height: Dp): LandscapeMetrics {
    val sidebar = if (width >= 760.dp) 84.dp else 78.dp
    val rest = width - sidebar
    val chrome = FieldPaddingHorizontal * 2 + CardGap * (CARDS_PER_ROW - 1)

    // 読み上げの欄に panel の幅を残したときの札の幅
    fun cardLeaving(panel: Dp): Dp = (rest - panel - chrome) / CARDS_PER_ROW

    val rowHeight = (height - FieldPaddingVertical * 4 - CardGap - FieldSeparator) / 3
    val byHeight = rowHeight * TORIFUDA_ASPECT_RATIO
    val upper = minOf(byHeight, maxOf(LandscapeCardWidth, minOf(cardLeaving(PanelComfortWidth), MaxCardWidth)))
    val touch = minOf(TouchCardWidth, cardLeaving(PanelFloorWidth))
    val card = maxOf(cardLeaving(PanelMinWidth), touch).coerceAtMost(upper).coerceAtLeast(MinCardWidth)
    val fields = card * CARDS_PER_ROW + chrome
    return LandscapeMetrics(
        sidebarWidth = sidebar,
        panelWidth = (rest - fields).coerceAtLeast(0.dp),
        fieldWidth = fields,
        cardWidth = card,
    )
}

/**
 * 横に並べる配置にするか（幅 [width]・高さ [height] はシステムバーを除いた広さ）。
 * 幅が高さより広く、読み上げの欄に下限の幅が残るなら、場を右に重ね、読み上げの欄を左に置く。
 * 端末の向きではなく、使える広さで決める（分割画面やタブレットでも合うように）。
 */
internal fun useLandscapeBoard(width: Dp, height: Dp): Boolean =
    width > height && landscapeMetrics(width, height).panelWidth >= PanelFloorWidth

/**
 * 畳の上に並べた一つの陣。1段に最大5枚で折り返す。
 * 敵陣は自陣と向かい合うよう、1段目を中央寄り（下側）に置く。
 * [modifier] で高さを与えると、札はその中の上下中央に置く。
 *
 * @param contentPadding 札がシステムバーに重ならないよう内側に空ける余白。畳はその下まで敷く。
 */
@Composable
internal fun FieldArea(
    cards: List<FieldCard>,
    side: Side,
    cardWidth: Dp,
    upsideDown: Boolean,
    dimmed: Boolean,
    selectable: Boolean,
    onCardTap: ((FieldCard) -> Unit)?,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val rows = cards.chunked(CARDS_PER_ROW).let { if (side == Side.CPU) it.asReversed() else it }
    Column(
        modifier
            .fillMaxWidth()
            .tatami(heriTop = true, heriBottom = true)
            .padding(contentPadding)
            .padding(vertical = FieldPaddingVertical)
            .heightIn(min = torifudaHeight(cardWidth))
            .semantics { paneTitle = if (side == Side.CPU) "敵陣" else "自陣" },
        verticalArrangement = Arrangement.spacedBy(CardGap, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(CardGap)) {
                row.forEach { card ->
                    key(card.poemNo) {
                        Torifuda(
                            shimoKana = card.shimoKana,
                            modifier = Modifier.width(cardWidth),
                            upsideDown = upsideDown,
                            isNew = card.isNew,
                            selectable = selectable,
                            dimmed = dimmed,
                            onTap = onCardTap?.let { tap -> { tap(card) } },
                        )
                    }
                }
            }
        }
    }
}
