package io.github.abonsan.hyakunin.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.abonsan.hyakunin.ui.theme.Kincha
import io.github.abonsan.hyakunin.ui.theme.Tatami
import io.github.abonsan.hyakunin.ui.theme.TatamiHeri
import io.github.abonsan.hyakunin.ui.theme.TatamiLine
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 畳の地を描く。細い畳目の線と、[heriTop] / [heriBottom] に畳縁を付ける。
 */
fun Modifier.tatami(
    heriTop: Boolean = false,
    heriBottom: Boolean = false,
    heriWidth: Dp = 5.dp,
): Modifier = drawBehind {
    drawRect(Tatami)
    // 畳目: 2本ずつ濃淡を変えて、い草の織り目に見せる
    val pitch = 2.5.dp.toPx()
    val stroke = 0.6.dp.toPx()
    var y = pitch / 2
    var i = 0
    while (y < size.height) {
        val alpha = if (i % 2 == 0) 0.55f else 0.28f
        drawLine(TatamiLine.copy(alpha = alpha), Offset(0f, y), Offset(size.width, y), stroke)
        y += pitch
        i++
    }
    // 光の当たり方のむらを少しだけ付ける
    drawRect(
        Brush.horizontalGradient(
            0f to Color.White.copy(alpha = 0.10f),
            0.5f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.06f),
        ),
    )
    val heri = heriWidth.toPx()
    if (heriTop) drawHeri(top = 0f, height = heri)
    if (heriBottom) drawHeri(top = size.height - heri, height = heri)
}

private fun DrawScope.drawHeri(top: Float, height: Float) {
    drawRect(TatamiHeri, topLeft = Offset(0f, top), size = size.copy(height = height))
    // 縁の中ほどに金茶の細い線
    val lineY = top + height / 2
    drawLine(Kincha.copy(alpha = 0.7f), Offset(0f, lineY), Offset(size.width, lineY), 0.6.dp.toPx())
}

/**
 * 和紙の繊維のような細い線を薄く散らす。乱数の種を固定するので、同じ大きさなら毎回同じ模様になる。
 */
fun Modifier.washiFibers(
    color: Color = Kincha,
    alpha: Float = 0.12f,
    seed: Int = 1100,
): Modifier = drawWithCache {
    val random = Random(seed)
    val cell = 26.dp.toPx()
    val count = ((size.width / cell) * (size.height / cell)).toInt().coerceIn(0, 900)
    val path = Path()
    repeat(count) {
        val x = random.nextFloat() * size.width
        val y = random.nextFloat() * size.height
        val length = (3f + random.nextFloat() * 9f).dp.toPx()
        val angle = random.nextFloat() * PI.toFloat()
        val dx = cos(angle) * length
        val dy = sin(angle) * length
        // 少し曲げる
        val bend = (random.nextFloat() - 0.5f) * length * 0.5f
        path.moveTo(x, y)
        path.quadraticTo(x + dx / 2 - dy / length * bend, y + dy / 2 + dx / length * bend, x + dx, y + dy)
    }
    val stroke = Stroke(width = 0.6.dp.toPx(), cap = StrokeCap.Round)
    onDrawBehind { drawPath(path, color.copy(alpha = alpha), style = stroke) }
}

/**
 * 中身が下に続くとき（[scroll] がまだ下へ動かせるとき）、下の端を [color] に溶かして続きがあることを示す。
 * スクロールする要素より前（外側）に付ける。
 */
fun Modifier.fadingBottomEdge(
    scroll: ScrollState,
    color: Color,
    height: Dp = 20.dp,
): Modifier = drawWithContent {
    drawContent()
    if (scroll.canScrollForward) {
        val fade = height.toPx()
        drawRect(
            brush = Brush.verticalGradient(
                listOf(color.copy(alpha = 0f), color),
                startY = size.height - fade,
                endY = size.height,
            ),
            topLeft = Offset(0f, size.height - fade),
        )
    }
}

/**
 * 青海波（せいがいは）の文様を描く。[radius] は一つの波の半径。
 * 下の段が上の段に重なるので、波ごとに [background] で塗ってから輪を描く。
 */
fun DrawScope.drawSeigaiha(
    color: Color,
    background: Color,
    radius: Float,
    strokeWidth: Float,
    rings: Int = 4,
) {
    val dx = radius * 2f
    val dy = radius / 2f
    var row = 0
    var y = 0f
    while (y < size.height + radius) {
        var x = if (row % 2 == 0) 0f else -radius
        while (x < size.width + radius) {
            val center = Offset(x, y)
            drawCircle(background, radius, center)
            for (ring in 0 until rings) {
                val r = radius * (1f - ring / rings.toFloat()) - strokeWidth / 2
                if (r > 0f) drawCircle(color, r, center, style = Stroke(strokeWidth))
            }
            x += dx
        }
        y += dy
        row++
    }
}
