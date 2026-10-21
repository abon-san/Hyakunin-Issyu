package io.github.abonsan.hyakunin.update

import android.content.Intent
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class UpdateViewModelTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var scheduler: TestCoroutineScheduler
    private val source = FakeReleaseSource()
    private lateinit var downloader: FakeApkDownloader
    private val installer = FakeApkInstaller()
    private val results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 8)
    private lateinit var vm: UpdateViewModel

    private val state: UpdateState get() = vm.state.value

    @Before
    fun setUp() {
        scheduler = TestCoroutineScheduler()
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        downloader = FakeApkDownloader(temp.root)
        vm = UpdateViewModel(source, downloader, installer, currentVersion = "0.7.0", installResults = results)
        run()
    }

    @After
    fun tearDown() {
        vm.viewModelScope.cancel()
        Dispatchers.resetMain()
    }

    private fun run() = scheduler.advanceUntilIdle()

    private fun report(result: InstallResult) {
        results.tryEmit(result)
        run()
    }

    @Test
    fun aNewerReleaseIsAvailable() {
        assertEquals(UpdateState.Idle, state)
        source.release = testRelease("0.8.0")
        vm.check()
        assertEquals(UpdateState.Checking, state)
        run()
        assertEquals(UpdateState.Available(testRelease("0.8.0")), state)
    }

    @Test
    fun theSameOrAnOlderReleaseOrNoReleaseIsUpToDate() {
        for (release in listOf(testRelease("0.7.0"), testRelease("0.6.0"), null)) {
            source.release = release
            vm.check()
            run()
            assertEquals("$release", UpdateState.UpToDate, state)
        }
    }

    @Test
    fun aFailedCheckIsShown() {
        source.error = IOException("offline")
        vm.check()
        run()
        assertEquals(UpdateState.Failed(UpdateFailure.CHECK), state)
        // 閉じると、まだ確かめていない状態に戻る
        vm.dismiss()
        assertEquals(UpdateState.Idle, state)
    }

    @Test
    fun theLaunchCheckRunsOnlyOnce() {
        vm.checkOnLaunch()
        run()
        vm.checkOnLaunch()
        run()
        assertEquals(1, source.checks)
        // 自分で確かめるのはいつでもできる
        vm.check()
        run()
        assertEquals(2, source.checks)
    }

    @Test
    fun aCheckIsNotRepeatedWhileOneIsRunning() {
        source.gate = CompletableDeferred()
        vm.check()
        run()
        vm.check()
        run()
        assertEquals(1, source.checks)
        source.gate!!.complete(Unit)
        run()
        assertEquals(UpdateState.UpToDate, state)
    }

    @Test
    fun theDownloadedApkIsInstalledAndThenConfirmed() {
        val release = testRelease("0.8.0")
        downloader.gate = CompletableDeferred()
        vm.update(release)
        run()
        assertEquals(UpdateState.Downloading(release, 0.5f), state)
        downloader.gate!!.complete(Unit)
        run()
        assertEquals(listOf("apk"), installer.installed)
        assertEquals(UpdateState.Installing(release), state)
        // 落としたファイルは、セッションに書き込んだら消す
        assertTrue(temp.root.listFiles().isNullOrEmpty())

        // 確認の画面を開いて、利用者が入れた
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        report(InstallResult.NeedsConfirmation(confirm))
        assertEquals(UpdateState.Installing(release, confirm), state)
        vm.onConfirmationShown()
        assertEquals(UpdateState.Installing(release), state)
        report(InstallResult.Success)
        assertEquals(UpdateState.Installing(release), state)
    }

    @Test
    fun cancellingTheConfirmationGoesBackToAvailable() {
        val release = testRelease("0.8.0")
        vm.update(release)
        run()
        report(InstallResult.Aborted)
        assertEquals(UpdateState.Available(release), state)
    }

    @Test
    fun aSignatureMismatchIsExplained() {
        val release = testRelease("0.8.0")
        vm.update(release)
        run()
        report(InstallResult.Failed(UpdateFailure.SIGNATURE))
        assertEquals(UpdateState.Failed(UpdateFailure.SIGNATURE, release), state)
        assertTrue(UpdateFailure.SIGNATURE.message.contains("署名"))
        // 閉じると、新しい版がある状態に戻る
        vm.dismiss()
        assertEquals(UpdateState.Available(release), state)
    }

    @Test
    fun resultsAreIgnoredWhenNothingIsBeingInstalled() {
        report(InstallResult.Failed(UpdateFailure.INSTALL))
        assertEquals(UpdateState.Idle, state)
    }

    @Test
    fun aFailedDownloadOrInstallIsShown() {
        val release = testRelease("0.8.0")
        downloader.error = IOException("offline")
        vm.update(release)
        run()
        assertEquals(UpdateState.Failed(UpdateFailure.DOWNLOAD, release), state)
        assertTrue(installer.installed.isEmpty())

        downloader.error = null
        installer.error = SecurityException("not allowed")
        vm.update(release)
        run()
        assertEquals(UpdateState.Failed(UpdateFailure.INSTALL, release), state)
    }

    @Test
    fun aDownloadCanBeCancelled() {
        val release = testRelease("0.8.0")
        downloader.gate = CompletableDeferred()
        vm.update(release)
        run()
        vm.cancel()
        assertEquals(UpdateState.Available(release), state)
        downloader.gate!!.complete(Unit)
        run()
        // やめた後は入れない
        assertTrue(installer.installed.isEmpty())
        assertEquals(UpdateState.Available(release), state)
        assertFalse(state.isBusy)
    }
}
