package io.github.xrisium.makudon.app

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.os.LocaleListCompat
import io.github.xrisium.makudon.R
import io.github.xrisium.makudon.data.LanguagePreference
import kotlinx.coroutines.*
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.util.Locale

/** Language selection owns no Activity and never resolves long-lived translated UI messages. */
class AppLanguages(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preference = LanguagePreference(File(application.filesDir, "app-preferences/language"))
    val supported: List<String> = application.resources.getXml(R.xml.locales_config).use { xml ->
        buildList {
            while (xml.eventType != XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == XmlPullParser.START_TAG && xml.name == "locale") {
                    add(checkNotNull(xml.getAttributeValue("http://schemas.android.com/apk/res/android", "name")))
                }
                xml.next()
            }
        }
    }
    var selected by mutableStateOf("")
        private set
    var error by mutableStateOf<Int?>(null)
        private set
    var migration by mutableStateOf<String?>(null)
        private set
    private var legacy = ""
    private val loaded = scope.async {
        try {
            val stored = withContext(Dispatchers.IO) { preference.read() }
            require(stored.isEmpty() || stored in supported)
            if (Build.VERSION.SDK_INT < 33) legacy = stored
            else if (stored.isNotEmpty()) {
                if (AppCompatDelegate.getApplicationLocales().isEmpty) migration = stored
                else withContext(Dispatchers.IO) { preference.write("") }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = R.string.language_load_error }
    }

    /** A cold legacy launch waits before creating a library or consuming an external VIEW. */
    suspend fun initialize(host: Context): Boolean {
        loaded.await()
        if (Build.VERSION.SDK_INT < 33 && AppCompatDelegate.getApplicationLocales().toLanguageTags() != legacy) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(legacy))
            val expected = if (legacy.isEmpty()) application.resources.configuration.locales[0] else Locale.forLanguageTag(legacy)
            // An explicit choice equal to the system language can be applied without recreation.
            // Wait only when this host still has the old configuration, not for a no-op recreate.
            if (host.resources.configuration.locales[0] != expected) return false
        }
        sync()
        return true
    }

    fun sync() { selected = AppCompatDelegate.getApplicationLocales().toLanguageTags() }

    suspend fun change(tag: String) {
        require(tag.isEmpty() || tag in supported)
        try {
            // On 33+, this file only dismisses a legacy migration candidate; the platform is authoritative.
            if (Build.VERSION.SDK_INT < 33 || migration != null) {
                withContext(Dispatchers.IO + NonCancellable) { preference.write(if (Build.VERSION.SDK_INT < 33) tag else "") }
            }
            if (Build.VERSION.SDK_INT < 33) legacy = tag
            migration = null
            error = null
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
            sync()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { error = R.string.language_error }
    }

    /** Application resources retain the system language on legacy Android; use a fresh configured context. */
    fun context(): Context {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return application
        return application.createConfigurationContext(Configuration(application.resources.configuration).apply {
            setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()))
        })
    }

    fun nativeName(tag: String): String = application.createConfigurationContext(Configuration(application.resources.configuration).apply {
        setLocale(Locale.forLanguageTag(tag))
    }).getString(R.string.language_native_name)
}
