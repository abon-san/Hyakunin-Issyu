package io.github.abonsan.hyakunin.game.voice

import io.github.abonsan.hyakunin.data.Poem
import io.github.abonsan.hyakunin.game.SYNTHETIC_POEMS
import io.github.abonsan.hyakunin.game.kanaCount

/** テスト用の [VoiceBank]。渡した歌・序歌のクリップだけを返す。 */
internal class FakeVoiceBank(
    private val poems: Map<Int, PoemVoice> = emptyMap(),
    override val jokaKami: VoiceClip? = null,
    override val jokaShimo: VoiceClip? = null,
) : VoiceBank {
    override fun poem(no: Int): PoemVoice? = poems[no]
}

/**
 * 仮名 [count] 個を、[firstMs] から [stepMs] 間隔で読むクリップ。長さは最後の仮名の [tailMs] 後。
 * [tailMs] が下の句の余韻（約 3 秒）に当たる。
 */
internal fun fakeClip(asset: String, count: Int, firstMs: Int, stepMs: Int, tailMs: Long): VoiceClip {
    val starts = IntArray(count) { firstMs + stepMs * it }
    val last = if (count > 0) starts.last().toLong() else 0L
    return VoiceClip(asset, last + tailMs, starts)
}

/** 歌 [poem] のクリップ。上の句は 100ms から 200ms ごと（長さは最後の 400ms 後）、下の句は 100ms から 300ms ごと（余韻 3000ms）。 */
internal fun fakePoemVoice(poem: Poem): PoemVoice {
    val id = "p%03d".format(poem.no)
    return PoemVoice(
        kami = fakeClip("voice/${id}_kami.ogg", kanaCount(poem.kamiYomi), firstMs = 100, stepMs = 200, tailMs = 400L),
        shimo = fakeClip("voice/${id}_shimo.ogg", kanaCount(poem.shimoYomi), firstMs = 100, stepMs = 300, tailMs = 3000L),
    )
}

/** 序歌の上の句（仮名 17）。100ms から 100ms ごと、長さは最後の 200ms 後（1900ms）。 */
internal val FAKE_JOKA_KAMI: VoiceClip = fakeClip("voice/joka_kami.ogg", 17, firstMs = 100, stepMs = 100, tailMs = 200L)

/** 序歌の下の句（仮名 14）。100ms から 100ms ごと、余韻 3000ms（長さは 4400ms）。 */
internal val FAKE_JOKA_SHIMO: VoiceClip = fakeClip("voice/joka_shimo.ogg", 14, firstMs = 100, stepMs = 100, tailMs = 3000L)

/**
 * [poems]（[only] があればその歌番号だけ）に [fakePoemVoice] を持つ [VoiceBank]。[joka] が true なら序歌のクリップも持つ。
 * 合成の100首（上の句 16 仮名・下の句 10 仮名）では、上の句の長さが 3500ms、下の句の長さが 5800ms になる。
 */
internal fun fakeVoiceBank(
    poems: List<Poem> = SYNTHETIC_POEMS,
    only: Set<Int>? = null,
    joka: Boolean = true,
): FakeVoiceBank = FakeVoiceBank(
    poems = poems.filter { only == null || it.no in only }.associate { it.no to fakePoemVoice(it) },
    jokaKami = FAKE_JOKA_KAMI.takeIf { joka },
    jokaShimo = FAKE_JOKA_SHIMO.takeIf { joka },
)
