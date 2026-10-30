package app.sclviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Pause
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// "su" is a virtual filter (matches the [!] alert marker); the rest match " <event> " as emitted
// by the module (see emit() in syscall_logger.c).
internal val EVENT_FILTERS = listOf(
    "crash", "su", "execve", "connect", "setuid", "ptrace", "mount",
    "stdin", "write", "unlink", "mkdir", "rename", "socket", "bind", "dns", "http", "tls", "fork", "exit"
)

private const val MAX_LINES = 20000

private val TS_RE = Regex("^\\[\\s*(\\d+\\.\\d+)]")

/** Kernel timestamp of a dmesg line ("[ 1234.567890] ..."), or null when it has none. */
private fun dmesgTs(line: String): Double? = TS_RE.find(line)?.groupValues?.get(1)?.toDoubleOrNull()

/**
 * Everything the log tab shows. Lives in the root so lines keep collecting while another tab is
 * open and nothing is lost when switching tabs.
 */
internal class LogState {
    var status by mutableStateOf("Pick an app on the first tab")
    var lines by mutableStateOf(listOf<String>())
    var revision by mutableStateOf(0) // bumped whenever lines are appended
    var paused by mutableStateOf(false)
    var query by mutableStateOf("")
    val filters = mutableStateMapOf<String, Boolean>().also { m -> EVENT_FILTERS.forEach { m[it] = false } }
    var filterRevision by mutableStateOf(0)
    var followTail by mutableStateOf(true)

    // dmesg bookkeeping: take only lines newer than what we already have (robust when the kernel
    // buffer wraps). lastLine is the fallback for a dmesg without timestamps.
    private var lastTs = -1.0
    private var seenAtLastTs = setOf<String>()
    private var useTs = true
    private var lastLine: String? = null
    private var baselineDone = false

    /** New session: forget everything and skip what the kernel buffer already holds. */
    fun reset(message: String) {
        lines = emptyList()
        revision++
        baselineDone = false
        lastTs = -1.0
        seenAtLastTs = emptySet()
        useTs = true
        lastLine = null
        followTail = true
        status = message
    }

    fun visibleLines(): List<String> {
        val active = EVENT_FILTERS.filter { filters[it] == true }
        val q = query
        return lines.filter { l ->
            (active.isEmpty() || active.any { f -> if (f == "su") l.contains("[!]") else l.contains(" $f ") }) &&
                (q.isEmpty() || l.contains(q, ignoreCase = true))
        }
    }

    fun shownText(): String {
        val t = visibleLines().joinToString("\n")
        return if (t.length > 200_000) t.substring(t.length - 200_000) else t
    }

    /** Feed one `dmesg | grep SCL` snapshot. */
    fun onSnapshot(out: String, err: String) {
        // control answers ("[SCL]ctl <token> ...") are chatter between app and module
        val all = out.split('\n').filter { it.isNotBlank() && !it.contains("SCL]ctl ") }
        if (!baselineDone) {
            baselineDone = true
            useTs = all.isEmpty() || all.any { dmesgTs(it) != null }
            if (useTs) {
                lastTs = all.mapNotNull { dmesgTs(it) }.maxOrNull() ?: -1.0
                seenAtLastTs = all.filter { dmesgTs(it) == lastTs }.toSet()
            } else {
                lastLine = all.lastOrNull()
            }
            status = "Watching (older log lines skipped)"
            return
        }
        var fresh: List<String> = emptyList()
        if (useTs) {
            fresh = all.filter { l ->
                val t = dmesgTs(l)
                t != null && (t > lastTs || (t == lastTs && l !in seenAtLastTs))
            }
            if (fresh.isNotEmpty()) {
                val newTs = fresh.maxOf { dmesgTs(it) ?: lastTs }
                val atNewTs = fresh.filter { dmesgTs(it) == newTs }
                seenAtLastTs = if (newTs > lastTs) atNewTs.toSet() else seenAtLastTs + atNewTs
                lastTs = newTs
            }
        } else {
            val idx = lastLine?.let { all.lastIndexOf(it) } ?: -1
            fresh = if (idx >= 0) all.subList(idx + 1, all.size) else all
            lastLine = all.lastOrNull() ?: lastLine
        }
        if (fresh.isNotEmpty()) {
            lines = (lines + fresh).takeLast(MAX_LINES)
            revision++
            status = "${lines.size} lines"
        } else if (lines.isEmpty()) {
            status = if (err.isNotBlank()) "dmesg: ${err.trim()}" else "Root OK, no SCL lines yet"
        }
    }
}

