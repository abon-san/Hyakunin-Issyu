package io.github.abonsan.hyakunin.update

import kotlinx.coroutines.CompletableDeferred
import java.io.File

/** テスト用のリリース。 */
internal fun testRelease(version: String = "0.8.0", notes: String = "札を覚える機能を足しました") = AppRelease(
    versionName = version,
    apkUrl = "https://github.com/abon-san/Hyakunin-Issyu/releases/download/v$version/hyakunin-issyu-$version.apk",
    apkSize = 3L,
    notes = notes,
    pageUrl = "https://github.com/abon-san/Hyakunin-Issyu/releases/tag/v$version",
)

/** 決めた版を返す [ReleaseSource]。[error] があれば、確かめるたびにそれを投げる。 */
internal class FakeReleaseSource(var release: AppRelease? = null) : ReleaseSource {
    var checks = 0
        private set
    var error: Exception? = null

    /** これを渡すと、完了するまで確かめ終えない。 */
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun latest(): AppRelease? {
        checks++
        gate?.await()
        error?.let { throw it }
        return release
    }
}

/** [dir] に中身「apk」のファイルを作る [ApkDownloader]。途中で 50% を知らせ、[gate] があれば完了するまで待つ。 */
internal class FakeApkDownloader(private val dir: File) : ApkDownloader {
    val downloads = mutableListOf<AppRelease>()
    var error: Exception? = null
    var gate: CompletableDeferred<Unit>? = null

    override suspend fun download(release: AppRelease, onProgress: (Float?) -> Unit): File {
        downloads += release
        onProgress(0.5f)
        gate?.await()
        error?.let { throw it }
        onProgress(1f)
        return File(dir, "hyakunin-issyu-${release.versionName}.apk").apply { writeText("apk") }
    }
}

/** 渡された APK の中身を記録する [ApkInstaller]。 */
internal class FakeApkInstaller : ApkInstaller {
    val installed = mutableListOf<String>()
    var error: Exception? = null

    override suspend fun install(apk: File) {
        installed += apk.readText()
        error?.let { throw it }
    }
}
