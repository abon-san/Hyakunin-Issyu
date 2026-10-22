package io.github.abonsan.hyakunin

import io.github.abonsan.hyakunin.ui.home.VERSION_LABEL
import org.junit.Assert.assertEquals
import org.junit.Test

/** アプリの版。リリースのたびに書き換える。 */
class AppVersionTest {
    @Test
    fun theVersionIs071() {
        assertEquals(14, BuildConfig.VERSION_CODE)
        assertEquals("0.7.1", BuildConfig.VERSION_NAME)
        // トップ画面の版は versionName から作る
        assertEquals("Ver. 0.7.1", VERSION_LABEL)
    }
}
