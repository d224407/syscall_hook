package app.sclviewer

import android.content.Context
import kotlin.random.Random

/**
 * Controls syscall_logger. Default channel needs only root (su): the module watches
 * readlinkat() calls made by root for the path "/.scl:<token> <cmd>" and prints its
 * answer to the kernel log as "[SCL]ctl <token> <result>" - no SuperKey needed, works
 * with current APatch builds that verify the manager app's signature instead.
 *
 * Legacy mode (Prefs.legacyMode) routes through Supercall/supercall.c instead, for
 * older APatch/KernelPatch builds that still authenticate KPM control with a SuperKey.
 */
object ModuleCtl {
    data class Result(val ok: Boolean, val out: String, val error: String?)

    private val SAFE = Regex("[A-Za-z0-9 _.:+-]{1,100}")

    fun send(ctx: Context, cmd: String): Result {
        return if (Prefs.legacyMode(ctx)) sendLegacy(ctx, cmd) else sendRoot(cmd)
    }

    /** Heartbeat: refreshes the module's timeout; the module sends no answer, so no dmesg read. */
    fun ping(ctx: Context) {
        if (Prefs.legacyMode(ctx)) {
            val key = Prefs.superKey(ctx)
            if (key.isNotEmpty()) Supercall.control(key, "ping")
        } else {
            Su.run("readlink '/.scl:p ping' >/dev/null 2>&1; true")
        }
    }

    private fun sendRoot(cmd: String): Result {
        if (!SAFE.matches(cmd)) return Result(false, "", "Bad command")
        val tok = Random.nextInt(0x100000, 0xFFFFFF).toString(16)
        val script = "readlink '/.scl:$tok $cmd' >/dev/null 2>&1; " +
            "dmesg | tail -n 3000 | grep 'SCL]ctl $tok ' | tail -n 1; true"
        val r = Su.run(script)
        if (r.rc != 0) {
            return Result(false, "", "su failed (rc=${r.rc}): ${r.err.trim()}. Grant root to this app in APatch.")
        }
        val line = r.out.trim()
        val marker = "ctl $tok "
        val i = line.indexOf(marker)
        if (i < 0) {
            return Result(false, "", "No answer: syscall_logger (0.4.0+) is not loaded, " +
                "or this APatch needs Legacy mode (see Settings)")
        }
        return Result(true, line.substring(i + marker.length).trim(), null)
    }

    private fun sendLegacy(ctx: Context, cmd: String): Result {
        val key = Prefs.superKey(ctx)
        if (key.isEmpty()) return Result(false, "", "Legacy mode: set the SuperKey in Settings")
        val r = Supercall.control(key, cmd)
        return Result(r.ok, r.out, r.error)
    }
}
