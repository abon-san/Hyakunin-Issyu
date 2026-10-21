package io.github.abonsan.hyakunin.ui.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.abonsan.hyakunin.BuildConfig
import io.github.abonsan.hyakunin.ui.components.PrimaryButton
import io.github.abonsan.hyakunin.ui.components.SecondaryButton
import io.github.abonsan.hyakunin.ui.components.WafuDialog
import io.github.abonsan.hyakunin.ui.components.WafuDialogFrame
import io.github.abonsan.hyakunin.ui.theme.Shu
import io.github.abonsan.hyakunin.ui.theme.UsuzumiText
import io.github.abonsan.hyakunin.ui.theme.WashiDeep
import io.github.abonsan.hyakunin.update.AppRelease
import io.github.abonsan.hyakunin.update.GitHubReleaseSource
import io.github.abonsan.hyakunin.update.HttpApkDownloader
import io.github.abonsan.hyakunin.update.SessionApkInstaller
import io.github.abonsan.hyakunin.update.UpdateState
import io.github.abonsan.hyakunin.update.UpdateViewModel
import io.github.abonsan.hyakunin.update.isBusy
import java.io.File
import kotlin.math.roundToInt

/** 「今すぐ確認」のボタンの文字。 */
internal const val CHECK_UPDATE_LABEL = "今すぐ確認"

/** 「起動時に更新を確認」の見出し。 */
internal const val AUTO_UPDATE_CHECK_LABEL = "起動時に更新を確認"

/** 新しい版を入れ始めるボタンの文字。 */
internal const val UPDATE_LABEL = "更新"

/** 新しい版の知らせを閉じるボタンの文字。 */
internal const val LATER_LABEL = "あとで"

/** ダウンロードをやめるボタンの文字。 */
internal const val CANCEL_UPDATE_LABEL = "中止"

/** 新しい版の知らせの題。 */
internal const val UPDATE_AVAILABLE_TITLE = "新しい版があります"

/** 今の版の表示（例:「今の版：Ver. 0.7.0」）。 */
internal val CURRENT_VERSION_TEXT = "今の版：Ver. ${BuildConfig.VERSION_NAME}"

/** 新しい版に更新するボタンの文字（例:「Ver. 0.8.0 に更新」）。 */
internal fun updateToLabel(release: AppRelease) = "Ver. ${release.versionName} に更新"

/** 知らせに出すリリースの注記の行数の上限。 */
private const val NOTES_MAX_LINES = 6

/** 知らせに出すリリースの注記の文字数の上限。 */
private const val NOTES_MAX_LENGTH = 300

/** 更新の状態の説明（設定の画面に出す）。まだ確かめていなければ null。 */
internal fun updateStatusText(state: UpdateState): String? = when (state) {
    UpdateState.Idle -> null
    UpdateState.Checking -> "確認しています…"
    UpdateState.UpToDate -> "最新の版です"
    is UpdateState.Available -> "新しい版（Ver. ${state.release.versionName}）があります"
    is UpdateState.Downloading -> "ダウンロードしています…" + (state.progress?.let { " ${percentText(it)}" } ?: "")
    is UpdateState.Installing -> "インストールの確認を待っています"
    is UpdateState.Failed -> state.failure.message
}

/** 進み具合 [progress]（0〜1）の表示（例:「42%」）。 */
internal fun percentText(progress: Float): String = "${(progress * 100).roundToInt()}%"

/** 新しい版の知らせの本文。版と、リリースの注記の先頭（長ければ省く）。 */
internal fun updateMessage(release: AppRelease): String {
    val lines = release.notes.trim().lines().filter { it.isNotBlank() }
    val head = lines.take(NOTES_MAX_LINES).joinToString("\n")
    val cut = lines.size > NOTES_MAX_LINES || head.length > NOTES_MAX_LENGTH
    val notes = head.take(NOTES_MAX_LENGTH) + if (cut) "…" else ""
    val version = "Ver. ${release.versionName} が出ています（今は Ver. ${BuildConfig.VERSION_NAME}）。"
    return if (notes.isEmpty()) version else "$version\n\n$notes"
}

/** アプリの更新（[UpdateViewModel]）。GitHub Releases を確かめ、キャッシュに落として入れる。Activity の ViewModel に持つ。 */
@Composable
fun rememberAppUpdates(): UpdateViewModel {
    val context = LocalContext.current.applicationContext
    return viewModel {
        UpdateViewModel(
            source = GitHubReleaseSource(),
            downloader = HttpApkDownloader(File(context.cacheDir, UPDATE_CACHE_DIR)),
            installer = SessionApkInstaller(context),
        )
    }
}

