package io.github.abonsan.hyakunin.ui.card

import android.content.Context
import android.graphics.RectF
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.font.resolveAsTypeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.data.Poems
import io.github.abonsan.hyakunin.ui.theme.TorifudaFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** 取り札の字の置き方。どの札でも、どの大きさでも、字が縁にかからず重ならないか。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TorifudaLayoutTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val typeface = createFontFamilyResolver(context).resolveAsTypeface(TorifudaFont, FontWeight.SemiBold).value

    private fun layout(no: Int, width: Float) =
        layoutTorifuda(torifudaColumns(Poems.byNo(no).shimoKana), Size(width, width / TORIFUDA_ASPECT_RATIO), typeface)

    /** 字のインクの外接矩形（札の上の位置）。右の列から、列ごとに上から。 */
    private fun TorifudaLayout.inkBoxes(): List<RectF> = glyphs.map { glyph ->
        RectF().also {
            paint.inkBounds(glyph.text, it)
            it.offset(glyph.x, glyph.baseline)
        }
    }

    @Test
    fun glyphsStayInsideTheFaceWithoutOverlapping() {
        // 参照画像の大きさ、縦画面の一般的な札（72dp・xxhdpi）、横画面の小さい札（48dp・36dp、xhdpi）
        for (width in listOf(619.7f, 216f, 96f, 72f)) {
            val height = width / TORIFUDA_ASPECT_RATIO
            for (poem in Poems.all) {
                val layout = layout(poem.no, width)
                val boxes = layout.inkBoxes()
                val label = "${poem.no}番 幅 $width"
                for (box in boxes) {
                    assertTrue("$label: 縁にかかる $box", box.left >= layout.edge && box.right <= width - layout.edge)
                    assertTrue("$label: 縁にかかる $box", box.top >= layout.edge && box.bottom <= height - layout.edge)
                }
                // 同じ列の上下の字、隣り合う列の字が重ならない
                var start = 0
                val byColumn = torifudaColumns(poem.shimoKana).map { column ->
                    boxes.subList(start, start + column.length).also { start += column.length }
                }
                byColumn.forEach { column ->
                    column.zipWithNext { upper, lower -> assertTrue("$label: 上下の字が重なる", upper.bottom < lower.top) }
                }
                byColumn.zipWithNext { right, left ->
                    assertTrue("$label: 列が重なる", left.maxOf { it.right } < right.minOf { it.left })
                }
            }
        }
    }

    @Test
    fun alignsTheInkTopOfEachRow() {
        // 字の形によらず、インクの上端を行の線にそろえる（参照画像の札の 1px が 1px の大きさで）
        for (no in listOf(78, 58, 22)) {
            val layout = layout(no, 619.7f)
            val rows = torifudaColumns(Poems.byNo(no).shimoKana).flatMap { it.indices }
            layout.inkBoxes().zip(rows).forEach { (box, row) -> assertEquals("${no}番", 122f + 134.6f * row, box.top, 0.05f) }
        }
    }

    @Test
    fun centersTheEmBoxOnEachColumn() {
        // 列の中心は札の中心と、その左右に 158.9px（右の列から）
        val layout = layout(78, 619.7f)
        val centers = layout.glyphs.map { it.x + layout.paint.textSize / 2 }.distinct()
        val expected = listOf(309.85f + 158.9f, 309.85f, 309.85f - 158.9f)
        assertEquals(3, centers.size)
        centers.zip(expected).forEach { (actual, column) -> assertEquals(column, actual, 0.05f) }
    }

    @Test
    fun sixRowCardShrinksToFitTheSameArea() {
        // 21番（16字）だけが 6行。字の大きさと行の間隔を 5/6 にし、1行目の位置は変えない
        val six = layout(21, 619.7f)
        val five = layout(1, 619.7f)
        assertEquals(five.paint.textSize * 5 / 6, six.paint.textSize, 0.01f)
        val tops = six.inkBoxes().map { it.top }
        assertEquals(122f, tops[0], 0.05f)
        assertEquals(122f + 134.6f * 5 / 6 * 5, tops[5], 0.05f)
    }
}
