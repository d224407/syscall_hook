package app.sclviewer

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.drawable.toBitmap
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

class AppEntry(val label: String, val pkg: String, val uid: Int, val system: Boolean)

/** App list row: icon, label, "package - uid". Icons are loaded lazily and cached. */
class AppAdapter(
    private val pm: PackageManager,
    private val onPick: (AppEntry) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    class VH(
        val root: LinearLayout,
        val icon: ImageView,
        val title: TextView,
        val sub: TextView
    ) : RecyclerView.ViewHolder(root)

    private var items: List<AppEntry> = emptyList()
    private val cache = LruCache<String, Bitmap>(300)
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun submit(list: List<AppEntry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * d).toInt()

        val icon = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        }
        val title = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        val sub = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            alpha = 0.7f
            maxLines = 1
        }
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(title)
            addView(sub)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            }
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            isClickable = true
            isFocusable = true
            addView(icon)
            addView(col)
        }
        val tv = TypedValue()
        if (ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            row.setBackgroundResource(tv.resourceId)
        }
        return VH(row, icon, title, sub)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val e = items[position]
        holder.title.text = e.label
        holder.sub.text = "${e.pkg}  -  uid ${e.uid}"
        holder.icon.tag = e.pkg
        holder.root.setOnClickListener { onPick(e) }

        val cached = cache.get(e.pkg)
        if (cached != null) {
            holder.icon.setImageBitmap(cached)
            return
        }
        holder.icon.setImageDrawable(null)
        pool.execute {
            val bmp: Bitmap? = try {
                pm.getApplicationIcon(e.pkg).toBitmap(96, 96)
            } catch (t: Throwable) {
                null
            }
            if (bmp != null) {
                cache.put(e.pkg, bmp)
                main.post {
                    if (holder.icon.tag == e.pkg) holder.icon.setImageBitmap(bmp)
                }
            }
        }
    }
}
