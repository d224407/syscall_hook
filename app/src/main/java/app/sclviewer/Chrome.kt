package app.sclviewer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

internal enum class Tab { Apps, Log, Settings }

/** Space the list content must leave at the bottom so the floating bar never covers the last row. */
internal val CHROME_HEIGHT = 88.dp

/**
 * Nested-scroll hook shared by every scrolling screen: tells the root a user scroll is happening
 * (so the floating bar can get out of the way) and hands the vertical delta to the screen.
 * Nothing is consumed, so scrolling itself is unaffected.
 */
@Composable
internal fun rememberScrollHider(onDelta: (Float) -> Unit): NestedScrollConnection {
    val latest by rememberUpdatedState(onDelta)
    return remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y != 0f) latest(available.y)
                return Offset.Zero
            }
        }
    }
}

/**
 * Floating, icon-only tab bar (plus the "N selected" pill on the Apps tab). It slides off the
 * bottom while the user scrolls and returns shortly after scrolling stops. The offset is applied
 * in the graphics layer, so animating it does not recompose or re-layout anything.
 */
// Icons never leave the screen or move - only a subtle shrink + dim while scrolling, so there
// is never a moment where a tap lands on the wrong (just-appeared) target. A gentle spring
// instead of a linear tween keeps it from feeling stiff.
private val ChromeSpring = spring<Float>(dampingRatio = 0.75f, stiffness = 380f)

@Composable
internal fun BoxScope.BottomChrome(
    visible: Boolean,
    bottomInset: Dp,
    tab: Tab,
    onTab: (Tab) -> Unit,
    pickedCount: Int,
    onTraceSelected: () -> Unit,
    onClearSelection: () -> Unit,
    onFilter: () -> Unit,
    onActions: () -> Unit
) {
    val scale by animateFloatAsState(if (visible) 1f else 0.93f, animationSpec = ChromeSpring, label = "chromeScale")
    val dim by animateFloatAsState(if (visible) 1f else 0.6f, animationSpec = ChromeSpring, label = "chromeDim")

    Column(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = bottomInset + 12.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = dim
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (pickedCount > 0 && tab == Tab.Apps) {
            Row(
                Modifier
                    .shadow(8.dp, RoundedCornerShape(22.dp))
                    .background(MiuixTheme.colorScheme.surfaceVariant, RoundedCornerShape(22.dp))
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("$pickedCount selected")
                TextButton(text = "Clear", onClick = onClearSelection)
                TextButton(text = "Trace", onClick = onTraceSelected, colors = ButtonDefaults.textButtonColorsPrimary())
            }
        }
        Row(
            Modifier
                .shadow(10.dp, RoundedCornerShape(28.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant, RoundedCornerShape(28.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TabIcon(MiuixIcons.GridView, "Apps", tab == Tab.Apps) { onTab(Tab.Apps) }
            TabIcon(MiuixIcons.ListView, "Log", tab == Tab.Log) { onTab(Tab.Log) }
            if (tab == Tab.Log) {
                TabIcon(MiuixIcons.Filter, "Filter", false, onFilter)
                TabIcon(MiuixIcons.More, "Actions", false, onActions)
            }
            TabIcon(MiuixIcons.Settings, "Settings", tab == Tab.Settings) { onTab(Tab.Settings) }
        }
    }
}

@Composable
private fun TabIcon(icon: ImageVector, description: String, selected: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(icon, contentDescription = description)
        }
        // selected marker: a short bar under the icon (no icon tinting needed)
        Box(
            Modifier
                .size(width = 14.dp, height = 3.dp)
                .background(
                    if (selected) MiuixTheme.colorScheme.primary else Color.Transparent,
                    RoundedCornerShape(2.dp)
                )
        )
    }
}
