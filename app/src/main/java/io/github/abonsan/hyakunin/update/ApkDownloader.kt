package io.github.abonsan.hyakunin.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 新しい版の APK を落とすもの。 */
fun interface ApkDownloader {
    /**
     * [release] の APK を落として、そのファイルを返す。進み具合（0〜1。大きさが分からなければ null）を [onProgress] で
     * 知らせる（どのスレッドからでも呼ぶ）。落とせなければ例外を投げる。止める（コルーチンを取り消す）と、途中のファイルは消す。
     */
    suspend fun download(release: AppRelease, onProgress: (Float?) -> Unit): File
}

/**
 * APK を [dir]（アプリのキャッシュの中）に落とす。前に落としたものは、落とし始める前に消す。
 * 落とした大きさがリリースに書かれた大きさと違えば、壊れているとみなして [IOException] にする。
 */
class HttpApkDownloader(private val dir: File) : ApkDownloader {
    override suspend fun download(release: AppRelease, onProgress: (Float?) -> Unit): File = withContext(Dispatchers.IO) {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "hyakunin-issyu-${release.versionName}.apk")
        val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = NETWORK_TIMEOUT_MILLIS
            connection.readTimeout = NETWORK_TIMEOUT_MILLIS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code: ${release.apkUrl}")
            val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.apkSize.takeIf { it > 0 }
            onProgress(if (total != null) 0f else null)
            connection.inputStream.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var done = 0L
                    var lastPercent = 0
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        done += read
                        if (total != null) {
                            // 1% ごとに知らせる（画面を描き直しすぎない）
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress((done.toFloat() / total).coerceAtMost(1f))
                            }
                        }
                    }
                }
            }
            if (release.apkSize > 0 && file.length() != release.apkSize) {
                throw IOException("${file.length()} bytes, expected ${release.apkSize}")
            }
            file
        } catch (e: Throwable) {
            file.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
