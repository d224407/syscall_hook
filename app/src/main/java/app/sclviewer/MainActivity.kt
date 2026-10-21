package app.sclviewer

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

/**
 * Pick an app; the module is told `target <uid>` through the root control channel
 * (see ModuleCtl) and the log screen opens.
 */
class MainActivity : AppCompatActivity() {

    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var all: List<AppEntry> = emptyList()
    private var query = ""
    private var showSystem = false

    private lateinit var adapter: AppAdapter
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showSystem = Prefs.showSystem(this)

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        val title = TextView(this).apply {
            text = "Choose the app to trace"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(10), dp(12), 0)
        }
        status = TextView(this).apply {
            text = "..."
            setPadding(dp(12), dp(2), dp(12), dp(4))
        }

        val logBtn = btn("Open log") {
            startActivity(Intent(this, LogActivity::class.java))
        }
        val stopBtn = btn("Stop target") { stopTarget() }
        val settingsBtn = btn("Settings") { showSettingsDialog() }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(logBtn)
            addView(stopBtn)
            addView(settingsBtn)
        }
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }

        val search = EditText(this).apply {
            hint = "Search name or package"
            isSingleLine = true
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    query = s?.toString()?.trim() ?: ""
                    applyFilter()
                }
            })
        }
        val sys = CheckBox(this).apply {
            text = "System"
            isChecked = showSystem
            setOnCheckedChangeListener { _, checked ->
                showSystem = checked
                Prefs.setShowSystem(this@MainActivity, checked)
                applyFilter()
            }
        }
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(search, LinearLayout.LayoutParams(0, -2, 1f))
            addView(sys, LinearLayout.LayoutParams(-2, -2))
        }

        adapter = AppAdapter(packageManager) { entry -> pick(entry) }
        val list = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }

        root.addView(title, LinearLayout.LayoutParams(-1, -2))
        root.addView(status, LinearLayout.LayoutParams(-1, -2))
        root.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        root.addView(searchRow, LinearLayout.LayoutParams(-1, -2))
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        loadApps()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        io.shutdown()
    }

    // ---- app list ------------------------------------------------------

    private fun loadApps() {
        status.text = "Loading apps..."
        io.execute {
            val pm = packageManager
            val list = pm.getInstalledApplications(0).map { ai ->
                val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                    (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                AppEntry(pm.getApplicationLabel(ai).toString(), ai.packageName, ai.uid, system)
            }.sortedBy { it.label.lowercase() }
            handler.post {
                all = list
                applyFilter()
                refreshStatus()
            }
        }
    }

    private fun applyFilter() {
        val q = query.lowercase()
        adapter.submit(all.filter {
            (showSystem || !it.system) &&
                (q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.lowercase().contains(q))
        })
    }

    // ---- module control ------------------------------------------------

    private fun pick(e: AppEntry) {
        status.text = "Setting target ${e.label}..."
        io.execute {
            val r = ModuleCtl.send(this, "target ${e.uid}")
            handler.post {
                if (r.ok) {
                    status.text = "Module: ${r.out}"
                    startActivity(
                        Intent(this, LogActivity::class.java)
                            .putExtra(LogActivity.EXTRA_LABEL, e.label)
                            .putExtra(LogActivity.EXTRA_PKG, e.pkg)
                            .putExtra(LogActivity.EXTRA_UID, e.uid)
                            .putExtra(LogActivity.EXTRA_FRESH, true)
                    )
                } else {
                    status.text = r.error ?: "failed"
                }
            }
        }
    }

    private fun stopTarget() {
        io.execute {
            val r = ModuleCtl.send(this, "clear")
            handler.post { status.text = if (r.ok) "Module: ${r.out}" else (r.error ?: "failed") }
        }
    }

    private fun refreshStatus() {
        io.execute {
            val r = ModuleCtl.send(this, "list")
            handler.post { status.text = if (r.ok) "Module: ${r.out}" else (r.error ?: "failed") }
        }
    }

    private fun showSettingsDialog() {
        val legacy = CheckBox(this).apply {
            text = "Legacy mode (old APatch: SuperKey instead of root channel)"
            isChecked = Prefs.legacyMode(this@MainActivity)
        }
        val key = EditText(this).apply {
            hint = "SuperKey (legacy mode only)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            setText(Prefs.superKey(this@MainActivity))
            isEnabled = legacy.isChecked
        }
        legacy.setOnCheckedChangeListener { _, checked -> key.isEnabled = checked }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(legacy)
            addView(key, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        AlertDialog.Builder(this)
            .setTitle("Settings")
            .setMessage("Default: no key needed, control goes through root. Only turn Legacy mode on if the module reports \"needs Legacy mode\".")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                Prefs.setLegacyMode(this, legacy.isChecked)
                Prefs.setSuperKey(this, key.text.toString().trim())
                refreshStatus()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- helpers -------------------------------------------------------

    private fun btn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        setAllCaps(false)
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
