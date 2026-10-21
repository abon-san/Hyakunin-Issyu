package io.github.abonsan.hyakunin.update

import io.github.abonsan.hyakunin.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 最新の版を調べるところ。 */
fun interface ReleaseSource {
    /** 最新の版。リリースがない（入れられる APK がない）ときは null。調べられなければ例外を投げる。 */
    suspend fun latest(): AppRelease?
}

/** このアプリの最新のリリース（GitHub API）。 */
internal const val LATEST_RELEASE_URL = "https://api.github.com/repos/abon-san/Hyakunin-Issyu/releases/latest"

/** 通信の待ち時間の上限（ms）。 */
internal const val NETWORK_TIMEOUT_MILLIS = 15_000

/**
 * GitHub Releases の最新のリリースを、IO スレッドで調べる。リリースがひとつもない（404）なら null、
 * そのほかの HTTP の失敗は [IOException]。
 */
class GitHubReleaseSource(private val url: String = LATEST_RELEASE_URL) : ReleaseSource {
    override suspend fun latest(): AppRelease? = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
            connection.readTimeout = NETWORK_TIMEOUT_MILLIS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> parseRelease(connection.inputStream.bufferedReader().use { it.readText() })
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("HTTP $code: $url")
            }
        } finally {
            connection.disconnect()
        }
    }
}

/** GitHub に送るアプリの名前（GitHub API は User-Agent を求める）。 */
internal val USER_AGENT = "Hyakunin-Issyu/${BuildConfig.VERSION_NAME}"
