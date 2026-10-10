package io.github.abonsan.hyakunin.ui.match

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
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
private val CardGap = 6.dp

/** 陣の左右の余白。 */
private val FieldPaddingHorizontal = 12.dp

/** 札の幅の上限（タブレットなどの広い画面で大きくなりすぎないように）。 */
private val MaxCardWidth = 84.dp

/**
 * 1段に5枚と間隔が収まる札の幅。画面の高さに対しても大きくなりすぎないようにする
 * （札の高さが画面の高さの 12% まで）。
 */
internal fun fieldCardWidth(maxWidth: Dp, maxHeight: Dp): Dp {
    val perRow = GameConfig.CARDS_PER_SIDE
    val byWidth = (maxWidth - FieldPaddingHorizontal * 2 - CardGap * (perRow - 1)) / perRow
    val byHeight = maxHeight * 0.12f * TORIFUDA_ASPECT_RATIO
    return minOf(byWidth, byHeight, MaxCardWidth)
}

/**
 * 畳の上に並べた一つの陣。1段に最大5枚で折り返す。
 * 敵陣は自陣と向かい合うよう、1段目を中央寄り（下側）に置く。
 *
 * @param contentInsets 札がシステムバーに重ならないよう内側に空ける余白。畳はその下まで敷く。
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
    contentInsets: WindowInsets = WindowInsets(0),
) {
    val rows = cards.chunked(GameConfig.CARDS_PER_SIDE).let { if (side == Side.CPU) it.asReversed() else it }
    Column(
        modifier
            .fillMaxWidth()
            .tatami(heriTop = true, heriBottom = true)
            .windowInsetsPadding(contentInsets)
            .padding(vertical = 12.dp)
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
