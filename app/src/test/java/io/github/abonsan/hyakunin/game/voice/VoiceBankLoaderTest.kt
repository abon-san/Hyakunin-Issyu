package io.github.abonsan.hyakunin.game.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 読み上げ音声を、頼まれたときに一度だけ読み込む（VoiceBankLoader）。 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceBankLoaderTest {
    private val bank = fakeVoiceBank()

    private fun TestScope.loader(load: () -> VoiceBank?) =
        VoiceBankLoader(this, StandardTestDispatcher(testScheduler), load)

    @Test
    fun nothingIsLoadedUntilRequested() = runTest {
        var loads = 0
        val loader = loader {
            loads++
            bank
        }
        advanceUntilIdle()
        assertEquals(VoiceBankState.Loading, loader.state.value)
        assertEquals(0, loads)

        loader.request()
        // 読み込みは別のスレッド（ここではテストの dispatcher）で進み、呼んだ側は待たない
        assertEquals(VoiceBankState.Loading, loader.state.value)
        advanceUntilIdle()
        assertSame(bank, (loader.state.value as VoiceBankState.Ready).bank)
        assertEquals(1, loads)
    }

    @Test
    fun repeatedRequestsLoadOnlyOnce() = runTest {
        var loads = 0
        val loader = loader {
            loads++
            bank
        }
        repeat(3) { loader.request() }
        advanceUntilIdle()
        loader.request()
        advanceUntilIdle()
        assertEquals(1, loads)
    }

    @Test
    fun noVoiceWhenNothingCanBeLoaded() = runTest {
        val missing = loader { null }
        missing.request()
        advanceUntilIdle()
        assertEquals(VoiceBankState.Ready(null), missing.state.value)

        // 読み込みで例外が起きても、音声なしで対戦できる
        val broken = loader { error("壊れたアセット") }
        broken.request()
        advanceUntilIdle()
        assertEquals(VoiceBankState.Ready(null), broken.state.value)
    }

    @Test
    fun aReadySourceNeedsNoLoading() {
        val source = VoiceBankSource.ready(bank)
        assertSame(bank, (source.state.value as VoiceBankState.Ready).bank)
        source.request()
        assertEquals(VoiceBankState.Ready(null), VoiceBankSource.ready(null).state.value)
    }
}
