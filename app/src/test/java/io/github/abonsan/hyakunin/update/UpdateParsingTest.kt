package io.github.abonsan.hyakunin.update

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** GitHub のリリースの読み方と、版の比べ方。 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class UpdateParsingTest {
    private val latest = """
        {
          "tag_name": "v0.8.0",
          "name": "0.8.0",
          "html_url": "https://github.com/abon-san/Hyakunin-Issyu/releases/tag/v0.8.0",
          "body": "札を覚える機能を足しました\r\n音量を変えられるようにしました",
          "assets": [
            {"name": "checksums.txt", "size": 100, "browser_download_url": "https://example.com/checksums.txt"},
            {"name": "hyakunin-issyu-0.8.0.apk", "size": 12345678,
             "browser_download_url": "https://github.com/abon-san/Hyakunin-Issyu/releases/download/v0.8.0/hyakunin-issyu-0.8.0.apk"}
          ]
        }
    """.trimIndent()

    @Test
    fun theApkOfTheLatestReleaseIsRead() {
        assertEquals(
            AppRelease(
                versionName = "0.8.0",
                apkUrl = "https://github.com/abon-san/Hyakunin-Issyu/releases/download/v0.8.0/hyakunin-issyu-0.8.0.apk",
                apkSize = 12345678L,
                notes = "札を覚える機能を足しました\r\n音量を変えられるようにしました",
                pageUrl = "https://github.com/abon-san/Hyakunin-Issyu/releases/tag/v0.8.0",
            ),
            parseRelease(latest),
        )
    }

    @Test
    fun aReleaseWithoutAnApkCannotBeInstalled() {
        assertNull(parseRelease("""{"tag_name": "v0.8.0", "assets": [{"name": "notes.txt", "browser_download_url": "x"}]}"""))
        assertNull(parseRelease("""{"tag_name": "v0.8.0"}"""))
    }

    @Test
    fun missingNotesAreEmpty() {
        val release = parseRelease("""{"tag_name": "0.8.0", "body": null, "assets": [{"name": "a.APK", "browser_download_url": "u"}]}""")!!
        assertEquals("0.8.0", release.versionName)
        assertEquals("", release.notes)
        assertEquals(0L, release.apkSize)
    }

    @Test
    fun brokenJsonIsAnError() {
        assertThrows(JSONException::class.java) { parseRelease("<html>rate limited</html>") }
    }

    @Test
    fun versionsAreComparedNumberByNumber() {
        assertTrue(isNewer("0.8.0", "0.7.0"))
        assertTrue(isNewer("0.10.0", "0.9.1"))
        assertTrue(isNewer("1.0", "0.99.99"))
        assertTrue(isNewer("v0.7.1", "0.7.0"))
        assertTrue(isNewer("0.7.0.1", "0.7"))
        assertFalse(isNewer("0.7.0", "0.7.0"))
        assertFalse(isNewer("0.7", "0.7.0"))
        assertFalse(isNewer("0.6.9", "0.7.0"))
        // 数として読めない名前は新しくないとみなす
        assertFalse(isNewer("0.8.0-beta", "0.7.0"))
        assertFalse(isNewer("latest", "0.7.0"))
        assertFalse(isNewer("0.8.0", "dev"))
    }
}
