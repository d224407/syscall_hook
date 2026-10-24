package app.sclviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Refresh
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.menu.OverlayDropdownMenu
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One activity, Compose/Miuix UI. Two in-app "screens" (app picker, log viewer) switched by
 * state, so every OverlayDialog/OverlayDropdownMenu in either can share the one root Scaffold
 * they require (see Miuix docs: popups must be inside a Scaffold).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                AppRoot()
            }
        }
    }
}

private sealed class Screen {
    object Picker : Screen()
    data class Log(val label: String?, val uid: Int, val fresh: Boolean) : Screen()
}

private class AppEntry(val label: String, val pkg: String, val uid: Int, val system: Boolean)

@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf<Screen>(Screen.Picker) }

    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = when (val s = screen) {
                    Screen.Picker -> "SCL Viewer"
                    is Screen.Log -> s.label ?: "All tracked processes"
                },
                navigationIcon = {
                    if (screen is Screen.Log) {
                        IconButton(onClick = { screen = Screen.Picker }) {
                            Icon(MiuixIcons.Back, contentDescription = "Back")
                        }
                    }
                }
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when (val s = screen) {
                Screen.Picker -> PickerScreen(onPick = { label, uid ->
                    screen = Screen.Log(label, uid, fresh = true)
                })
                is Screen.Log -> LogScreen(label = s.label, uid = s.uid, fresh = s.fresh)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Picker screen
// ---------------------------------------------------------------------------------------------

@Composable
private fun PickerScreen(onPick: (String, Int) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("Loading apps...") }
    var allApps by remember { mutableStateOf(listOf<AppEntry>()) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(Prefs.showSystem(ctx)) }
    var showSettings by remember { mutableStateOf(false) }

    fun refreshStatus() {
        scope.launch {
            val r = withContext(Dispatchers.IO) { ModuleCtl.send(ctx, "list") }
            status = if (r.ok) "Module: ${r.out}" else (r.error ?: "failed")
        }
    }

    LaunchedEffect(Unit) {
        allApps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledApplications(0).map { ai ->
                val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                AppEntry(pm.getApplicationLabel(ai).toString(), ai.packageName, ai.uid, system)
            }.sortedBy { it.label.lowercase() }
        }
        refreshStatus()
    }

    val shown = remember(allApps, query, showSystem) {
        val q = query.lowercase()
        allApps.filter {
            (showSystem || !it.system) &&
                (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q))
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(status, style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            IconButton(onClick = { showSettings = true }) {
                Icon(MiuixIcons.Settings, contentDescription = "Settings")
            }
        }

        TextField(
            value = query,
            onValueChange = { query = it },
            label = "Search name or package",
            leadingIcon = { Icon(MiuixIcons.Search, contentDescription = null, modifier = Modifier.padding(start = 12.dp)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )

        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            SwitchPreference(
                title = "Show system apps",
                checked = showSystem,
                onCheckedChange = {
                    showSystem = it
                    Prefs.setShowSystem(ctx, it)
                }
            )
        }

        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.pkg }) { entry ->
                AppRow(entry, onClick = {
                    scope.launch {
                        status = "Setting target ${entry.label}..."
                        val r = withContext(Dispatchers.IO) { ModuleCtl.send(ctx, "target ${entry.uid}") }
                        if (r.ok) {
                            onPick(entry.label, entry.uid)
                        } else {
                            status = r.error ?: "failed"
                        }
                    }
                })
            }
        }
    }

    if (showSettings) {
        SettingsDialog(show = showSettings, onDismiss = {
            showSettings = false
            refreshStatus()
        })
    }
}

