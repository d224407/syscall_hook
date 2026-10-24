package app.sclviewer

/** Runs a command through `su -c` (needed only to read dmesg). */
object Su {
    data class Res(val rc: Int, val out: String, val err: String)

    fun run(cmd: String): Res {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).start()
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val rc = p.waitFor()
            p.destroy()
            Res(rc, out, err)
        } catch (e: Exception) {
            Res(-1, "", e.message ?: e.toString())
        }
    }

    /** Like run(), but feeds [input] to the command's stdin (used to write files as root). */
    fun runWithInput(cmd: String, input: String): Res {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).start()
            p.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
            val out = p.inputStream.bufferedReader().readText()
            val err = p.errorStream.bufferedReader().readText()
            val rc = p.waitFor()
            p.destroy()
            Res(rc, out, err)
        } catch (e: Exception) {
            Res(-1, "", e.message ?: e.toString())
        }
    }
}
