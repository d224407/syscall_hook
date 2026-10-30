package app.sclviewer

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal class AppEntry(val label: String, val pkg: String, val uid: Int, val system: Boolean)

internal fun loadApps(pm: PackageManager): List<AppEntry> =
    pm.getInstalledApplications(0).map { ai ->
        val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
            (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        AppEntry(pm.getApplicationLabel(ai).toString(), ai.packageName, ai.uid, system)
    }.sortedBy { it.label.lowercase() }

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

/**
 * App list. Tap a row to trace that app alone; use the box on the left to pick several apps
 * (the root then shows "N selected" above the tab bar).
 */
@Composable
internal fun AppsScreen(
    apps: List<AppEntry>,
    notice: String,
    selected: List<String>,
    tracedUids: List<Int>,
    showSystem: Boolean,
    bottomPadding: Dp,
    onToggle: (String) -> Unit,
    onTrace: (AppEntry) -> Unit,
    onUserScroll: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    // Currently traced app(s) first, then checked-but-not-yet-traced ones, then the rest -
    // each group kept alphabetical. Re-sorts only when tracedUids/selected actually change, not
    // on every recomposition, so the list does not jump around while scrolling.
    val shown = remember(apps, query, showSystem, selected, tracedUids) {
        val q = query.lowercase()
        val tracedSet = tracedUids.toSet()
        apps.filter {
            (showSystem || !it.system) &&
                (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q))
        }.sortedWith(
            compareByDescending<AppEntry> { tracedSet.contains(it.uid) }
                .thenByDescending { selected.contains(it.pkg) }
                .thenBy { it.label.lowercase() }
        )
    }
    val scrollHook = rememberScrollHider { onUserScroll() }

    Column(Modifier.fillMaxSize()) {
        TextField(
            value = query,
            onValueChange = { query = it },
            label = "Search name or package",
            leadingIcon = { Icon(MiuixIcons.Search, contentDescription = null, modifier = Modifier.padding(start = 12.dp)) },
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)
        )
        if (notice.isNotEmpty()) {
            Text(
                notice,
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().nestedScroll(scrollHook),
            contentPadding = PaddingValues(bottom = bottomPadding)
        ) {
            items(shown, key = { it.pkg }) { entry ->
                AppRow(
                    entry = entry,
                    checked = selected.contains(entry.pkg),
                    traced = tracedUids.contains(entry.uid),
                    onToggle = { onToggle(entry.pkg) },
                    onClick = { onTrace(entry) }
                )
            }
        }
    }
}

@Composable
private fun AppRow(entry: AppEntry, checked: Boolean, traced: Boolean, onToggle: () -> Unit, onClick: () -> Unit) {
    val ctx = LocalContext.current
    var bitmap by remember(entry.pkg) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(entry.pkg) {
        bitmap = withContext(Dispatchers.IO) { IconCache.get(ctx.packageManager, entry.pkg) }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(24.dp)
                .background(
                    if (checked) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(6.dp)
                )
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(MiuixTheme.colorScheme.onPrimary, RoundedCornerShape(2.dp))
                )
            }
        }
        Box(Modifier.padding(start = 12.dp).size(40.dp), contentAlignment = Alignment.Center) {
            val b = bitmap
            if (b != null) {
                Image(bitmap = b.asImageBitmap(), contentDescription = null, modifier = Modifier.size(40.dp))
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.label, style = MiuixTheme.textStyles.body1)
                if (traced) {
                    Box(
                        Modifier
                            .padding(start = 8.dp)
                            .background(MiuixTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    ) {
                        Text("Tracing", style = MiuixTheme.textStyles.body2, color = MiuixTheme.colorScheme.onPrimary)
                    }
                }
            }
            Text(
                "${entry.pkg}  -  uid ${entry.uid}",
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}
