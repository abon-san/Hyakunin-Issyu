package io.github.abonsan.hyakunin.game

/** 陣（札が置かれている側、または対戦者）。 */
enum class Side {
    PLAYER,
    CPU;

    val opponent: Side get() = if (this == PLAYER) CPU else PLAYER
}

enum class RuleMode(val label: String, val description: String) {
    COMPETITIVE(
        label = "競技かるた",
        description = "自陣の札が先になくなった方が勝ち。敵陣の札を取ると自陣から1枚送る。お手つきは相手から1枚送られる。",
    ),
    SIMPLE(
        label = "簡易",
        description = "取った札の枚数で勝敗を決める。誤って触れた場合はその回は取れなくなるだけ。",
    ),
}

/** コンピュータの強さ。反応時間とお手つきの確率は [CpuProfile] で定義する。 */
enum class CpuLevel(val label: String) {
    BEGINNER("初級"),
    INTERMEDIATE("中級"),
    ADVANCED("上級"),
    MASTER("名人"),
}

/** 読み上げ（文字表示）の速度。[charMillis] は仮名1文字あたりの表示間隔。 */
enum class ReadingSpeed(val label: String, val charMillis: Long) {
    SLOW("ゆっくり", 320L),
    NORMAL("ふつう", 230L),
    FAST("はやい", 160L),
}

data class GameConfig(
    val ruleMode: RuleMode = RuleMode.COMPETITIVE,
    /** 空札（読まれるが場にない札）の枚数。0..[MAX_EMPTY_CARDS]。 */
    val emptyCardCount: Int = 10,
    val cpuLevel: CpuLevel = CpuLevel.INTERMEDIATE,
    val readingSpeed: ReadingSpeed = ReadingSpeed.NORMAL,
    /** 敵陣の札を相手向き（上下逆さ）に表示するか。 */
    val flipOpponentCards: Boolean = true,
) {
    init {
        require(emptyCardCount in 0..MAX_EMPTY_CARDS) { "emptyCardCount=$emptyCardCount" }
    }

    companion object {
        /** 各陣に最初に並べる札の枚数。 */
        const val CARDS_PER_SIDE = 5
        const val MAX_EMPTY_CARDS = 100 - 2 * CARDS_PER_SIDE
    }
}
