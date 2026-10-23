package io.github.xrisium.makudon.app

import androidx.compose.runtime.Composable
import io.github.xrisium.makudon.feature.workspace.WorkspaceScreen
import io.github.xrisium.makudon.ui.theme.MakudonTheme

@Composable
fun MakudonApp() {
    MakudonTheme {
        WorkspaceScreen()
    }
}
