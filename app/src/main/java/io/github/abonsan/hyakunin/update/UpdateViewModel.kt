package io.github.abonsan.hyakunin.update

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.abonsan.hyakunin.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 更新できなかった理由と、利用者に見せる文。 */
enum class UpdateFailure(val message: String) {
    CHECK("新しい版を確認できませんでした。ネットワークにつながっているかを確かめてください。"),
    DOWNLOAD("新しい版をダウンロードできませんでした。ネットワークにつながっているかを確かめてください。"),
    INSTALL("新しい版を入れられませんでした。"),
    SIGNATURE("入っているアプリと署名が違うため、更新できません。いったんアンインストールしてから、新しい版を入れてください。"),
    STORAGE("端末の空き容量が足りないため、新しい版を入れられませんでした。"),
}

/** 更新の状態。 */
sealed interface UpdateState {
    /** まだ確かめていない。 */
    data object Idle : UpdateState

    /** 新しい版があるかを確かめている。 */
    data object Checking : UpdateState

    /** 今の版が最新。 */
    data object UpToDate : UpdateState

    /** 新しい版 [release] がある。 */
    data class Available(val release: AppRelease) : UpdateState

    /** [release] の APK を落としている。[progress] は 0〜1（大きさが分からなければ null）。 */
    data class Downloading(val release: AppRelease, val progress: Float?) : UpdateState

    /**
     * [release] を入れている（Android の確認の画面を待っている）。[confirm] は、まだ開いていない確認の画面
     * （画面が前に出ているときに開き、[UpdateViewModel.onConfirmationShown] で知らせる）。
     */
    data class Installing(val release: AppRelease, val confirm: Intent? = null) : UpdateState

    /** [failure] のため、確かめられなかった・更新できなかった。[release] は更新しようとした版（確かめられなかったときは null）。 */
    data class Failed(val failure: UpdateFailure, val release: AppRelease? = null) : UpdateState
}

/** 確かめている・落としている・入れている最中か（その間は、もう一度確かめたり更新したりしない）。 */
val UpdateState.isBusy: Boolean
    get() = this is UpdateState.Checking || this is UpdateState.Downloading || this is UpdateState.Installing

/**
 * アプリの更新。GitHub Releases の最新の版を確かめ（[check]）、新しければ APK を落として入れる（[update]）。
 * 画面を回しても続くよう、Activity の ViewModel に持つ。UI のスレッド（メイン）からだけ呼ぶこと。
 *
 * @param currentVersion 今の版の名前
 * @param installResults インストールの結果（[UpdateInstallResults]）
 */
class UpdateViewModel(
    private val source: ReleaseSource,
    private val downloader: ApkDownloader,
    private val installer: ApkInstaller,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    installResults: Flow<InstallResult> = UpdateInstallResults.results,
) : ViewModel() {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** 起動したときの確認を済ませたか。 */
    private var checkedOnLaunch = false

    /** 確かめる・落として入れる仕事。 */
    private var job: Job? = null

    init {
        viewModelScope.launch { installResults.collect(::onInstallResult) }
    }

    /** 起動したときに一度だけ確かめる（画面を回しても確かめ直さない）。 */
    fun checkOnLaunch() {
        if (checkedOnLaunch) return
        checkedOnLaunch = true
        check()
    }

    /** 新しい版があるかを確かめる。確かめている・更新している最中なら何もしない。 */
    fun check() {
        if (_state.value.isBusy) return
        _state.value = UpdateState.Checking
        job = viewModelScope.launch {
            _state.value = try {
                val release = source.latest()
                if (release != null && isNewer(release.versionName, currentVersion)) {
                    UpdateState.Available(release)
                } else {
                    UpdateState.UpToDate
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UpdateState.Failed(UpdateFailure.CHECK)
            }
        }
    }

    /** [release] を落として入れ始める。確かめている・更新している最中なら何もしない。 */
    fun update(release: AppRelease) {
        if (_state.value.isBusy) return
        _state.value = UpdateState.Downloading(release, progress = null)
        job = viewModelScope.launch {
            val apk = try {
                downloader.download(release) { progress ->
                    // 中止した後に遅れて届いた知らせでは、状態を戻さない
                    _state.update { if (it is UpdateState.Downloading) UpdateState.Downloading(release, progress) else it }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(UpdateFailure.DOWNLOAD, release)
                return@launch
            }
            _state.value = UpdateState.Installing(release)
            try {
                installer.install(apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(UpdateFailure.INSTALL, release)
            } finally {
                // セッションに書き込んだので、落としたファイルはもう要らない
                apk.delete()
            }
        }
    }

    /** 落としている最中なら、やめて新しい版がある状態に戻す。 */
    fun cancel() {
        val downloading = _state.value as? UpdateState.Downloading ?: return
        job?.cancel()
        _state.value = UpdateState.Available(downloading.release)
    }

    /**
     * 失敗の知らせ・確認の画面を待つ表示を閉じる。更新しようとした版があれば、新しい版がある状態に戻す
     * （確かめられなかったときは、まだ確かめていない状態に戻す）。
     */
    fun dismiss() {
        _state.value = when (val state = _state.value) {
            is UpdateState.Failed -> state.release?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            is UpdateState.Installing -> UpdateState.Available(state.release)
            else -> return
        }
    }

    /** 確認の画面（[UpdateState.Installing.confirm]）を開いた。 */
    fun onConfirmationShown() {
        val installing = _state.value as? UpdateState.Installing ?: return
        _state.value = installing.copy(confirm = null)
    }

    private fun onInstallResult(result: InstallResult) {
        val installing = _state.value as? UpdateState.Installing ?: return
        _state.value = when (result) {
            is InstallResult.NeedsConfirmation -> installing.copy(confirm = result.confirm)
            // 入るとこのアプリは終わらされるので、それまで待つ
            InstallResult.Success -> installing
            InstallResult.Aborted -> UpdateState.Available(installing.release)
            is InstallResult.Failed -> UpdateState.Failed(result.failure, installing.release)
        }
    }
}
