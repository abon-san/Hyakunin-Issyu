package io.github.abonsan.hyakunin.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import androidx.core.content.IntentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import java.io.File

/** 落とした APK を入れるもの。 */
fun interface ApkInstaller {
    /**
     * [apk] を入れ始める。結果（利用者の確認待ち・成功・失敗）は後から [UpdateInstallResults] に届く。
     * 入れ始められなければ例外を投げる。
     */
    suspend fun install(apk: File)
}

/** インストールの結果（[PackageInstaller] の状態を、アプリで扱う形にしたもの）。 */
sealed interface InstallResult {
    /** 利用者の確認が要る。[confirm] は確認の画面（Android のインストール確認、または提供元の許可の画面）。 */
    data class NeedsConfirmation(val confirm: Intent) : InstallResult

    /** 入った（このアプリはすぐに終わらされる）。 */
    data object Success : InstallResult

    /** 利用者が確認の画面でやめた。 */
    data object Aborted : InstallResult

    /** 入れられなかった。 */
    data class Failed(val failure: UpdateFailure) : InstallResult
}

/** [PackageInstaller.EXTRA_STATUS] の [status] を、確認待ち以外の結果にする。 */
internal fun installResultOf(status: Int): InstallResult = when (status) {
    PackageInstaller.STATUS_SUCCESS -> InstallResult.Success
    PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Aborted
    // 入っているアプリと署名が違う（デバッグ版の上に入れようとしたなど）
    PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
        InstallResult.Failed(UpdateFailure.SIGNATURE)
    PackageInstaller.STATUS_FAILURE_STORAGE -> InstallResult.Failed(UpdateFailure.STORAGE)
    else -> InstallResult.Failed(UpdateFailure.INSTALL)
}

/** インストールの結果をアプリ全体に届ける（[UpdateInstallReceiver] が受けて流し、更新の ViewModel が受け取る）。 */
object UpdateInstallResults {
    private val _results = MutableSharedFlow<InstallResult>(extraBufferCapacity = 8)
    val results: SharedFlow<InstallResult> = _results.asSharedFlow()

    fun report(result: InstallResult) {
        _results.tryEmit(result)
    }
}

/**
 * [PackageInstaller] のセッションに APK を書き込んで入れる。同じ署名の新しい版だけが入る（違えば
 * [InstallResult.Failed] の [UpdateFailure.SIGNATURE]）。結果は [UpdateInstallReceiver] で受ける。
 */
class SessionApkInstaller(context: Context) : ApkInstaller {
    private val appContext = context.applicationContext

    override suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = appContext.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(appContext.packageName)
            setSize(apk.length())
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(SESSION_APK_NAME, 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                // 結果の状態を書き込んでもらうので変更できる PendingIntent にする（行き先は明示）
                val intent = Intent(appContext, UpdateInstallReceiver::class.java).setPackage(appContext.packageName)
                val pending = PendingIntent.getBroadcast(
                    appContext,
                    sessionId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        }
    }

    private companion object {
        const val SESSION_APK_NAME = "base.apk"
    }
}

/**
 * [SessionApkInstaller] のインストールの結果を受けて、[UpdateInstallResults] に流す。
 * 利用者の確認が要るときは、確認の画面の Intent を渡す（画面が前にあるときに開く）。
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val result = if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                ?.let { InstallResult.NeedsConfirmation(it) }
                ?: InstallResult.Failed(UpdateFailure.INSTALL)
        } else {
            installResultOf(status)
        }
        if (result is InstallResult.Failed) {
            Log.w(TAG, "更新を入れられない: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
        }
        UpdateInstallResults.report(result)
    }

    private companion object {
        const val TAG = "HyakuninUpdate"
    }
}
