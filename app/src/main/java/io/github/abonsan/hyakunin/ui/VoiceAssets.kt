package io.github.abonsan.hyakunin.ui

import android.content.res.AssetManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.abonsan.hyakunin.game.voice.AssetVoiceBank
import io.github.abonsan.hyakunin.game.voice.VoiceBankLoader
import io.github.abonsan.hyakunin.game.voice.VoiceBankSource

/**
 * アセットの読み上げ音声（[AssetVoiceBank]）の読み込み。最初に頼まれたときに IO スレッドで読む。
 * 画面を回しても読み直さないよう、Activity の ViewModel に持つ。
 */
@Composable
fun rememberAssetVoiceBanks(): VoiceBankSource {
    val assets = LocalContext.current.applicationContext.assets
    return viewModel { AssetVoiceBankHolder(assets) }.loader
}

/** [AssetVoiceBank] の読み込みを Activity の間だけ持つ。 */
internal class AssetVoiceBankHolder(assets: AssetManager) : ViewModel() {
    val loader = VoiceBankLoader(viewModelScope) { AssetVoiceBank.load(assets) }
}
