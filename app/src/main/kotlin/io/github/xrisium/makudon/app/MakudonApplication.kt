package io.github.xrisium.makudon.app

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.github.xrisium.makudon.feature.workspace.WorkspaceViewModel

/** Process-owned store keeps a single editor/writer across Activity recreation and external VIEW intents. */
class MakudonApplication : Application() {
    private val models = ViewModelStore()
    val workspace: WorkspaceViewModel by lazy {
        ViewModelProvider(models, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[WorkspaceViewModel::class.java]
    }
}
