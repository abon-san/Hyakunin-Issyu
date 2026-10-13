package io.github.abonsan.hyakunin

import io.github.abonsan.hyakunin.ui.home.VERSION_LABEL
import org.junit.Assert.assertEquals
import org.junit.Test

/** アプリの版。リリースのたびに書き換える。 */
class AppVersionTest {
    @Test
    fun theVersionIs050() {
        assertEquals(9, BuildConfig.VERSION_CODE)
        assertEquals("0.5.0", BuildConfig.VERSION_NAME)
        // トップ画面の版は versionName から作る
        assertEquals("Ver. 0.5.0", VERSION_LABEL)
    }
}
