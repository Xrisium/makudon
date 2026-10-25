package io.github.xrisium.makudon.app

import androidx.compose.runtime.Composable
import io.github.xrisium.makudon.feature.workspace.WorkspaceScreen
import io.github.xrisium.makudon.ui.theme.MakudonTheme
import io.github.xrisium.makudon.feature.workspace.WorkspaceViewModel
import io.github.xrisium.makudon.domain.ColorTheme

@Composable
fun MakudonApp(model: WorkspaceViewModel) {
    MakudonTheme(model.ui.workspace?.theme ?: ColorTheme.System, ready = model.ui.workspace != null) {
        WorkspaceScreen(model)
    }
}
