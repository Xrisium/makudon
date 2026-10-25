package io.github.xrisium.makudon

import android.os.Bundle
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.xrisium.makudon.app.MakudonApp
import io.github.xrisium.makudon.app.MakudonApplication
import java.util.UUID

class MainActivity : ComponentActivity() {
    private val workspace get() = (application as MakudonApplication).workspace
    private var requestId: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestId = savedInstanceState?.getString("importRequestId") ?: UUID.randomUUID().toString()
        setContent { MakudonApp(workspace) }
        if (intent.action == Intent.ACTION_VIEW) workspace.import(intent.data, requestId)
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestId = UUID.randomUUID().toString()
        if (intent.action == Intent.ACTION_VIEW) workspace.import(intent.data, requestId)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("importRequestId", requestId)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        workspace.backgroundSave()
        super.onStop()
    }
}
