package io.github.abonsan.hyakunin.ui.card

import android.content.Context
import android.graphics.Paint
import android.graphics.fonts.Font
import android.graphics.text.TextRunShaper
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.font.resolveAsTypeface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.R
import io.github.abonsan.hyakunin.data.Poems
import io.github.abonsan.hyakunin.ui.theme.TorifudaFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 取り札のフォント（しっぽり明朝 SemiBold の仮名だけのサブセット）に、取り札に書く字がすべてあるか。
 * 足りない字は端末のフォントで補われて書体が混ざるので、字を描くフォントまで確かめる。
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class TorifudaFontTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val subset = Font.Builder(context.resources, R.font.shippori_mincho_semibold).build()
    private val paint = Paint().apply {
        typeface = createFontFamilyResolver(context).resolveAsTypeface(TorifudaFont, FontWeight.SemiBold).value
        textSize = 100f
        fontFeatureSettings = TORIFUDA_FONT_FEATURES
    }

    /** [text]（1字）を [paint] で描くときの、サブセットのグリフ番号。サブセットに無ければ null。 */
    private fun glyphInSubset(text: String, paint: Paint = this.paint): Int? {
        val glyphs = TextRunShaper.shapeTextRun(text, 0, text.length, 0, text.length, 0f, 0f, false, paint)
        if (glyphs.glyphCount() != 1 || glyphs.getFont(0) != subset) return null
        return glyphs.getGlyphId(0).takeIf { it != 0 }
    }

    @Test
    fun hasEveryCharacterOfTheLowerVerses() {
        // 札に書く字（濁点を外したもの）と、参照画像と比べる札に書く字（濁点つき）
        val chars = Poems.all
            .flatMap { poem -> (withoutDakuten(poem.shimoKana) + poem.shimoKana).filterNot { it.isWhitespace() }.toList() }
            .toSortedSet()
        val missing = chars.filter { glyphInSubset(it.toString()) == null }
        assertEquals("サブセットに無い字", "", missing.joinToString(""))
    }

    @Test
    fun detectsCharactersOutsideTheSubset() {
        // 漢字はサブセットに無く、端末のフォントで補われる
        assertNull(glyphInSubset("山"))
    }

    @Test
    fun keepsTheVerticalForms() {
        // 小書きの仮名や長音符は、縦書き用の字形（vert）に置き換わる
        val horizontal = Paint(paint).apply { fontFeatureSettings = null }
        for (char in listOf("ゃ", "っ", "ー", "ャ")) {
            val vertical = glyphInSubset(char)
            assertNotNull(char, vertical)
            assertNotEquals(char, glyphInSubset(char, horizontal), vertical)
        }
    }
}
