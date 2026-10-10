package io.github.abonsan.hyakunin.ui.card

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.abonsan.hyakunin.ui.theme.CardEdge
import io.github.abonsan.hyakunin.ui.theme.CardFace
import io.github.abonsan.hyakunin.ui.theme.CardFaceShade
import io.github.abonsan.hyakunin.ui.theme.Kin
import io.github.abonsan.hyakunin.ui.theme.ShipporiMincho
import io.github.abonsan.hyakunin.ui.theme.Shu
import io.github.abonsan.hyakunin.ui.theme.Sumi

/** 取り札の幅:高さ（52:73）。 */
const val TORIFUDA_ASPECT_RATIO = 52f / 73f

/** 幅 [width] の取り札の高さ。 */
fun torifudaHeight(width: Dp): Dp = width / TORIFUDA_ASPECT_RATIO

/**
 * 取り札。下の句（[shimoKana]）を3列の縦書きで、右の列から書く。
 * 大きさは [modifier] で与えた幅から決まり、高さは縦横比で決まる。
 *
 * @param upsideDown 札を180度回した向き（相手から見た向き）で書く。
 * @param isNew 送り札で移ってきた札。金の縁で目立たせる。
 * @param selectable 送る札として選べる。朱の縁で目立たせる。
 * @param dimmed 取れない状態として少し暗くする。
 * @param onTap 触れたときの処理。指が触れた瞬間に呼ぶ（離すのを待たない）。null なら触れられない。
 */
@Composable
fun Torifuda(
    shimoKana: String,
    modifier: Modifier = Modifier,
    upsideDown: Boolean = false,
    isNew: Boolean = false,
    selectable: Boolean = false,
    dimmed: Boolean = false,
    onTap: (() -> Unit)? = null,
) {
    val columns = remember(shimoKana) { torifudaColumns(shimoKana) }
    val measurer = rememberTextMeasurer(cacheSize = 24)
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "torifudaPress")
    val currentOnTap by rememberUpdatedState(onTap)
    val tappable = onTap != null

    val edgeColor = when {
        selectable -> Shu
        isNew -> Kin
        else -> CardEdge
    }
    val edgeWidth = if (selectable || isNew) 2.5.dp else 1.dp
    val glowColor = when {
        selectable -> Shu
        isNew -> Kin
        else -> Color.Black
    }
    val elevation = if (selectable || isNew) 7.dp else 2.dp

    Box(
        modifier
            .aspectRatio(TORIFUDA_ASPECT_RATIO)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .shadow(elevation, CardShape, ambientColor = glowColor, spotColor = glowColor)
            .background(CardFaceBrush, CardShape)
            .border(edgeWidth, edgeColor, CardShape)
            .semantics {
                contentDescription = shimoKana
                if (tappable) {
                    role = Role.Button
                    onClick {
                        currentOnTap?.invoke()
                        true
                    }
                }
            }
            .then(
                if (tappable) {
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown()
                            pressed = true
                            try {
                                currentOnTap?.invoke()
                                waitForUpOrCancellation()
                            } finally {
                                pressed = false
                            }
                        }
                    }
                } else {
                    Modifier
                },
            )
            .drawWithCache {
                val glyphs = placeGlyphs(columns, measurer)
                onDrawWithContent {
                    drawContent()
                    if (upsideDown) {
                        rotate(180f) { drawGlyphs(glyphs) }
                    } else {
                        drawGlyphs(glyphs)
                    }
                    if (dimmed) drawRect(DimColor)
                }
            },
    )
}

/** 取り札の文字を3列に分ける。空白を除き、1列に ceil(n/3) 文字ずつ入れる。最初の要素が右端の列。 */
internal fun torifudaColumns(shimoKana: String): List<String> {
    val text = shimoKana.filterNot { it.isWhitespace() }
    if (text.isEmpty()) return emptyList()
    return text.chunked((text.length + 2) / 3)
}

private val CardShape = RoundedCornerShape(3.dp)
private val CardFaceBrush = Brush.verticalGradient(listOf(CardFace, CardFaceShade))
private val DimColor = Sumi.copy(alpha = 0.28f)

// しっぽり明朝の em ボックス（上 0.88em・下 0.12em）の中心は、ベースラインの 0.38em 上にある
private const val EM_CENTER_ABOVE_BASELINE = 0.38f

private class PlacedGlyph(val layout: TextLayoutResult, val topLeft: Offset)

private fun CacheDrawScope.placeGlyphs(columns: List<String>, measurer: TextMeasurer): List<PlacedGlyph> {
    if (columns.isEmpty()) return emptyList()
    val rows = columns.maxOf { it.length }
    val padX = size.width * 0.12f
    val padY = size.height * 0.08f
    val columnWidth = (size.width - padX * 2) / 3
    val cellHeight = (size.height - padY * 2) / rows
    val fontPx = minOf(columnWidth * 0.88f, cellHeight * 0.98f)
    val style = TextStyle(
        color = Sumi,
        fontFamily = ShipporiMincho,
        fontSize = fontPx.toSp(),
        // 小書きの仮名や長音符を縦書き用の字形にする
        fontFeatureSettings = "vert",
    )
    return columns.flatMapIndexed { column, text ->
        val centerX = size.width - padX - columnWidth * (column + 0.5f)
        text.mapIndexed { row, char ->
            val layout = measurer.measure(char.toString(), style, density = this)
            val centerY = padY + cellHeight * (row + 0.5f)
            PlacedGlyph(
                layout = layout,
                topLeft = Offset(
                    x = centerX - layout.size.width / 2f,
                    y = centerY - layout.firstBaseline + EM_CENTER_ABOVE_BASELINE * fontPx,
                ),
            )
        }
    }
}

private fun DrawScope.drawGlyphs(glyphs: List<PlacedGlyph>) {
    glyphs.forEach { drawText(it.layout, topLeft = it.topLeft) }
}
