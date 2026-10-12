package io.github.abonsan.hyakunin.game.voice

import android.content.res.AssetManager
import android.util.Log
import androidx.annotation.WorkerThread
import io.github.abonsan.hyakunin.data.Joka
import io.github.abonsan.hyakunin.data.Poem
import io.github.abonsan.hyakunin.data.Poems
import io.github.abonsan.hyakunin.game.kanaCount
import io.github.abonsan.hyakunin.game.stripYomiSpaces
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * アプリのアセットに入れた読み上げ音声（[DIR] の timings.json と OGG）。
 *
 * timings.json は tools/voice/install_voice_assets.py が書く。形は
 * `{"clips": [{"file": "001_kami.ogg", "poem": 1（序歌は "joka"）, "part": "kami"（または "shimo"）,
 * "reading": 読み, "durationMs": 長さ, "kanaStartMs": [仮名ごとの開始時刻]}, …]}`。
 *
 * クリップは歌の読みと照らし合わせ（[VoiceClip.fits]）、合わないもの・ファイルがないものは使わずにログに残す。
 * 上の句と下の句の片方でも使えない歌は音声なしになり、エンジンはその歌を文字だけの時刻表で読む。
 */
class AssetVoiceBank private constructor(
    private val poems: Map<Int, PoemVoice>,
    override val jokaKami: VoiceClip?,
    override val jokaShimo: VoiceClip?,
) : VoiceBank {
    override fun poem(no: Int): PoemVoice? = poems[no]

    /** 音声で読める歌の数（序歌を除く）。 */
    val poemCount: Int get() = poems.size

    override fun toString(): String = "AssetVoiceBank($poemCount poems, joka=${jokaKami != null && jokaShimo != null})"

    companion object {
        /** 冥鳴ひまりの音声を入れたアセットのフォルダ。 */
        const val DIR = "voice/himari"

        /** 時刻表のファイル名。 */
        const val TIMINGS = "timings.json"

        private const val TAG = "AssetVoiceBank"
        private const val JOKA = "joka"

        /**
         * [assets] の [dir] から読み込む。時刻表がない・読めないとき、使えるクリップが1つもないときは null（音声なし）。
         * 例外は投げない。ファイルを読むので、メインスレッドでは呼ばないこと。
         */
        @WorkerThread
        fun load(assets: AssetManager, dir: String = DIR, poems: List<Poem> = Poems.all): AssetVoiceBank? {
            val files = try {
                assets.list(dir)?.toSet().orEmpty()
            } catch (e: IOException) {
                Log.w(TAG, "$dir を一覧できない", e)
                emptySet()
            }
            if (TIMINGS !in files) {
                Log.i(TAG, "$dir/$TIMINGS がないので、音声なしで読む")
                return null
            }
            val json = try {
                assets.open("$dir/$TIMINGS").bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                Log.w(TAG, "$dir/$TIMINGS を読めない", e)
                return null
            }
            return parse(json, dir, poems, files)
        }

        /**
         * 時刻表 [json] から作る。クリップの [VoiceClip.asset] は「[dir]/ファイル名」。
         * [files] を渡すと、そこにないファイルのクリップは使わない。合わないクリップは [warn] に理由を渡す。
         */
        internal fun parse(
            json: String,
            dir: String,
            poems: List<Poem> = Poems.all,
            files: Set<String>? = null,
            warn: (String) -> Unit = { Log.w(TAG, it) },
        ): AssetVoiceBank? {
            val clips = try {
                JSONObject(json).getJSONArray("clips")
            } catch (e: JSONException) {
                warn("$TIMINGS を読めない: ${e.message}")
                return null
            }
            val byNo = poems.associateBy { it.no }
            val found = LinkedHashMap<ClipKey, VoiceClip>()
            for (i in 0 until clips.length()) {
                val entry = clips.optJSONObject(i)
                if (entry == null) {
                    warn("clips[$i] が項目ではない")
                    continue
                }
                // 別読み（公式の読みの別版）は使わない
                if (entry.optBoolean("alt")) continue
                val (key, clip) = readClip(entry, dir, byNo, files, warn) ?: continue
                if (key in found) {
                    warn("${entry.optString("file")}: 同じ句のクリップが2つある（先のものを使う）")
                    continue
                }
                found[key] = clip
            }
            val voices = LinkedHashMap<Int, PoemVoice>()
            for (poem in poems) {
                val kami = found[ClipKey(poem.no, kami = true)]
                val shimo = found[ClipKey(poem.no, kami = false)]
                if (kami != null && shimo != null) {
                    voices[poem.no] = PoemVoice(kami, shimo)
                } else if (kami != null || shimo != null) {
                    warn("${poem.no}番は上の句と下の句の音声がそろわないので、文字だけで読む")
                }
            }
            val jokaKami = found[ClipKey(Joka.poem.no, kami = true)]
            val jokaShimo = found[ClipKey(Joka.poem.no, kami = false)]
            if (voices.isEmpty() && jokaKami == null && jokaShimo == null) {
                warn("使える音声がないので、音声なしで読む")
                return null
            }
            val missing = poems.size - voices.size
            if (missing > 0) warn("音声なしで読む歌が $missing 首ある")
            return AssetVoiceBank(voices, jokaKami, jokaShimo)
        }

        /** 1つのクリップを読み、歌の読みと照らし合わせる。使えなければ理由を [warn] に渡して null。 */
        private fun readClip(
            entry: JSONObject,
            dir: String,
            poems: Map<Int, Poem>,
            files: Set<String>?,
            warn: (String) -> Unit,
        ): Pair<ClipKey, VoiceClip>? {
            val file = entry.optString("file")
            val no = when (val poem = entry.opt("poem")) {
                JOKA -> Joka.poem.no
                is Int -> poem
                else -> null
            }
            val kami = when (entry.optString("part")) {
                "kami" -> true
                "shimo" -> false
                else -> null
            }
            if (file.isEmpty() || no == null || kami == null) {
                warn("file・poem・part のない項目: $entry")
                return null
            }
            val poem = if (no == Joka.poem.no) Joka.poem else poems[no]
            if (poem == null) {
                warn("$file: ${no}番の歌がない")
                return null
            }
            if (files != null && file !in files) {
                warn("$file: ファイルがない")
                return null
            }
            val yomi = if (kami) poem.kamiYomi else poem.shimoYomi
            val reading = entry.optString("reading")
            if (reading.isNotEmpty() && stripYomiSpaces(reading) != stripYomiSpaces(yomi)) {
                warn("$file: 読みが違う（時刻表「$reading」、歌「$yomi」）")
                return null
            }
            val clip = try {
                val starts = entry.getJSONArray("kanaStartMs")
                val duration = entry.getLong("durationMs")
                require(duration > 0L) { "durationMs=$duration" }
                VoiceClip("$dir/$file", duration, IntArray(starts.length()) { starts.getInt(it) })
            } catch (e: JSONException) {
                warn("$file: 時刻表を読めない: ${e.message}")
                return null
            } catch (e: IllegalArgumentException) {
                warn("$file: 時刻表がおかしい: ${e.message}")
                return null
            }
            if (!clip.fits(yomi)) {
                warn("$file: 仮名の数が合わない（時刻表 ${clip.kanaStartMs.size}、読み ${kanaCount(yomi)}）")
                return null
            }
            return ClipKey(no, kami) to clip
        }
    }

    /** 句の見分け。[no] は歌番号（序歌は 0）。 */
    private data class ClipKey(val no: Int, val kami: Boolean)
}
