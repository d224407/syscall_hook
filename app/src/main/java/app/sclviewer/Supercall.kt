package app.sclviewer

/**
 * Legacy mode only: talks to the syscall_logger KPM the way the APatch app
 * used to for a KPM control call, before it switched to verifying the
 * manager app's signature - KernelPatch supercall (syscall 45) authenticated
 * by the SuperKey. See supercall.c for the wire format.
 */
object Supercall {
    private const val MODULE = "syscall_logger"
    private val KEY_RE = Regex("[A-Za-z0-9]{1,63}") // APatch SuperKey: letters and digits

    private var loaded = false
    private var loadError: String? = null

    private external fun nativeControl(key: String, name: String, args: String, out: ByteArray): Long

    data class Result(val ok: Boolean, val rc: Long, val out: String, val error: String?)

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("sclnative")
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message
            false
        }
    }

    /** Sends one command string to the module (add/del/target/clear/uid/hb/verbose/list ...). */
    fun control(key: String, args: String): Result {
        if (!KEY_RE.matches(key)) {
            return Result(false, -22, "", "SuperKey must be letters and digits only")
        }
        if (!ensureLoaded()) {
            return Result(false, -1, "", "native library not loaded: $loadError")
        }
        val buf = ByteArray(4096)
        val rc = nativeControl(key, MODULE, args, buf)
        var end = buf.indexOf(0.toByte())
        if (end < 0) end = buf.size
        val out = String(buf, 0, end, Charsets.UTF_8)
        val error = when (rc) {
            0L -> null
            -2L -> "No answer: wrong SuperKey, or syscall_logger is not loaded"
            else -> "Supercall failed, rc=$rc"
        }
        return Result(rc == 0L, rc, out, error)
    }
}
