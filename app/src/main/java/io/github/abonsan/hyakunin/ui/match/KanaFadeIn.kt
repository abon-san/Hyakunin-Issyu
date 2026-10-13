package io.github.abonsan.hyakunin.ui.match

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.MotionDurationScale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/** 読み上げた文字が、透明から元の色になるまでの時間（ms）。 */
internal const val KANA_FADE_MILLIS = 200

/** 文字の浮かび上がり方。初めに速く濃くなり、終わりはゆっくり落ち着く（ease-out）。 */
internal val KanaFadeEasing: Easing = EaseOut

/**
 * 句の文字の浮かび上がり（字幕の1字ずつのフェードイン）。見せる文字の数が増えたら（[show]）、増えた文字を透明から
 * [KANA_FADE_MILLIS] かけて元の色にする。一度に増えた文字は一緒に、別々に増えた文字はそれぞれ増えた時刻から
 * （前の文字と重なりながら）浮かび上がる。
 *
 * - 作ったときにもう見せている文字（画面を開いた・回した・戻ってきたとき）は、すぐに元の色で見せる
 * - 数が減った（同じ下の句を初めから見せ直す）ら、その文字はすぐに消す
 * - 不透明度は描くとき（graphicsLayer）だけに読む。文字の配置は変わらず、浮かび上がっている間も句を組み直さない。
 *   浮かび上がっている文字がなければ、毎フレームの仕事はない
 *
 * 文字の通し番号は [Verse] と同じ（表示の文字で数え、上の句の先頭を 0 とする）。
 */
@Stable
internal class KanaFadeIn(initiallyShown: Int) {
    /** 浮かび上がっている最中の文字。 */
    private val rising = mutableStateListOf<RisingKana>()

    /** [show] で受け取った、見せる文字の数。 */
    private var received by mutableIntStateOf(initiallyShown)

    /** 通し番号 [index] の文字を、元の色で見せてよいか（見せていて、浮かび上がりも終えている）。 */
    fun isSettled(index: Int): Boolean = index < received && rising.none { index in it }

    /** [line] の文字のうち、浮かび上がっている最中のもの（一度に見せたまとまりごと）。 */
    fun risingIn(line: VerseLine): List<RisingKana> {
        val end = line.firstKana + line.text.length
        return rising.filter { it.from < end && it.until > line.firstKana }
    }

    /** 浮かび上がっている最中の文字があるか（なければ毎フレームの仕事はない）。 */
    val fading: Boolean get() = rising.isNotEmpty()

    /** 通し番号 [index] の文字が浮かび上がっている最中か。 */
    fun isRising(index: Int): Boolean = rising.any { index in it }

    /** 通し番号 [index] の文字の不透明度（0〜1）。見せていなければ 0、浮かび上がり終えていれば 1。 */
    fun alphaOf(index: Int): Float = when {
        isSettled(index) -> 1f
        index >= received -> 0f
        else -> rising.firstOrNull { index in it }?.alpha ?: 0f
    }

    /**
     * 見せる文字の数が [count] になった。増えた文字は [scope] で浮かび上がらせ始める（[animate] が false なら、
     * すぐに元の色で見せる）。
     */
    fun show(count: Int, scope: CoroutineScope, animate: Boolean) {
        if (count > received && animate) {
            val kana = RisingKana(received, count)
            rising += kana
            kana.job = scope.launch {
                kana.rise()
                rising -= kana
            }
        } else if (count < received) {
            // 見せ直す: 消える文字を含む浮かび上がりはやめる（残る文字は元の色で見せる）
            for (kana in rising.filter { it.until > count }) {
                kana.job?.cancel()
                rising -= kana
            }
        }
        received = count
    }
}

/** 一度に見せた文字（通し番号 [from] から [until] の手前まで）。一緒に浮かび上がる。 */
@Stable
internal class RisingKana(val from: Int, val until: Int) {
    private val animatable = Animatable(0f)

    /** 今の不透明度。描くときだけに読む。 */
    val alpha: Float get() = animatable.value

    var job: Job? = null

    operator fun contains(index: Int): Boolean = index in from until until

    suspend fun rise() {
        animatable.animateTo(1f, tween(KANA_FADE_MILLIS, easing = KanaFadeEasing))
    }
}

/**
 * 句 [key] の文字の浮かび上がりを覚えておき、見せる文字の数 [shown] の変化を追う。端末の「アニメーションを削除」が
 * オン（アニメーションの速さが 0）なら null を返す（浮かび上がらせずに、すぐ見せる）。
 */
@Composable
internal fun rememberKanaFadeIn(key: Any, shown: Int): KanaFadeIn? {
    val fade = remember(key) { KanaFadeIn(shown) }
    val currentShown by rememberUpdatedState(shown)
    LaunchedEffect(fade) {
        val scope = this
        snapshotFlow { currentShown }.collect { fade.show(it, scope, animate = !scope.coroutineContext.animationsOff) }
    }
    val animationsOff = rememberCoroutineScope().coroutineContext.animationsOff
    return if (animationsOff) null else fade
}

/** アニメーションの速さが 0（端末の「アニメーションを削除」）か。 */
private val CoroutineContext.animationsOff: Boolean
    get() = (this[MotionDurationScale]?.scaleFactor ?: 1f) == 0f
