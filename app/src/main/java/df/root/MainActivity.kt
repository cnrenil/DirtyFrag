package df.root

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import df.root.daemon.Dfctl
import df.root.script.ModuleEngine
import df.root.script.ModuleStore
import df.root.shell.Privilege
import df.root.shell.Shell
import df.root.ui.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** UI host only. All execution and startup ownership lives in the module engine. */
class MainActivity : ComponentActivity() {
    private val prefs by lazy { createDeviceProtectedStorageContext().getSharedPreferences("dfroot", Context.MODE_PRIVATE) }
    private var uiState by mutableStateOf(MainUiState(versionName = BuildConfig.VERSION_NAME))
    private var armed by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The obsolete receiver is disabled even on upgrades where its component override survived.
        packageManager.setComponentEnabledSetting(android.content.ComponentName(this, BootReceiver::class.java),
            android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
        lifecycleScope.launch {
            ModuleEngine.state.collect { execution ->
                val saved = withContext(Dispatchers.IO) {
                    ModuleStore.read(applicationContext) to runCatching {
                        ModuleEngine.logFile(applicationContext).takeIf { it.isFile }?.readText().orEmpty()
                    }.getOrDefault("")
                }
                // Never ping the FIFO while a handed-off plan is running: the
                // daemon is in run_plan() and will not reply, which used to
                // taint the channel and leave the home screen on "未提权".
                val daemonReady = withContext(Dispatchers.IO) {
                    if (execution.running) {
                        uiState.rooted || ModuleEngine.daemonProcessAlive(applicationContext)
                    } else {
                        Dfctl.forgetUnreachable(applicationContext)
                        Shell.detect(applicationContext) != Privilege.App
                    }
                }
                uiState = uiState.copy(running = execution.running, runArmed = armed,
                    rooted = daemonReady, failedRun = !execution.running && saved.first.lastFailure.isNotBlank(),
                    hasRun = execution.output.isNotBlank() || saved.second.isNotBlank(),
                    logText = execution.output.ifBlank { saved.second },
                    progress1 = execution.progress,
                    progress2 = execution.progress,
                    statusLeft = execution.currentId?.let { id -> saved.first.steps.firstOrNull { it.id == id }?.let { it.name.ifBlank { it.kind.name } } }.orEmpty(),
                    statusRight = if (execution.running) "" else saved.first.lastFailure,
                    advancedLog = prefs.getBoolean("advanced_log", false))
            }
        }
        setContent {
            var theme by remember {
                mutableStateOf(DfThemeState(prefs.getInt("theme_color_mode", 0), prefs.getInt("theme_key_color", 0),
                    prefs.getInt("theme_palette_style", 0),
                    floatingNavigation = prefs.getBoolean("ui_floating_navigation", false),
                    floatingNavigationStyle = prefs.getInt("ui_floating_navigation_style", 0),
                    topBarStyle = prefs.getInt("ui_top_bar_style", 0),
                    blurBars = prefs.getBoolean("ui_blur_bars", false)).sanitized())
            }
            DfTheme(colorMode = theme.colorMode, keyColor = KeyColors.getOrNull(theme.keyColor)?.second, paletteStyle = theme.paletteStyle) {
                val dark = isInDarkTheme()
                SideEffect {
                    val style = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                CompositionLocalProvider(LocalChromeStyle provides theme) {
                    DfShell(state = uiState.copy(runArmed = armed), callbacks = callbacks, theme = theme, onThemeChange = {
                        val updated = it.sanitized()
                        prefs.edit().putInt("theme_color_mode", updated.colorMode).putInt("theme_key_color", updated.keyColor)
                            .putInt("theme_palette_style", updated.paletteStyle)
                            .putBoolean("ui_floating_navigation", updated.floatingNavigation)
                            .putInt("ui_floating_navigation_style", updated.floatingNavigationStyle)
                            .putInt("ui_top_bar_style", updated.topBarStyle)
                            .putBoolean("ui_blur_bars", updated.blurBars).apply()
                        theme = updated
                    })
                }
            }
        }
        // Create the log and marker directories owned by this app before anything
        // (app, daemon or LKM helper) writes into them.
        runCatching { df.root.script.RuntimeFiles.prepare(applicationContext) }
            .onFailure { android.util.Log.w("dfroot", "Runtime directories unavailable", it) }
        if (savedInstanceState == null) ModuleEngine.onAppOpen(applicationContext)
        lifecycleScope.launch(Dispatchers.IO) { checkForAppUpdate() }
    }

    override fun onResume() {
        super.onResume()
        // Never probe the FIFO from the main thread: a hung daemon would freeze
        // the UI. The result is published when it arrives.
        lifecycleScope.launch {
            val ready = withContext(Dispatchers.IO) {
                if (ModuleEngine.state.value.running) {
                    uiState.rooted || ModuleEngine.daemonProcessAlive(applicationContext)
                } else {
                    Dfctl.forgetUnreachable(applicationContext)
                    Shell.detect(applicationContext) != Privilege.App
                }
            }
            uiState = uiState.copy(rooted = ready)
        }
    }
    override fun onStop() { armed = false; super.onStop() }

    private val callbacks = object : MainCallbacks {
        override fun onCancelRunConfirmation() { armed = false }
        override fun onRunClick() {
            if (ModuleEngine.state.value.running) return
            if (!armed) { armed = true; return }
            armed = false
            ModuleEngine.launchManual(applicationContext)
        }
        override fun onSaveLog() { saveLogToDownloads() }
        override fun onAdvancedLogChange(enabled: Boolean) {
            prefs.edit().putBoolean("advanced_log", enabled).apply()
            uiState = uiState.copy(advancedLog = enabled)
        }
        override fun onOpenGithub() {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/mitschud/DirtyFrag"))) }
        }
    }

    private fun saveLogToDownloads() {
        val log = uiState.logText
        if (log.isBlank()) return
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "dirtyfrag_modules.log")
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    }
                    val uri = checkNotNull(contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values))
                    checkNotNull(contentResolver.openOutputStream(uri)).use { it.write(log.toByteArray()) }
                }
                Toast.makeText(this@MainActivity, R.string.save_log, Toast.LENGTH_SHORT).show()
                startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
            }.onFailure { android.util.Log.e("dfroot", "Save log failed", it) }
        }
    }

    private suspend fun checkForAppUpdate() {
        runCatching {
            val connection = URL("https://api.github.com/repos/mitschud/DirtyFrag/releases/latest").openConnection() as HttpURLConnection
            try {
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (connection.responseCode != 200) return
                val tag = connection.inputStream.bufferedReader().use { JSONObject(it.readText()).optString("tag_name") }
                if (tag.isNotBlank() && !tag.removePrefix("v").equals(BuildConfig.VERSION_NAME.removePrefix("v"), true)) {
                    withContext(Dispatchers.Main) { uiState = uiState.copy(updateAvailable = true) }
                }
            } finally { connection.disconnect() }
        }
    }
}
