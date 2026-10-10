package io.github.abonsan.hyakunin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.abonsan.hyakunin.ui.home.GameConfigStore
import io.github.abonsan.hyakunin.ui.home.HomeScreen
import io.github.abonsan.hyakunin.ui.match.MatchRoute

/** ホームと対戦画面を切り替えるアプリ全体。 */
@Composable
fun HyakuninApp(configStore: GameConfigStore) {
    var config by remember { mutableStateOf(configStore.load()) }
    var inMatch by rememberSaveable { mutableStateOf(false) }
    var matchId by rememberSaveable { mutableIntStateOf(0) }

    if (inMatch) {
        MatchScope(matchId = matchId, isActive = { inMatch }) {
            MatchRoute(config = config, onExit = { inMatch = false })
        }
    } else {
        HomeScreen(
            config = config,
            onConfigChange = {
                config = it
                configStore.save(it)
            },
            onStart = {
                matchId++
                inMatch = true
            },
        )
    }
}

/**
 * 対戦ごとに新しい ViewModelStore を [content] に渡す。
 * 画面の回転などの構成変更では同じ対戦を続け、対戦を抜けたら（[isActive] が false）破棄する。
 */
@Composable
private fun MatchScope(matchId: Int, isActive: () -> Boolean, content: @Composable () -> Unit) {
    val holder = viewModel { MatchStoreHolder() }
    val owner = remember(holder, matchId) { holder.ownerFor(matchId) }
    val currentIsActive by rememberUpdatedState(isActive)
    DisposableEffect(owner) {
        onDispose { if (!currentIsActive()) holder.release(matchId) }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner, content = content)
}

/** 対戦用の ViewModelStore を Activity の ViewModel として持つ。 */
internal class MatchStoreHolder : ViewModel() {
    private val store = ViewModelStore()
    private var matchId: Int? = null

    fun ownerFor(id: Int): ViewModelStoreOwner {
        if (matchId != id) {
            store.clear()
            matchId = id
        }
        return object : ViewModelStoreOwner {
            override val viewModelStore: ViewModelStore get() = store
        }
    }

    fun release(id: Int) {
        if (matchId == id) {
            store.clear()
            matchId = null
        }
    }

    override fun onCleared() = store.clear()
}
