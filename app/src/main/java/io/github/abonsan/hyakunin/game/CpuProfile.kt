package io.github.abonsan.hyakunin.game

/**
 * コンピュータの強さごとの振る舞い。
 *
 * @property reactionMinMillis 決まり字を聞いてから札を取るまでの反応時間の下限（ms）
 * @property reactionMaxMillis 同上限（ms）
 * @property faultProbability 読みごとに「友札」へ誤って飛び出す確率（0.0〜1.0）
 */
data class CpuProfile(
    val reactionMinMillis: Long,
    val reactionMaxMillis: Long,
    val faultProbability: Double,
) {
    init {
        require(reactionMinMillis in 0..reactionMaxMillis) { "reaction=$reactionMinMillis..$reactionMaxMillis" }
        require(faultProbability in 0.0..1.0) { "faultProbability=$faultProbability" }
    }

    companion object {
        fun of(level: CpuLevel): CpuProfile = when (level) {
            CpuLevel.BEGINNER -> CpuProfile(1400L, 2600L, 0.10)
            CpuLevel.INTERMEDIATE -> CpuProfile(750L, 1500L, 0.05)
            CpuLevel.ADVANCED -> CpuProfile(350L, 800L, 0.02)
            CpuLevel.MASTER -> CpuProfile(150L, 400L, 0.0)
        }
    }
}
