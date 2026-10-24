package app.sclviewer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Polls `su -c "dmesg | grep SCL"` once a second and shows new lines.
 * New lines = lines after the last line seen (kernel timestamps are unique).
 * With EXTRA_FRESH the lines already in dmesg are skipped (a new target was just set).
 */
class LogActivity : AppCompatActivity() {

    private val all = ArrayList<String>()
    private val shown = ArrayList<String>()
    private val adapter = LogAdapter(shown)
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private var lastLine: String? = null
    private var baselineDone = false
    private var skipExisting = false
    private var paused = false
    private var query = ""
    private val activeChips = LinkedHashSet<String>()
    private var autoTimeout = true
    private var logWrites = false

    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var pauseBtn: Button
    private val chipBtns = HashMap<String, Button>()

    private val poller = object : Runnable {
        override fun run() {
            if (!paused) poll()
            handler.postDelayed(this, POLL_MS)
        }
    }

    // Keeps the module's target alive while this screen is open (see "Heartbeat" in the
    // module header comment). Stop pinging (leave this screen) and the module auto-clears
    // the target by itself after HEARTBEAT_TIMEOUT seconds - no separate "stop" step needed.
    private val pinger = object : Runnable {
        override fun run() {
            io.execute { ModuleCtl.send(this@LogActivity, "ping") }
            handler.postDelayed(this, PING_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        skipExisting = intent.getBooleanExtra(EXTRA_FRESH, false)
        autoTimeout = Prefs.autoTimeout(this)
        logWrites = Prefs.logWrites(this)
        val label = intent.getStringExtra(EXTRA_LABEL)
        val uid = intent.getIntExtra(EXTRA_UID, -1)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        val header = TextView(this).apply {
            text = if (label != null) "Target: $label (uid $uid)" else "All tracked processes"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(8), dp(8), 0)
        }
        status = TextView(this).apply {
            text = "Starting..."
            setPadding(dp(8), dp(2), dp(8), dp(4))
        }
        adapter.defColor = status.currentTextColor

        pauseBtn = btn("Pause") {
            paused = !paused
            pauseBtn.text = if (paused) "Resume" else "Pause"
        }
        val clearBtn = btn("Clear") {
            all.clear()
            shown.clear()
            adapter.notifyDataSetChanged()
            status.text = "Cleared (kernel buffer untouched)"
        }
        val stopBtn = btn("Stop target") { moduleCmd("clear") }
        val stateBtn = btn("Module") { moduleCmd("list") }
        val exportBtn = btn("Export") { export() }
        val shareBtn = btn("Share") { share() }
        val copyBtn = btn("Copy") { copyAll() }

        val autoTimeoutSwitch = CheckBox(this).apply {
            text = "Auto timeout"
            isChecked = autoTimeout
            setOnCheckedChangeListener { _, checked ->
                autoTimeout = checked
                Prefs.setAutoTimeout(this@LogActivity, checked)
                applyHeartbeatPref()
            }
        }
        val logWritesSwitch = CheckBox(this).apply {
            text = "Log writes"
            isChecked = logWrites
            setOnCheckedChangeListener { _, checked ->
                logWrites = checked
                Prefs.setLogWrites(this@LogActivity, checked)
                applyHeartbeatPref()
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(pauseBtn)
            addView(clearBtn)
            addView(stopBtn)
            addView(stateBtn)
            addView(exportBtn)
            addView(shareBtn)
            addView(copyBtn)
            addView(autoTimeoutSwitch)
            addView(logWritesSwitch)
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }

        val chipRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (chip in CHIPS) {
            val b = Button(this).apply {
                text = chip
                setAllCaps(false)
                textSize = 11f
                setOnClickListener {
                    if (activeChips.contains(chip)) activeChips.remove(chip) else activeChips.add(chip)
                    updateChipStyles()
                    rebuild(false)
                }
            }
            chipBtns[chip] = b
            chipRow.addView(b)
        }
        updateChipStyles()
        val chipScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        }

        val filter = EditText(this).apply {
            hint = "Filter (execve, setuid, stdin, pid=1234, ...)"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim() ?: ""
                    rebuild(false)
                }
            })
        }

        list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@LogActivity)
            adapter = this@LogActivity.adapter
        }

        root.addView(header, LinearLayout.LayoutParams(-1, -2))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        root.addView(chipScroll, LinearLayout.LayoutParams(-1, -2))
        root.addView(filter, LinearLayout.LayoutParams(-1, -2))
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        handler.post(poller)
        applyHeartbeatPref()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(poller)
        handler.removeCallbacks(pinger)
    }

    /** Tells the module whether the heartbeat timeout applies and whether to log write()
     * payloads, and starts/stops pinging to match. */
    private fun applyHeartbeatPref() {
        handler.removeCallbacks(pinger)
        io.execute {
            ModuleCtl.send(this, if (autoTimeout) "hb 1" else "hb 0")
            ModuleCtl.send(this, if (logWrites) "wr 1" else "wr 0")
        }
        if (autoTimeout) handler.post(pinger)
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ---- polling -------------------------------------------------------

    private fun poll() {
        if (!busy.compareAndSet(false, true)) return
        io.execute {
            val res = Su.run("dmesg | grep SCL; true")
            handler.post {
                busy.set(false)
                onResult(res)
            }
        }
    }

    private fun onResult(res: Su.Res) {
        if (res.rc != 0) {
            status.text = "su failed (rc=${res.rc}): ${res.err.trim()}\nGrant root to this app in APatch."
            return
        }
        val lines = res.out.split('\n').filter { it.isNotBlank() }

        if (!baselineDone) {
            baselineDone = true
            if (skipExisting) {
                lastLine = lines.lastOrNull()
                status.text = "Watching (older log lines skipped)"
                return
            }
        }
        if (lines.isEmpty()) {
            status.text = if (res.err.isNotBlank()) "dmesg: ${res.err.trim()}" else "Root OK, no SCL lines yet"
            return
        }
        val last = lastLine
        val start = if (last == null) 0 else {
            val idx = lines.lastIndexOf(last)
            if (idx >= 0) idx + 1 else 0
        }
        if (start >= lines.size) return
        lastLine = lines.last()
        all.addAll(lines.subList(start, lines.size))
        if (all.size > MAX_LINES) all.subList(0, all.size - MAX_LINES).clear()
        rebuild(true)
    }

    private fun matchesChips(l: String): Boolean {
        if (activeChips.isEmpty()) return true
        return activeChips.any { chip -> if (chip == "su") l.contains("[!]") else l.contains(" $chip ") }
    }

    private fun matches(l: String): Boolean =
        matchesChips(l) && (query.isEmpty() || l.contains(query, ignoreCase = true))

    private fun updateChipStyles() {
        for ((chip, b) in chipBtns) {
            val on = activeChips.contains(chip)
            b.setBackgroundColor(if (on) 0xFF1E88E5.toInt() else 0x00000000)
            b.setTextColor(if (on) Color.WHITE else adapter.defColor)
        }
    }

    private fun rebuild(follow: Boolean) {
        val atBottom = !list.canScrollVertically(1)
        shown.clear()
        for (l in all) if (matches(l)) shown.add(l)
        adapter.notifyDataSetChanged()
        if (follow && atBottom && shown.isNotEmpty()) list.scrollToPosition(shown.size - 1)
        status.text = "${all.size} lines, ${shown.size} shown"
    }

    // ---- actions -------------------------------------------------------

    private fun moduleCmd(cmd: String) {
        io.execute {
            val r = ModuleCtl.send(this, cmd)
            handler.post {
                val msg = if (r.ok) r.out else (r.error ?: "failed")
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun export() {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val path = "/sdcard/Download/scl_$ts.log"
        io.execute {
            val res = Su.run("dmesg | grep SCL > $path; true")
            handler.post {
                val msg = if (res.rc == 0) "Saved $path" else "Export failed: ${res.err.trim()}"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun shownText(): String {
        val text = shown.joinToString("\n")
        return if (text.length > MAX_SHARE_CHARS) text.substring(text.length - MAX_SHARE_CHARS) else text
    }

    private fun share() {
        val i = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, shownText())
        }
        startActivity(Intent.createChooser(i, "Share log"))
    }

    private fun copyAll() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("scl", shownText()))
        Toast.makeText(this, "Copied ${shown.size} lines", Toast.LENGTH_SHORT).show()
    }

    // ---- helpers -------------------------------------------------------

    private fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setAllCaps(false)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_LABEL = "label"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_UID = "uid"
        const val EXTRA_FRESH = "fresh"
        private const val POLL_MS = 1000L
        private const val PING_MS = 5000L
        private const val MAX_LINES = 20000
        private const val MAX_SHARE_CHARS = 200_000
        // "su" is a virtual chip (matches the [!] alert marker); the rest match " <event> "
        // as emitted by the module (see emit() in syscall_logger.c).
        private val CHIPS = listOf(
            "su", "execve", "connect", "setuid", "ptrace", "mount",
            "stdin", "write", "unlink", "mkdir", "rename", "socket", "bind", "dns", "http", "tls", "fork", "exit"
        )
    }
}

class LogAdapter(private val items: List<String>) : RecyclerView.Adapter<LogAdapter.VH>() {

    var defColor: Int = Color.GRAY

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val pad = (6 * parent.resources.displayMetrics.density).toInt()
        val tv = TextView(parent.context).apply {
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        return VH(tv)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val l = items[position]
        holder.tv.text = l.replace("[SCL]", "")
        holder.tv.setTextColor(
            when {
                l.contains("[!]") -> 0xFFE53935.toInt()
                l.contains(" execve ") || l.contains(" execveat ") -> 0xFF1E88E5.toInt()
                l.contains(" connect ") -> 0xFF43A047.toInt()
                l.contains(" stdin ") -> 0xFFFB8C00.toInt()
                l.contains(" write ") -> 0xFF8D6E63.toInt()
                l.contains(" unlink ") || l.contains(" rename ") -> 0xFFD81B60.toInt()
                l.contains(" mkdir ") -> 0xFF8D6E63.toInt()
                l.contains(" socket ") || l.contains(" bind ") -> 0xFF43A047.toInt()
                l.contains(" dns ") -> 0xFF00BCD4.toInt()
                l.contains(" http ") || l.contains(" tls ") -> 0xFFAB47BC.toInt()
                else -> defColor
            }
        )
    }
}
