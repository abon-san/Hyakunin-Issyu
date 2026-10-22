package io.github.abonsan.hyakunin.ui.quiz

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.abonsan.hyakunin.data.Poems
import io.github.abonsan.hyakunin.game.VerseStyle
import io.github.abonsan.hyakunin.game.quiz.QuizConfig
import io.github.abonsan.hyakunin.game.quiz.QuizUiState
import io.github.abonsan.hyakunin.game.quiz.QuizViewModel
import io.github.abonsan.hyakunin.ui.captureScreenshot
import io.github.abonsan.hyakunin.ui.theme.HyakuninTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.random.Random

/**
 * 「札を覚える」の設定・出題・解答後・結果を撮る。画面の大きさと向きはサブクラスの @Config で決める。
 * 画像の名前は「[prefix]_場面」。
 */
abstract class QuizScreensScreenshotTest(private val prefix: String) {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun capture(scene: String) = composeRule.captureScreenshot("${prefix}_$scene")

    /** 31番「あさぼらけ ありあけの」を出す練習。 */
    private fun quiz() = QuizViewModel(listOf(Poems.byNo(31)), random = Random(3))

    private fun show(state: QuizUiState) {
        composeRule.setContent {
            HyakuninTheme {
                QuizScreen(
                    state = state,
                    verseStyle = VerseStyle.KANA,
                    onChoose = {},
                    onReplay = {},
                    onNext = {},
                    onRestart = {},
                    onRestartMissed = {},
                    onBack = {},
                    onExit = {},
                )
            }
        }
    }

    @Test
    fun setup() {
        composeRule.setContent {
            HyakuninTheme {
                QuizSetupScreen(
                    config = QuizConfig(),
                    missedCount = 3,
                    poemCount = 100,
                    onConfigChange = {},
                    onStart = {},
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText(quizCountText(100)).assertExists()
        capture("setup")
    }

    @Test
    fun setupWithoutPoems() {
        composeRule.setContent {
            HyakuninTheme {
                QuizSetupScreen(
                    config = QuizConfig(missedOnly = true),
                    missedCount = 0,
                    poemCount = 0,
                    onConfigChange = {},
                    onStart = {},
                    onBack = {},
                )
            }
        }
        composeRule.onNodeWithText(missedDescription(0)).assertExists()
        composeRule.onNodeWithText(QUIZ_START_LABEL).assertIsNotEnabled()
    }

    @Test
    fun asking() {
        val vm = quiz()
        // 「あさぼらけ　ありあけの」まで読んだ
        vm.reveal(10)
        show(vm.uiState.value)
        composeRule.onNodeWithContentDescription("あさぼらけありあけの").assertExists()
        composeRule.onNodeWithText(REPLAY_LABEL).assertExists()
        capture("asking")
    }

    @Test
    fun askingThroughTheShimo() {
        val vm = QuizViewModel(listOf(Poems.byNo(31)), readsShimo = true, random = Random(3))
        // 上の句を読み終え、下の句の「よしのの」まで読んだ
        vm.reveal(vm.uiState.value.question.kamiKanaCount + 4)
        show(vm.uiState.value)
        composeRule.onNodeWithContentDescription("あさぼらけありあけのつきとみるまでによしのの").assertExists()
        capture("asking_shimo")
    }

    @Test
    fun answeredWrong() {
        val vm = quiz()
        vm.reveal(7)
        vm.answer(64)
        show(vm.uiState.value)
        composeRule.onNodeWithText(WRONG_VERDICT).assertExists()
        composeRule.onNodeWithText(CHOSEN_CARD_LABEL).assertExists()
        composeRule.onNodeWithText(ANSWER_CARD_LABEL).assertExists()
        composeRule.onNodeWithText(kimarijiNote("あさぼらけあ")).assertExists()
        capture("answered_wrong")
    }

    @Test
    fun result() {
        val vm = QuizViewModel(listOf(31, 64, 18, 87).map(Poems::byNo), random = Random(3))
        repeat(4) {
            val state = vm.uiState.value
            val no = state.question.poem.no
            vm.answer(if (no == 31 || no == 87) state.question.choices.first { it.no != no }.no else no)
            vm.next()
        }
        show(vm.uiState.value)
        composeRule.onNodeWithText(scoreText(2, 4)).assertExists()
        composeRule.onNodeWithText(RETRY_MISSED_LABEL).assertExists()
        capture("result")
    }
}

/** 一般的な大きさのスマホの縦画面。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w411dp-h891dp-xxhdpi")
class QuizScreenshotTest : QuizScreensScreenshotTest(prefix = "quiz")

/** 小さめのスマホの縦画面。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w360dp-h640dp-xhdpi")
class SmallQuizScreenshotTest : QuizScreensScreenshotTest(prefix = "small_quiz")

/** 一般的な大きさのスマホの横画面。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w891dp-h411dp-land-xxhdpi")
class LandscapeQuizScreenshotTest : QuizScreensScreenshotTest(prefix = "land_quiz")

/** 小さめのスマホの横画面。 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36], qualifiers = "w640dp-h360dp-land-xhdpi")
class SmallLandscapeQuizScreenshotTest : QuizScreensScreenshotTest(prefix = "land_small_quiz")