/** 落とした APK を置く、キャッシュの中のフォルダ。 */
private const val UPDATE_CACHE_DIR = "updates"

/**
 * Android の確認の画面（[UpdateState.Installing.confirm]）を、この画面が前に出ているときに開く
 * （裏にいる間は開けないので、前に戻るまで待つ）。
 */
@Composable
fun UpdateConfirmationLauncher(updates: UpdateViewModel, state: UpdateState) {
    val confirm = (state as? UpdateState.Installing)?.confirm ?: return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(confirm) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            context.startActivity(confirm)
            updates.onConfirmationShown()
        }
    }
}

/**
 * トップ画面での更新の知らせ。新しい版があれば知らせ（[promptDismissed] なら出さない）、落としている間と
 * 確認の画面を待つ間は進み具合を出し、落とせなかった・入れられなかったら理由を出す。確かめられなかったことは
 * ここでは知らせない（設定の画面に出す）。
 *
 * @param onLater 「あとで」・失敗の知らせを閉じた（この起動の間は、もう知らせない）
 */
@Composable
fun UpdateDialogs(
    state: UpdateState,
    promptDismissed: Boolean,
    onUpdate: (AppRelease) -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
    onLater: () -> Unit,
) {
    when (state) {
        is UpdateState.Available -> if (!promptDismissed) {
            WafuDialog(
                title = UPDATE_AVAILABLE_TITLE,
                message = updateMessage(state.release),
                confirmLabel = UPDATE_LABEL,
                onConfirm = { onUpdate(state.release) },
                dismissLabel = LATER_LABEL,
                onDismiss = onLater,
                onDismissRequest = onLater,
            )
        }
        is UpdateState.Downloading -> WafuDialogFrame(title = "ダウンロードしています", onDismissRequest = onCancel) {
            UpdateProgress(state.progress)
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryButton(CANCEL_UPDATE_LABEL, onCancel)
            }
        }
        is UpdateState.Installing -> WafuDialogFrame(title = "インストールの確認を待っています", onDismissRequest = onDismiss) {
            Text(
                text = "Android の確認の画面で、インストールを選んでください。",
                style = MaterialTheme.typography.bodyMedium,
                color = UsuzumiText,
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SecondaryButton("閉じる", onDismiss)
            }
        }
        is UpdateState.Failed -> state.release?.let { release ->
            val close = {
                onDismiss()
                onLater()
            }
            WafuDialog(
                title = "更新できませんでした",
                message = state.failure.message,
                confirmLabel = "もう一度",
                onConfirm = { onUpdate(release) },
                dismissLabel = "閉じる",
                onDismiss = close,
                onDismissRequest = close,
            )
        }
        else -> Unit
    }
}

/** ダウンロードの進み具合の帯と割合（[progress] が null なら、動き続ける帯だけ）。 */
@Composable
private fun UpdateProgress(progress: Float?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val bar = Modifier.weight(1f)
        if (progress != null) {
            LinearProgressIndicator(progress = { progress }, modifier = bar, color = Shu, trackColor = WashiDeep)
            Spacer(Modifier.width(12.dp))
            Text(
                text = percentText(progress),
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.bodyMedium,
                color = UsuzumiText,
            )
        } else {
            LinearProgressIndicator(modifier = bar, color = Shu, trackColor = WashiDeep)
        }
    }
}

/**
 * 設定の画面の「アップデート」の板の、今の版・「今すぐ確認」・状態・更新のボタン。
 * 新しい版があれば「Ver. x に更新」、落としている間は進み具合と「中止」を出す。
 */
@Composable
fun UpdateStatus(
    state: UpdateState,
    onCheck: () -> Unit,
    onUpdate: (AppRelease) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(CURRENT_VERSION_TEXT, style = MaterialTheme.typography.titleSmall)
                updateStatusText(state)?.let {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = it,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodySmall,
                        color = UsuzumiText,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            SecondaryButton(
                text = CHECK_UPDATE_LABEL,
                onClick = onCheck,
                minHeight = 40.dp,
                enabled = !state.isBusy,
            )
        }
        when (state) {
            is UpdateState.Available -> {
                Spacer(Modifier.height(12.dp))
                PrimaryButton(
                    text = updateToLabel(state.release),
                    onClick = { onUpdate(state.release) },
                    modifier = Modifier.fillMaxWidth(),
                    minHeight = 48.dp,
                )
            }
            is UpdateState.Downloading -> {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    UpdateProgress(state.progress, Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    SecondaryButton(CANCEL_UPDATE_LABEL, onCancel, minHeight = 40.dp)
                }
            }
            else -> Unit
        }
    }
}
