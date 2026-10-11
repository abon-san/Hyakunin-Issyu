package io.github.abonsan.hyakunin.ui.card

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.resolveAsTypeface
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.abonsan.hyakunin.ui.theme.CardEdge
import io.github.abonsan.hyakunin.ui.theme.CardFace
import io.github.abonsan.hyakunin.ui.theme.CardInk
import io.github.abonsan.hyakunin.ui.theme.Kin
import io.github.abonsan.hyakunin.ui.theme.Shu
import io.github.abonsan.hyakunin.ui.theme.Sumi
import io.github.abonsan.hyakunin.ui.theme.TorifudaFont
import kotlin.math.roundToInt

/** 取り札の幅:高さ（52:73）。 */
const val TORIFUDA_ASPECT_RATIO = 52f / 73f

/** 幅 [width] の取り札の高さ。 */
fun torifudaHeight(width: Dp): Dp = width / TORIFUDA_ASPECT_RATIO

/**
 * 取り札。下の句（[shimoKana]）を3列の縦書きで、右の列から書く。
 * 大きさは [modifier] で与えた幅から決まり、高さは縦横比で決まる。
 * 直角の札に内側へ向けて緑の縁を引き、文字はしっぽり明朝 SemiBold の黒で書く。
 * 縁の太さ・字の大きさと位置はすべて札の大きさとの比で決める（端末の文字サイズの設定には左右されない）。
 *
 * @param upsideDown 札を180度回した向き（相手から見た向き）で書く。
 * @param isNew 送り札で移ってきた札。縁を金にして目立たせる。
 * @param selectable 送る札として選べる。縁を朱にして目立たせる。
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
    val fontResolver = LocalFontFamilyResolver.current
    val typeface by remember(fontResolver) {
        fontResolver.resolveAsTypeface(TorifudaFont, FontWeight.SemiBold, fontSynthesis = FontSynthesis.None)
    }
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (pressed) 0.94f else 1f, label = "torifudaPress")
    val currentOnTap by rememberUpdatedState(onTap)
    val tappable = onTap != null

    // 縁の色だけを替える（太さはどの札も同じ）
    val edgeColor = when {
        selectable -> Shu
        isNew -> Kin
        else -> CardEdge
    }
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
            .shadow(elevation, RectangleShape, ambientColor = glowColor, spotColor = glowColor)
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
                val face = layoutTorifuda(columns, size, typeface)
                onDrawBehind {
                    drawRect(edgeColor)
                    drawRect(
                        CardFace,
                        topLeft = Offset(face.edge, face.edge),
                        size = Size(size.width - face.edge * 2, size.height - face.edge * 2),
                    )
                    if (upsideDown) {
                        rotate(180f) { drawGlyphs(face) }
                    } else {
                        drawGlyphs(face)
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

/** 取り札の文字に使う OpenType の機能。小書きの仮名や長音符を縦書き用の字形にする。 */
internal const val TORIFUDA_FONT_FEATURES = "vert"

private val DimColor = Sumi.copy(alpha = 0.28f)

// デザイン（幅 619.7・高さ 870 の札）での寸法。札の幅・高さとの比にして使う
private const val DESIGN_WIDTH = 619.7f
private const val DESIGN_HEIGHT = 870f

/** 縁の太さ（四辺とも同じ）。 */
private const val EDGE_WIDTH = 40f / DESIGN_WIDTH

/** 字の大きさ（em）。 */
private const val FONT_SIZE = 109.8f / DESIGN_WIDTH

/** 隣り合う列の中心の間隔。中央の列の中心は札の中心。 */
private const val COLUMN_STEP = 158.9f / DESIGN_WIDTH

/** 1行目の字のインクの上端。 */
private const val FIRST_INK_TOP = 122.0f / DESIGN_HEIGHT

/** 行の間隔（インクの上端どうし）。 */
private const val ROW_PITCH = 134.6f / DESIGN_HEIGHT

/** デザインの行数。これより行の多い札は、字の大きさと行の間隔を縮めて同じ範囲に収める。 */
private const val DESIGN_ROWS = 5

/** 字の送り幅を測る大きさ。小さな字では送り幅が整数の px に丸められるので、大きな字で測ってから縮める。 */
private const val MEASURE_TEXT_SIZE = 1000f

/** 札に書く一字。[x] は em ボックスの左端、[baseline] はベースラインの高さ。 */
internal class PlacedGlyph(val text: String, val x: Float, val baseline: Float)

/** 大きさを決めた札の中身。縁の太さ [edge] と、字を書く [paint]・[glyphs]。 */
internal class TorifudaLayout(val edge: Float, val paint: Paint, val glyphs: List<PlacedGlyph>)

/**
 * 大きさ [size] の札に [columns]（[torifudaColumns] で分けた列）の字を置く。
 * 列の中心に em ボックスの中心をそろえ、字形の実際の上端（インクの上端）を行の線にそろえる。
 */
internal fun layoutTorifuda(columns: List<String>, size: Size, typeface: Typeface): TorifudaLayout {
    // 縁は px の境目にそろえて、内側の線をにじませない
    val edge = (size.width * EDGE_WIDTH).roundToInt().toFloat().coerceAtLeast(1f).coerceAtMost(size.minDimension / 2)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        this.typeface = typeface
        color = CardInk.toArgb()
        fontFeatureSettings = TORIFUDA_FONT_FEATURES
        textSize = MEASURE_TEXT_SIZE
    }
    val rows = columns.maxOfOrNull { it.length } ?: 0
    val shrink = if (rows > DESIGN_ROWS) DESIGN_ROWS.toFloat() / rows else 1f
    val fontPx = size.width * FONT_SIZE * shrink
    val firstTop = size.height * FIRST_INK_TOP
    val pitch = size.height * ROW_PITCH * shrink
    // 字ごとの送り幅（em）
    val advances = columns.joinToString("").toSet().associateWith { paint.measureText(it.toString()) / MEASURE_TEXT_SIZE }
    paint.textSize = fontPx
    val ink = RectF()
    val glyphs = columns.flatMapIndexed { column, text ->
        // 右の列から書く
        val centerX = size.width / 2 + size.width * COLUMN_STEP * (1 - column)
        text.mapIndexed { row, char ->
            val glyph = char.toString()
            paint.inkBounds(glyph, ink)
            PlacedGlyph(
                text = glyph,
                x = centerX - advances.getValue(char) * fontPx / 2,
                baseline = firstTop + pitch * row - ink.top,
            )
        }
    }
    return TorifudaLayout(edge, paint, glyphs)
}

/**
 * [text] を原点に書いたときのインクの外接矩形を [out] に入れる（字形の輪郭から求めるので小数の px まで正しい）。
 * [Paint.getTextBounds] は整数の px に丸めるうえ、大きな字では実際より広く返すので使わない。
 */
internal fun Paint.inkBounds(text: String, out: RectF) {
    val path = Path()
    getTextPath(text, 0, text.length, 0f, 0f, path)
    path.computeBounds(out, true)
}

private fun DrawScope.drawGlyphs(layout: TorifudaLayout) {
    drawIntoCanvas { canvas ->
        layout.glyphs.forEach { canvas.nativeCanvas.drawText(it.text, it.x, it.baseline, layout.paint) }
    }
}
