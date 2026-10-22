package io.github.abonsan.hyakunin.ui.quiz

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.game.quiz.KimarijiGroup
import io.github.abonsan.hyakunin.game.quiz.PoemNumberRange
import io.github.abonsan.hyakunin.game.quiz.QuizConfig
import io.github.abonsan.hyakunin.game.quiz.QuizReadRange
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class QuizStoreTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("game_config", Context.MODE_PRIVATE)

    @Test
    fun defaultsWhenNothingIsSaved() {
        val store = QuizStore(context)
        assertEquals(QuizConfig(), store.loadConfig())
        assertEquals(emptySet<Int>(), store.missed())
    }

    @Test
    fun theRangeIsSaved() {
        val config = QuizConfig(PoemNumberRange.FROM_61, KimarijiGroup.THREE, missedOnly = true, QuizReadRange.THROUGH_SHIMO)
        QuizStore(prefs).saveConfig(config)
        assertEquals(config, QuizStore(prefs).loadConfig())
    }

    @Test
    fun aWrongAnswerIsRememberedUntilItIsAnsweredRight() {
        val store = QuizStore(prefs)
        store.recordAnswer(18, correct = false)
        store.recordAnswer(87, correct = false)
        store.recordAnswer(3, correct = true)
        assertEquals(setOf(18, 87), QuizStore(prefs).missed())
        // 同じ札をまた間違えても1つ
        store.recordAnswer(18, correct = false)
        assertEquals(setOf(18, 87), QuizStore(prefs).missed())
        store.recordAnswer(18, correct = true)
        assertEquals(setOf(87), QuizStore(prefs).missed())
        assertEquals("87", prefs.getString("quiz.missed", null))
    }

    @Test
    fun unreadableValuesFallBackToTheDefaults() {
        prefs.edit {
            putString("quiz.number_range", "NOPE")
            putInt("quiz.kimariji", 3)
            putString("quiz.missed_only", "yes")
            putString("quiz.read_range", "ALL")
            putString("quiz.missed", "18,x,,0,101,87")
        }
        val store = QuizStore(prefs)
        assertEquals(QuizConfig(), store.loadConfig())
        // 読める歌番号だけを使う
        assertEquals(setOf(18, 87), store.missed())
    }
}
