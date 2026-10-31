package io.github.xrisium.makudon.app

import androidx.compose.runtime.Composable
import io.github.xrisium.makudon.feature.workspace.WorkspaceScreen
import io.github.xrisium.makudon.ui.theme.MakudonTheme
import io.github.xrisium.makudon.feature.workspace.WorkspaceViewModel
import io.github.xrisium.makudon.domain.ColorTheme

@Composable
fun MakudonApp(model: WorkspaceViewModel) {
    val workspace = model.ui.workspace
    MakudonTheme(workspace?.theme ?: ColorTheme.System, ready = workspace != null, uiFont = workspace?.uiFont ?: io.github.xrisium.makudon.domain.FontPreference(), editorFont = workspace?.editorFont ?: io.github.xrisium.makudon.domain.FontPreference()) {
        WorkspaceScreen(model)
    }
}
