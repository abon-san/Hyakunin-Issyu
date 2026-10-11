package io.github.abonsan.hyakunin.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.abonsan.hyakunin.ui.theme.Washi

/** 2段組みにできる最小の幅。 */
private val WideLayoutMinWidth = 560.dp

/**
 * 横に広い（幅が高さより大きく、2段組みにできる幅がある）なら true。
 * 端末の向きではなく、使える広さで決める（分割画面やタブレットでも合うように）。
 */
fun isWideLayout(width: Dp, height: Dp): Boolean = width >= WideLayoutMinWidth && width > height

/**
 * 中身が入り切れば上下中央に置き、入り切らなければスクロールできる列。
 * スクロールできるときは下端を [fadeColor] に溶かして続きがあることを示す。
 */
@Composable
fun CenteredScrollColumn(
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    fadeColor: Color = Washi,
    content: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(modifier) {
        val scroll = rememberScrollState()
        val viewport = if (constraints.hasBoundedHeight) maxHeight else 0.dp
        Column(
            Modifier
                .fillMaxWidth()
                .fadingBottomEdge(scroll, fadeColor)
                .verticalScroll(scroll)
                .heightIn(min = viewport),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = horizontalAlignment,
            content = content,
        )
    }
}
