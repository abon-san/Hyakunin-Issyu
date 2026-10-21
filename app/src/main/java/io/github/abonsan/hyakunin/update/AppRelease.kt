package io.github.abonsan.hyakunin.update

import org.json.JSONObject

/** GitHub Releases に出ている版。 */
data class AppRelease(
    /** 版の名前（タグから先頭の `v` を外したもの。例:「0.7.0」）。 */
    val versionName: String,
    /** APK のダウンロード先。 */
    val apkUrl: String,
    /** APK の大きさ（バイト）。分からなければ 0。 */
    val apkSize: Long,
    /** リリースの注記。 */
    val notes: String,
    /** リリースのページ。 */
    val pageUrl: String,
)

/**
 * GitHub の「最新のリリース」の JSON（`/repos/{owner}/{repo}/releases/latest`）を読む。
 * 名前が `.apk` で終わる asset がなければ null（入れられるものがない）。JSON が壊れていれば [org.json.JSONException]。
 */
fun parseRelease(json: String): AppRelease? {
    val release = JSONObject(json)
    val assets = release.optJSONArray("assets") ?: return null
    val apk = (0 until assets.length())
        .map { assets.getJSONObject(it) }
        .firstOrNull { it.optString("name").endsWith(".apk", ignoreCase = true) }
        ?: return null
    return AppRelease(
        versionName = release.getString("tag_name").removePrefix("v"),
        apkUrl = apk.getString("browser_download_url"),
        apkSize = apk.optLong("size", 0L),
        notes = release.optString("body").takeUnless { release.isNull("body") }.orEmpty(),
        pageUrl = release.optString("html_url"),
    )
}

/**
 * 版の名前 [candidate] が [current] より新しいか。名前を「.」で分けた数の並び（「0.10.0」→ 0, 10, 0）として比べ、
 * 足りない桁は 0 とみなす（「0.7」と「0.7.0」は同じ）。先頭の `v` は外す。数として読めない名前は新しくないとみなす。
 */
fun isNewer(candidate: String, current: String): Boolean {
    val a = versionParts(candidate) ?: return false
    val b = versionParts(current) ?: return false
    for (i in 0 until maxOf(a.size, b.size)) {
        val x = a.getOrElse(i) { 0 }
        val y = b.getOrElse(i) { 0 }
        if (x != y) return x > y
    }
    return false
}

private fun versionParts(name: String): List<Int>? {
    val parts = name.trim().removePrefix("v").split('.')
    return parts.map { it.toIntOrNull() ?: return null }
}