@Composable
private fun AppRow(entry: AppEntry, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var bitmap by remember(entry.pkg) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(entry.pkg) {
        bitmap = withContext(Dispatchers.IO) {
            IconCache.get(ctx.packageManager, entry.pkg)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            val b = bitmap
            if (b != null) {
                Image(bitmap = b.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(entry.label, style = MiuixTheme.textStyles.body1)
            Text(
                "${entry.pkg}  -  uid ${entry.uid}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
        TextButton(text = "Trace", onClick = onClick, colors = ButtonDefaults.textButtonColorsPrimary())
    }
}

/** Tiny in-memory icon cache: avoids re-decoding on every recomposition/scroll. */
private object IconCache {
    private val cache = HashMap<String, Bitmap?>()

    @Synchronized
    fun get(pm: PackageManager, pkg: String): Bitmap? {
        cache[pkg]?.let { return it }
        val b = try {
            pm.getApplicationIcon(pkg).toBitmap(96, 96)
        } catch (t: Throwable) {
            null
        }
        cache[pkg] = b
        return b
    }
}

@Composable
private fun SettingsDialog(show: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var legacy by remember { mutableStateOf(Prefs.legacyMode(ctx)) }
    var key by remember { mutableStateOf(Prefs.superKey(ctx)) }

    OverlayDialog(
        title = "Settings",
        summary = "Default: no key needed, control goes through root. Only turn Legacy mode on if the module reports \"needs Legacy mode\".",
        show = show,
        onDismissRequest = onDismiss
    ) {
        Card {
            SwitchPreference(
                title = "Legacy mode",
                summary = "Old APatch: use SuperKey instead of the root channel",
                checked = legacy,
                onCheckedChange = { legacy = it }
            )
            if (legacy) {
                TextField(
                    value = key,
                    onValueChange = { key = it },
                    label = "SuperKey",
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(text = "Cancel", onClick = onDismiss, modifier = Modifier.weight(1f))
            Spacer(Modifier.size(16.dp))
            TextButton(
                text = "Save",
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    Prefs.setLegacyMode(ctx, legacy)
                    Prefs.setSuperKey(ctx, key.trim())
                    onDismiss()
                }
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Log screen
// ---------------------------------------------------------------------------------------------

// "su" is a virtual filter (matches the [!] alert marker); the rest match " <event> " as
// emitted by the module (see emit() in syscall_logger.c).
private val EVENT_FILTERS = listOf(
    "su", "execve", "connect", "setuid", "ptrace", "mount",
    "stdin", "write", "unlink", "mkdir", "rename", "socket", "bind", "dns", "http", "tls", "fork", "exit"
)

@Composable
private fun LogScreen(label: String?, uid: Int, fresh: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var status by remember { mutableStateOf("Starting...") }
    var allLines by remember { mutableStateOf(listOf<String>()) }
    var lastLine by remember { mutableStateOf<String?>(null) }
    var baselineDone by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val activeFilters = remember { mutableStateMapOf<String, Boolean>().apply { EVENT_FILTERS.forEach { put(it, false) } } }
    var autoTimeout by remember { mutableStateOf(Prefs.autoTimeout(ctx)) }
    var logWrites by remember { mutableStateOf(Prefs.logWrites(ctx)) }
    var filterRevision by remember { mutableStateOf(0) }

    fun matchesFilters(l: String): Boolean {
        val active = EVENT_FILTERS.filter { activeFilters[it] == true }
        if (active.isEmpty()) return true
        return active.any { f -> if (f == "su") l.contains("[!]") else l.contains(" $f ") }
    }

    val shown = remember(allLines, query, filterRevision) {
        allLines.filter { matchesFilters(it) && (query.isEmpty() || it.contains(query, ignoreCase = true)) }
    }

    suspend fun pushHeartbeat() {
        withContext(Dispatchers.IO) {
            ModuleCtl.send(ctx, if (autoTimeout) "hb 1" else "hb 0")
            ModuleCtl.send(ctx, if (logWrites) "wr 1" else "wr 0")
        }
    }

    // Poll dmesg once a second; ping every 5s while autoTimeout is on, to keep the module's
    // target alive (see "Heartbeat" in syscall_logger.c). Leaving this screen stops both,
    // and the module clears the target itself after a few seconds of silence.
    LaunchedEffect(uid) {
        pushHeartbeat()
        var tick = 0
        while (true) {
            if (!paused) {
                val res = withContext(Dispatchers.IO) { Su.run("dmesg | grep SCL; true") }
                if (res.rc != 0) {
                    status = "su failed (rc=${res.rc}): ${res.err.trim()}. Grant root to this app in APatch."
                } else {
                    val lines = res.out.split('\n').filter { it.isNotBlank() }
                    if (!baselineDone) {
                        baselineDone = true
                        if (fresh) {
                            lastLine = lines.lastOrNull()
                            status = "Watching (older log lines skipped)"
                        }
                    }
                    if (baselineDone) {
                        val last = lastLine
                        val start = if (last == null) 0 else {
                            val idx = lines.lastIndexOf(last)
                            if (idx >= 0) idx + 1 else 0
                        }
                        if (start < lines.size) {
                            lastLine = lines.last()
                            val merged = (allLines + lines.subList(start, lines.size)).let {
                                if (it.size > 20000) it.subList(it.size - 20000, it.size) else it
                            }
                            allLines = merged
                            status = "${merged.size} lines"
                        } else if (lines.isEmpty() && res.err.isNotBlank()) {
                            status = "dmesg: ${res.err.trim()}"
                        } else if (lines.isEmpty()) {
                            status = "Root OK, no SCL lines yet"
                        }
                    }
                }
                if (autoTimeout && tick % 5 == 0) {
                    withContext(Dispatchers.IO) { ModuleCtl.send(ctx, "ping") }
                }
            }
            tick++
            delay(1000)
        }
    }

    fun moduleCmd(cmd: String) {
        scope.launch {
            val r = withContext(Dispatchers.IO) { ModuleCtl.send(ctx, cmd) }
            status = if (r.ok) r.out else (r.error ?: "failed")
        }
    }

    fun shownText(): String {
        val text = shown.joinToString("\n")
        return if (text.length > 200_000) text.substring(text.length - 200_000) else text
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            status,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { paused = !paused }) {
                Icon(MiuixIcons.Refresh, contentDescription = if (paused) "Resume" else "Pause")
            }
            IconButton(onClick = { moduleCmd("clear") }) {
                Icon(MiuixIcons.Back, contentDescription = "Stop target")
            }
            IconButton(onClick = {
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val path = "/sdcard/Download/scl_$ts.log"
                val text = shownText()
                scope.launch {
                    if (text.isEmpty()) {
                        status = "Nothing to export"
                    } else {
                        val res = withContext(Dispatchers.IO) { Su.runWithInput("cat > $path", text) }
                        status = if (res.rc == 0) "Saved ${shown.size} lines to $path" else "Export failed: ${res.err.trim()}"
                    }
                }
            }) {
                Icon(MiuixIcons.Download, contentDescription = "Export")
            }
            IconButton(onClick = {
                val i = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, shownText())
                }
                ctx.startActivity(Intent.createChooser(i, "Share log"))
            }) {
                Icon(MiuixIcons.Share, contentDescription = "Share")
            }
            IconButton(onClick = {
                val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("scl", shownText()))
            }) {
                Icon(MiuixIcons.Copy, contentDescription = "Copy")
            }
        }

        // Vertical multi-select popup instead of a horizontally-scrolling chip row.
        val filterEntry = DropdownEntry(
            items = EVENT_FILTERS.map { f ->
                DropdownItem(
                    text = f,
                    selected = activeFilters[f] == true,
                    onClick = {
                        activeFilters[f] = activeFilters[f] != true
                        filterRevision++
                    }
                )
            }
        )
        val activeCount = EVENT_FILTERS.count { activeFilters[it] == true }
        OverlayDropdownMenu(
            title = "Filter",
            summary = if (activeCount == 0) "All events" else "$activeCount selected",
            entry = filterEntry,
            collapseOnSelection = false,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )

        TextField(
            value = query,
            onValueChange = { query = it },
            label = "Text filter",
            leadingIcon = { Icon(MiuixIcons.Search, contentDescription = null, modifier = Modifier.padding(start = 12.dp)) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
        )

        Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            SwitchPreference(
                title = "Auto timeout",
                summary = "Module forgets the target after a few seconds without this screen open",
                checked = autoTimeout,
                onCheckedChange = {
                    autoTimeout = it
                    Prefs.setAutoTimeout(ctx, it)
                    scope.launch { pushHeartbeat() }
                }
            )
            SwitchPreference(
                title = "Log writes",
                summary = "Also log printable write() payloads (noisy)",
                checked = logWrites,
                onCheckedChange = {
                    logWrites = it
                    Prefs.setLogWrites(ctx, it)
                    scope.launch { pushHeartbeat() }
                }
            )
        }

        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
            items(shown) { line ->
                Text(
                    line.replace("[SCL]", ""),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = lineColor(line),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun lineColor(l: String): Color = when {
    l.contains("[!]") -> Color(0xFFE53935)
    l.contains(" execve ") || l.contains(" execveat ") -> Color(0xFF1E88E5)
    l.contains(" connect ") -> Color(0xFF43A047)
    l.contains(" stdin ") -> Color(0xFFFB8C00)
    l.contains(" write ") -> Color(0xFF8D6E63)
    l.contains(" unlink ") || l.contains(" rename ") -> Color(0xFFD81B60)
    l.contains(" mkdir ") -> Color(0xFF8D6E63)
    l.contains(" socket ") || l.contains(" bind ") -> Color(0xFF43A047)
    l.contains(" dns ") -> Color(0xFF00BCD4)
    l.contains(" http ") || l.contains(" tls ") -> Color(0xFFAB47BC)
    else -> MiuixTheme.colorScheme.onSurface
}
