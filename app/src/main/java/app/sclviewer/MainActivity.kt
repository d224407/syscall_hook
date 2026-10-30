package app.sclviewer

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** True while the activity is resumed: polling and heartbeat pause when the app is in the background. */
internal object Foreground {
    @Volatile
    var on = true
}

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

    override fun onResume() {
        super.onResume()
        Foreground.on = true
    }

    override fun onPause() {
        super.onPause()
        Foreground.on = false
    }
}

/**
 * Root: owns everything that must survive a tab switch (log lines, the poller, targets) and the
 * floating bar. Popups (OverlayDialog) must live inside the Scaffold, so they are placed here too.
 */
@Composable
private fun AppRoot() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val log = remember { LogState() }
    val logListState = rememberLazyListState()

    var tab by remember { mutableStateOf(Tab.Apps) }
    var apps by remember { mutableStateOf(listOf<AppEntry>()) }
    var notice by remember { mutableStateOf("") }
    val selected = remember { mutableStateListOf<String>() } // package names
    var showSystem by remember { mutableStateOf(Prefs.showSystem(ctx)) }
    var sessionId by remember { mutableStateOf(0) }
    var targetUids by remember { mutableStateOf(listOf<Int>()) }
    var showFilter by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }

    // Floating bar: hidden the moment a user scroll starts, back 250 ms after it stops. The
    // timestamp is a plain holder so scroll events do not recompose the root.
    var barVisible by remember { mutableStateOf(true) }
    val lastScroll = remember { LongArray(1) }
    val onUserScroll: () -> Unit = remember {
        {
            lastScroll[0] = SystemClock.uptimeMillis()
            barVisible = false
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (!barVisible && SystemClock.uptimeMillis() - lastScroll[0] > 250) barVisible = true
            delay(40)
        }
    }
    LaunchedEffect(tab) { barVisible = true }

    // System/gesture back: go to the previous screen instead of exiting the app. It only
    // switches tabs - it does not touch the log, so an accidental tap on the Apps tab (or a back
    // press) never wipes what has been collected. The log is cleared only when a new session
    // actually starts (log.reset() below, keyed on sessionId).
    BackHandler(enabled = tab != Tab.Apps) {
        tab = Tab.Apps
    }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(ctx.packageManager) }
    }

    // One poller for the whole app (restarted for every new session): reads the module's log from
    // dmesg once a second and, while auto timeout is on, sends the heartbeat every 5 s.
    LaunchedEffect(sessionId) {
        log.reset(if (sessionId == 0) "Waiting for events..." else "Setting up...")
        withContext(Dispatchers.IO) { pushModuleSettings(ctx) }
        var tick = 0
        var wasForeground = true
        while (true) {
            val fg = Foreground.on
            if (fg) {
                if (!wasForeground && targetUids.isNotEmpty()) {
                    // back after being away: the module may have timed its targets out meanwhile
                    withContext(Dispatchers.IO) {
                        applyTargets(ctx, targetUids)
                        pushModuleSettings(ctx)
                    }
                }
                if (!log.paused) {
                    val res = withContext(Dispatchers.IO) { Su.run("dmesg | grep SCL | tail -n 4000; true") }
                    if (res.rc != 0) {
                        log.status = "su failed (rc=${res.rc}): ${res.err.trim()}. Grant root to this app in APatch."
                    } else {
                        log.onSnapshot(res.out, res.err)
                    }
                }
                if (tick % 5 == 0 && Prefs.autoTimeout(ctx)) {
                    withContext(Dispatchers.IO) { ModuleCtl.ping(ctx) }
                }
            }
            wasForeground = fg
            tick++
            delay(1000)
        }
    }

    fun startTrace(entries: List<AppEntry>) {
        if (entries.isEmpty()) return
        scope.launch {
            notice = "Setting ${entries.size} target(s)..."
            val uids = entries.map { it.uid }
            val error = withContext(Dispatchers.IO) { applyTargets(ctx, uids) }
            if (error == null) {
                notice = ""
                targetUids = uids
                selected.clear()
                sessionId++
                tab = Tab.Log
            } else {
                notice = error
            }
        }
    }

    Scaffold(topBar = {}) { padding ->
        val bottomInset = padding.calculateBottomPadding()
        val listBottom = CHROME_HEIGHT + bottomInset

        Box(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            when (tab) {
                Tab.Apps -> AppsScreen(
                    apps = apps,
                    notice = notice,
                    selected = selected,
                    tracedUids = targetUids,
                    showSystem = showSystem,
                    bottomPadding = listBottom,
                    onToggle = { pkg -> if (selected.contains(pkg)) selected.remove(pkg) else selected.add(pkg) },
                    onTrace = { entry -> startTrace(listOf(entry)) },
                    onUserScroll = onUserScroll
                )
                Tab.Log -> LogScreen(
                    log = log,
                    listState = logListState,
                    bottomPadding = listBottom,
                    onUserScroll = onUserScroll
                )
                Tab.Settings -> SettingsScreen(
                    bottomPadding = listBottom,
                    onShowSystemChange = { showSystem = it },
                    onModuleSettingsChanged = { scope.launch(Dispatchers.IO) { pushModuleSettings(ctx) } },
                    onUserScroll = onUserScroll
                )
            }

            BottomChrome(
                visible = barVisible,
                bottomInset = bottomInset,
                tab = tab,
                onTab = { tab = it },
                pickedCount = selected.size,
                onTraceSelected = { startTrace(apps.filter { selected.contains(it.pkg) }) },
                onClearSelection = { selected.clear() },
                onFilter = { showFilter = true },
                onActions = { showActions = true }
            )
        }

        FilterDialog(show = showFilter, log = log, onDismiss = { showFilter = false })
        ActionsDialog(
            show = showActions,
            paused = log.paused,
            onDismiss = { showActions = false },
            onPauseToggle = { log.paused = !log.paused },
            onStop = {
                scope.launch {
                    val r = withContext(Dispatchers.IO) { ModuleCtl.send(ctx, "clear") }
                    targetUids = emptyList()
                    log.status = if (r.ok) "Stopped tracking" else (r.error ?: "failed")
                }
            },
            onClearView = {
                log.lines = emptyList()
                log.revision++
                log.status = "View cleared"
            },
            onExport = {
                val text = log.shownText()
                scope.launch { log.status = if (text.isEmpty()) "Nothing to export" else exportLog(text) }
            },
            onShare = { shareText(ctx, log.shownText()) },
            onCopy = {
                copyText(ctx, log.shownText())
                log.status = "Copied"
            }
        )
    }
}
