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

/**
 * 読み上げの速さ。
 * 文字だけのときは [charMillis]（仮名1文字あたりの表示間隔）、音声があるときは [playbackSpeed]（再生速度の倍率）を使う。
 */
enum class ReadingSpeed(val label: String, val charMillis: Long, val playbackSpeed: Float) {
    SLOW("ゆっくり", 320L, 0.85f),
    NORMAL("ふつう", 230L, 1.0f),
    FAST("はやい", 160L, 1.2f),
}

/**
 * 読み始めの流儀。前置き（序歌・前の歌の下の句）の有無を決める。
 * 札の取り方や勝敗は [RuleMode] で決まり、これは変えない。
 * 前置きは音声があるとき（[ReadTiming.voice]）、または [ReadTiming.textLeadIn] が true のときだけ入る。
 */
enum class ReadingStyle(val label: String) {
    /**
     * 競技かるた式。最初の札の前に序歌（上の句→下の句→下の句）、2枚目以降の前に直前の歌の下の句を読み、
     * 余韻と間合いを置いてから上の句を読む。前置きの間は札を取れない。
     */
    COMPETITIVE("競技かるた式"),

    /** 簡易式。前置きなしで、待ちのあとすぐ上の句→下の句を読む。 */
    SIMPLE("簡易式"),
}

/**
 * 句の表示に使う仮名遣い。表示だけに使い、エンジンの動作（決まり字・読み上げ・判定）は変えない。
 * エンジンは常に読み（現代仮名遣い）の仮名の数で数えるので、[KANA] の表示は `verseBoundaries` で文字数に変換する。
 */
enum class VerseStyle(val label: String, val description: String) {
    /** 読み上げるとおりの現代仮名遣い（例:「きょう」「ちょう」）。 */
    READING(
        label = "読み（現代仮名遣い）",
        description = "読み上げるとおりの現代仮名遣いで表示する。例: きょう、ちょう",
    ),

    /** 漢字をひらがなにしただけの歴史的仮名遣い（例:「けふ」「てふ」）。取り札と同じ表記。 */
    KANA(
        label = "歴史的仮名遣い",
        description = "漢字をひらがなに直しただけの表記（取り札と同じ）で表示する。例: けふ、てふ",
    ),
}

/** 次の札を読み始めるきっかけ。 */
enum class ReadStartMode(val label: String) {
    /** 開始前は [GameConfig.memorizeTime]、以後は [GameConfig.readInterval] だけ待って自動で読み始める。 */
    AUTO("自動"),

    /** 毎回、プレイヤーが「読み始める」を押すまで待つ。 */
    TAP("タップで読み始める"),
}

/** 最初の札を読むまでの、札を覚える時間（[ReadStartMode.AUTO] のとき）。 */
enum class MemorizeTime(val label: String, val millis: Long) {
    SEC_10("10秒", 10_000L),
    SEC_30("30秒", 30_000L),
    MIN_1("1分", 60_000L),
    MIN_2("2分", 120_000L),
}

/** 2枚目以降の札を読むまでの間（[ReadStartMode.AUTO] のとき）。 */
enum class ReadInterval(val label: String, val millis: Long) {
    SEC_2("2秒", 2_000L),
    SEC_4("4秒", 4_000L),
    SEC_6("6秒", 6_000L),
    SEC_10("10秒", 10_000L),
}

data class GameConfig(
    val ruleMode: RuleMode = RuleMode.COMPETITIVE,
    /** 空札（読まれるが場にない札）の枚数。0..[MAX_EMPTY_CARDS]。 */
    val emptyCardCount: Int = 10,
    val cpuLevel: CpuLevel = CpuLevel.INTERMEDIATE,
    val readingSpeed: ReadingSpeed = ReadingSpeed.NORMAL,
    /** 敵陣の札を相手向き（上下逆さ）に表示するか。 */
    val flipOpponentCards: Boolean = true,
    /** 句の表示に使う仮名遣い。表示だけに使う設定で、エンジンの動作は変えない。 */
    val verseStyle: VerseStyle = VerseStyle.KANA,
    val readStartMode: ReadStartMode = ReadStartMode.AUTO,
    val memorizeTime: MemorizeTime = MemorizeTime.SEC_30,
    val readInterval: ReadInterval = ReadInterval.SEC_4,
    /** 読み始めの流儀。既定は [ReadingStyle.COMPETITIVE]（UI が [ruleMode] に合わせて選ぶ）。 */
    val readingStyle: ReadingStyle = ReadingStyle.COMPETITIVE,
    /** 読み上げ音声を使うか。[ReadTiming.voice] が渡されているときだけ意味がある。false なら文字だけで進める。 */
    val voiceEnabled: Boolean = true,
    /**
     * 読み上げ音声の音量。クリップそのままの大きさを 1 とした倍率で、0..[MAX_VOICE_VOLUME]。
     * 再生器に渡すだけで、エンジンの動作は変えない。
     */
    val voiceVolume: Float = 1f,
) {
    init {
        require(emptyCardCount in 0..MAX_EMPTY_CARDS) { "emptyCardCount=$emptyCardCount" }
        require(voiceVolume in 0f..MAX_VOICE_VOLUME) { "voiceVolume=$voiceVolume" }
    }

    companion object {
        /** 各陣に最初に並べる札の枚数。 */
        const val CARDS_PER_SIDE = 5
        const val MAX_EMPTY_CARDS = 100 - 2 * CARDS_PER_SIDE

        /**
         * 読み上げ音声の音量の上限（200%、クリップそのままの大きさの 2 倍）。
         * クリップの山は約 −7 dBFS なので、2 倍（+6 dB）でも割れない。
         */
        const val MAX_VOICE_VOLUME = 2f
    }
}
