package io.github.xrisium.makudon

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.*
import androidx.compose.material3.*
import androidx.compose.ui.res.stringResource
import io.github.xrisium.makudon.ui.theme.MakudonTheme
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
        setContent {
            if (ready) MakudonApp(workspace)
            else MakudonTheme { Surface { Text(stringResource(R.string.loading)) } }
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
