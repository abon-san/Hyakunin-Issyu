package io.github.abonsan.hyakunin.data

object Poems {
    /** 歌番号順の100首。 */
    val all: List<Poem> = POEMS_1_TO_50 + POEMS_51_TO_100

    fun byNo(no: Int): Poem = all[no - 1]
}