// ---- module commands (blocking: call on Dispatchers.IO) --------------------------------------

/** First uid replaces the module's targets, the others are added; returns an error or null. */
internal fun applyTargets(ctx: Context, uids: List<Int>): String? {
    var failure: String? = null
    if (uids.size > 1) failure = ModuleCtl.send(ctx, "clear").takeIf { !it.ok }?.error
    for ((i, uid) in uids.withIndex()) {
        if (failure != null) break
        val r = ModuleCtl.send(ctx, if (i == 0) "target $uid" else "target+ $uid")
        if (!r.ok) failure = r.error ?: "failed"
    }
    return failure
}

/** Pushes the saved auto-timeout / log-writes switches to the module. */
internal fun pushModuleSettings(ctx: Context) {
    ModuleCtl.send(ctx, if (Prefs.autoTimeout(ctx)) "hb 1" else "hb 0")
    ModuleCtl.send(ctx, if (Prefs.logWrites(ctx)) "wr 1" else "wr 0")
}

internal suspend fun exportLog(text: String): String = withContext(Dispatchers.IO) {
    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val path = "/sdcard/Download/scl_$ts.log"
    val res = Su.runWithInput("cat > $path", text)
    if (res.rc == 0) "Saved to $path" else "Export failed: ${res.err.trim()}"
}

internal fun shareText(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    ctx.startActivity(Intent.createChooser(i, "Share log"))
}

internal fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("scl", text))
}

// ---- screen ----------------------------------------------------------------------------------

@Composable
internal fun LogScreen(log: LogState, listState: LazyListState, bottomPadding: Dp, onUserScroll: () -> Unit) {
    val scope = rememberCoroutineScope()
    val shown = remember(log.lines, log.query, log.filterRevision) { log.visibleLines() }

    // Thumb activity: a timestamp written by the scroll hook plus a light polling loop, so a
    // scroll gesture does not recompose this screen once per event.
    val lastScroll = remember { LongArray(1) }
    var scrolling by remember { mutableStateOf(false) }
    var lingering by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val idle = SystemClock.uptimeMillis() - lastScroll[0]
            scrolling = idle < 300
            lingering = idle < 1200
            delay(60)
        }
    }

    val scrollHook = rememberScrollHider { dy ->
        lastScroll[0] = SystemClock.uptimeMillis()
        onUserScroll()
        if (dy > 0f) log.followTail = false // finger moving down = reading older lines
    }

    // New lines while following -> jump to the newest one. Programmatic scrolling does not go
    // through nested scroll, so it never counts as the user interrupting.
    LaunchedEffect(log.revision, log.filterRevision) {
        if (log.followTail && shown.isNotEmpty()) listState.scrollToItem(shown.size - 1)
    }
    // Back at the very bottom (by scrolling or dragging) -> resume following.
    LaunchedEffect(listState) {
        snapshotFlow { listState.canScrollForward }.collect { canForward ->
            if (!canForward) log.followTail = true
        }
    }

    Box(Modifier.fillMaxSize().nestedScroll(scrollHook)) {
        Column(Modifier.fillMaxSize()) {
            Text(
                log.status,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).fillMaxWidth().padding(start = 8.dp, end = 12.dp),
                contentPadding = PaddingValues(bottom = bottomPadding)
            ) {
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

        ScrollThumb(
            listState = listState,
            count = shown.size,
            visible = lingering || pressed,
            active = scrolling || pressed,
            onPress = { pressed = it },
            onDrag = { fraction, dy ->
                lastScroll[0] = SystemClock.uptimeMillis()
                if (dy > 1f || dy < -1f) onUserScroll()
                if (shown.isNotEmpty()) {
                    val idx = (fraction * (shown.size - 1)).toInt().coerceIn(0, shown.size - 1)
                    log.followTail = idx >= shown.size - 2
                    scope.launch { listState.scrollToItem(idx) }
                }
            }
        )

        if (!log.followTail) {
            TextButton(
                text = "Latest",
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = {
                    log.followTail = true
                    if (shown.isNotEmpty()) scope.launch { listState.scrollToItem(shown.size - 1) }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = bottomPadding)
            )
        }
    }
}

/**
 * Slider on the right edge: its position follows the list, dragging it jumps through the log.
 * It fades in while scrolling and out after ~1.2s of stillness; while the user scrolls or has a
 * finger on it, it grows a little (wider and taller). While invisible it does not take touches.
 */
