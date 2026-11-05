package io.github.xrisium.makudon

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.*
import kotlinx.coroutines.launch
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.xrisium.makudon.app.MakudonApp
import io.github.xrisium.makudon.app.MakudonApplication
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val workspace get() = (application as MakudonApplication).workspace
    private var ready by mutableStateOf(false)
    private var requestId: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestId = savedInstanceState?.getString("importRequestId") ?: UUID.randomUUID().toString()
        // Keep the platform starting window until the existing catalog supplies the theme.
        // No extra theme file, main-thread disk reads, or minimum logo display duration.
        val content = findViewById<android.view.View>(android.R.id.content)
        content.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val canDraw = ready && (workspace.launchTheme != null || !workspace.ui.busy)
                if (canDraw) content.viewTreeObserver.removeOnPreDrawListener(this)
                return canDraw
            }
        })
        setContent {
            if (ready) MakudonApp(workspace)
        }
        lifecycleScope.launch {
            if ((application as MakudonApplication).languages.initialize(this@MainActivity)) {
                ready = true
                importIntent()
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestId = UUID.randomUUID().toString()
        if (ready) importIntent()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("importRequestId", requestId)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        (application as MakudonApplication).backgroundSave()
        super.onStop()
    }
    override fun onResume() {
        super.onResume()
        (application as MakudonApplication).languages.sync()
    }
    private fun importIntent() {
        if (intent.action == Intent.ACTION_VIEW) workspace.import(intent.data, requestId)
    }
}
