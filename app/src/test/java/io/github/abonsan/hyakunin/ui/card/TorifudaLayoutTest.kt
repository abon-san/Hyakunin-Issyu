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

    /** [no] 番の札に書く列（濁点を外し、5字・5字・残りに分けたもの）。 */
    private fun columns(no: Int): List<String> = torifudaColumns(withoutDakuten(Poems.byNo(no).shimoKana))

    private fun layout(no: Int, width: Float) =
        layoutTorifuda(columns(no), Size(width, width / TORIFUDA_ASPECT_RATIO), typeface)

    /** 字のインクの外接矩形（札の上の位置）を列ごとに分けたもの。右の列から、列ごとに上から。 */
    private fun TorifudaLayout.inkBoxes(columns: List<String>): List<List<RectF>> {
        val boxes = glyphs.map { glyph ->
            RectF().also {
                paint.inkBounds(glyph.text, it)
                it.offset(glyph.x, glyph.baseline)
            }
        }
        var start = 0
        return columns.map { column -> boxes.subList(start, start + column.length).also { start += column.length } }
    }

    @Test
    fun glyphsStayInsideTheFaceWithoutOverlapping() {
        for (width in WIDTHS) {
            val height = width / TORIFUDA_ASPECT_RATIO
            for (poem in Poems.all) {
                val layout = layout(poem.no, width)
                val byColumn = layout.inkBoxes(columns(poem.no))
                val label = "${poem.no}番 幅 $width"
                for (box in byColumn.flatten()) {
                    assertTrue("$label: 縁にかかる $box", box.left >= layout.edge && box.right <= width - layout.edge)
                    assertTrue("$label: 縁にかかる $box", box.top >= layout.edge && box.bottom <= height - layout.edge)
                }
                // 同じ列の上下の字（6字の列も）、隣り合う列の字が重ならない
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
        // 5字までの列は、字の形によらず、インクの上端をデザインの行の線（122 + 134.6 × 行）にそろえる
        // （参照画像の札の 1px が 1px の大きさで）。22番は 5・5・5、78番・58番は 5・5・4
        for (no in listOf(78, 58, 22)) {
            layout(no, 619.7f).inkBoxes(columns(no)).forEach { column ->
                column.forEachIndexed { row, box -> assertEquals("${no}番", 122f + 134.6f * row, box.top, 0.05f) }
            }
        }
    }

    @Test
    fun sixCharacterColumnIsEvenlySpacedBetweenTheFirstAndFifthRows() {
        // 21番（16字）は 5・5・6。3列目「いてつるかな」の6字は、インクの上端を1行目の線（122）から
        // 5行目の線（122 + 134.6 × 4 = 660.4）まで等間隔（107.68）に置く。1列目・2列目は格子のまま
        assertEquals(listOf("ありあけの", "つきをまち", "いてつるかな"), columns(21))
        for (width in WIDTHS) {
            val height = width / TORIFUDA_ASPECT_RATIO
            val label = "幅 $width"
            val (right, middle, left) = layout(21, width).inkBoxes(columns(21))
            for (column in listOf(right, middle)) {
                column.forEachIndexed { row, box -> assertEquals(label, height * (122f + 134.6f * row) / 870f, box.top, 0.01f) }
            }
            left.forEachIndexed { row, box -> assertEquals(label, height * (122f + 107.68f * row) / 870f, box.top, 0.01f) }
            // 最後の字は、5字の列の最後の字と同じ高さ（5行目の線）から書く
            assertEquals(label, right.last().top, left.last().top, 0.01f)
            assertEquals(label, middle.last().top, left.last().top, 0.01f)
            // 詰めた列でも字は重ならない
            left.zipWithNext { upper, lower -> assertTrue("$label: 上下の字が重なる", upper.bottom < lower.top) }
            // 字の大きさは5字の列と同じ（「つ」は2列目にも3列目にもある）
            val tsu = middle[0] to left[2]
            assertEquals(label, tsu.first.width(), tsu.second.width(), 0.01f)
            assertEquals(label, tsu.first.height(), tsu.second.height(), 0.01f)
        }
    }

    @Test
    fun everyCardUsesTheSameGlyphSize() {
        // 字の大きさは札の幅の 109.8/619.7。6字の列のある21番も、ほかの札と同じ大きさで書く
        for (width in WIDTHS) {
            for (poem in Poems.all) {
                assertEquals("${poem.no}番 幅 $width", width * 109.8f / 619.7f, layout(poem.no, width).paint.textSize, 0.001f)
            }
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
}

/** 確かめる札の幅（px）。参照画像の大きさ、縦画面の一般的な札（72dp・xxhdpi）、横画面の小さい札（48dp・36dp、xhdpi）。 */
private val WIDTHS = listOf(619.7f, 216f, 96f, 72f)