@Composable
private fun BoxScope.ScrollThumb(
    listState: LazyListState,
    count: Int,
    visible: Boolean,
    active: Boolean,
    onPress: (Boolean) -> Unit,
    onDrag: (fraction: Float, dy: Float) -> Unit
) {
    var heightPx by remember { mutableStateOf(1) }
    val density = LocalDensity.current
    val alpha by animateFloatAsState(if (visible) 1f else 0f, animationSpec = tween(150), label = "thumbAlpha")
    val thumbW by animateDpAsState(if (active) 10.dp else 4.dp, animationSpec = tween(100), label = "thumbW")
    val thumbH by animateDpAsState(if (active) 72.dp else 56.dp, animationSpec = tween(100), label = "thumbH")
    val thumbPx = with(density) { thumbH.roundToPx() }
    val edgePx = with(density) { 2.dp.roundToPx() }
    val fraction = if (count <= 1) 0f else (listState.firstVisibleItemIndex.toFloat() / (count - 1)).coerceIn(0f, 1f)

    Box(
        Modifier
            .align(Alignment.CenterEnd)
            .fillMaxHeight()
            .width(32.dp)
            .onSizeChanged { heightPx = it.height.coerceAtLeast(1) }
            .then(
                if (visible) Modifier.pointerInput(count) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        onPress(true) // touch start: the thumb grows right away
                        down.consume()
                        var lastY = down.position.y
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val y = change.position.y
                            if (y != lastY) onDrag((y / heightPx).coerceIn(0f, 1f), y - lastY)
                            lastY = y
                            change.consume()
                        }
                        onPress(false)
                    }
                } else Modifier
            )
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(-edgePx, (fraction * (heightPx - thumbPx).coerceAtLeast(0)).toInt()) }
                .width(thumbW)
                .height(thumbH)
                .graphicsLayer { this.alpha = alpha }
                .background(MiuixTheme.colorScheme.primary, RoundedCornerShape(5.dp))
        )
    }
}

// ---- dialogs opened from the floating bar ----------------------------------------------------

/** Vertical multi-select list of events plus a text filter. */
@Composable
internal fun FilterDialog(show: Boolean, log: LogState, onDismiss: () -> Unit) {
    OverlayDialog(
        title = "Filter",
        summary = "Events to show (none selected = all)",
        show = show,
        onDismissRequest = onDismiss
    ) {
        TextField(
            value = log.query,
            onValueChange = { log.query = it },
            label = "Text filter",
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                EVENT_FILTERS.forEach { f ->
                    SwitchPreference(
                        title = f,
                        checked = log.filters[f] == true,
                        onCheckedChange = {
                            log.filters[f] = it
                            log.filterRevision++
                        }
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            TextButton(
                text = "Reset",
                onClick = {
                    EVENT_FILTERS.forEach { log.filters[it] = false }
                    log.query = ""
                    log.filterRevision++
                },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.size(16.dp))
            TextButton(
                text = "Done",
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColorsPrimary(),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
internal fun ActionsDialog(
    show: Boolean,
    paused: Boolean,
    onDismiss: () -> Unit,
    onPauseToggle: () -> Unit,
    onStop: () -> Unit,
    onClearView: () -> Unit,
    onExport: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit
) {
    OverlayDialog(
        title = "Log",
        summary = "Export, share and copy use the lines currently shown",
        show = show,
        onDismissRequest = onDismiss
    ) {
        ActionRow(if (paused) MiuixIcons.Play else MiuixIcons.Pause, if (paused) "Resume" else "Pause") {
            onDismiss(); onPauseToggle()
        }
        ActionRow(MiuixIcons.Close, "Stop tracking") { onDismiss(); onStop() }
        ActionRow(MiuixIcons.Delete, "Clear view") { onDismiss(); onClearView() }
        ActionRow(MiuixIcons.Download, "Export to Download") { onDismiss(); onExport() }
        ActionRow(MiuixIcons.Share, "Share") { onDismiss(); onShare() }
        ActionRow(MiuixIcons.Copy, "Copy") { onDismiss(); onCopy() }
    }
}

@Composable
private fun ActionRow(icon: ImageVector, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.size(12.dp))
        Text(text)
    }
}

@Composable
private fun lineColor(l: String): Color = when {
    l.contains("[!]") -> Color(0xFFE53935)
    l.contains(" crash ") -> Color(0xFFC2185B)
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
