package io.github.abonsan.hyakunin

import io.github.abonsan.hyakunin.ui.home.VERSION_LABEL
import org.junit.Assert.assertEquals
import org.junit.Test

/** アプリの版。リリースのたびに書き換える。 */
class AppVersionTest {
    @Test
    fun theVersionIs070() {
        assertEquals(13, BuildConfig.VERSION_CODE)
        assertEquals("0.7.0", BuildConfig.VERSION_NAME)
        // トップ画面の版は versionName から作る
        assertEquals("Ver. 0.7.0", VERSION_LABEL)
    }
}
