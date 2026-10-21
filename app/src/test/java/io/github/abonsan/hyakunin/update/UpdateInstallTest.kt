package io.github.abonsan.hyakunin.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** 落とした APK を PackageInstaller のセッションで入れることと、その結果の受け方。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [36])
class UpdateInstallTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun theApkIsWrittenToASessionForThisApp() = runTest {
        val apk = temp.newFile("hyakunin-issyu-0.8.0.apk").apply { writeBytes(ByteArray(1024) { it.toByte() }) }
        SessionApkInstaller(context).install(apk)
        val session = context.packageManager.packageInstaller.allSessions.single()
        assertEquals(context.packageName, session.appPackageName)
    }

    @Test
    fun theStatusesBecomeResults() {
        assertEquals(InstallResult.Success, installResultOf(PackageInstaller.STATUS_SUCCESS))
        assertEquals(InstallResult.Aborted, installResultOf(PackageInstaller.STATUS_FAILURE_ABORTED))
        // 署名が違う（デバッグ版の上に入れようとしたなど）
        assertEquals(InstallResult.Failed(UpdateFailure.SIGNATURE), installResultOf(PackageInstaller.STATUS_FAILURE_CONFLICT))
        assertEquals(InstallResult.Failed(UpdateFailure.SIGNATURE), installResultOf(PackageInstaller.STATUS_FAILURE_INCOMPATIBLE))
        assertEquals(InstallResult.Failed(UpdateFailure.STORAGE), installResultOf(PackageInstaller.STATUS_FAILURE_STORAGE))
        assertEquals(InstallResult.Failed(UpdateFailure.INSTALL), installResultOf(PackageInstaller.STATUS_FAILURE))
        assertEquals(InstallResult.Failed(UpdateFailure.INSTALL), installResultOf(PackageInstaller.STATUS_FAILURE_INVALID))
    }

    @Test
    fun theReceiverPassesTheResultsOn() = runTest {
        val results = mutableListOf<InstallResult>()
        val collecting = launch(UnconfinedTestDispatcher(testScheduler)) {
            UpdateInstallResults.results.collect { results += it }
        }
        val receiver = UpdateInstallReceiver()
        // 確認が要るときは、確認の画面をそのまま渡す
        val confirm = Intent("android.content.pm.action.CONFIRM_INSTALL")
        receiver.onReceive(
            context,
            Intent()
                .putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_PENDING_USER_ACTION)
                .putExtra(Intent.EXTRA_INTENT, confirm),
        )
        receiver.onReceive(context, Intent().putExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE_ABORTED))
        // 状態がなければ失敗とみなす
        receiver.onReceive(context, Intent())
        assertEquals(3, results.size)
        assertEquals("android.content.pm.action.CONFIRM_INSTALL", (results[0] as InstallResult.NeedsConfirmation).confirm.action)
        assertEquals(InstallResult.Aborted, results[1])
        assertEquals(InstallResult.Failed(UpdateFailure.INSTALL), results[2])
        collecting.cancel()
    }
}
