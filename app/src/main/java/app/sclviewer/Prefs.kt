package app.sclviewer

import android.content.Context

object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("scl", Context.MODE_PRIVATE)

    fun showSystem(c: Context): Boolean = sp(c).getBoolean("show_system", false)

    fun setShowSystem(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("show_system", v).apply()
    }

    /** Legacy mode: talk to the module via the old SuperKey supercall instead of the
     * root-only readlinkat channel. Needed only on APatch/KernelPatch builds old enough
     * to still require a SuperKey. */
    fun legacyMode(c: Context): Boolean = sp(c).getBoolean("legacy_mode", false)

    fun setLegacyMode(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("legacy_mode", v).apply()
    }

    fun superKey(c: Context): String = sp(c).getString("superkey", "") ?: ""

    fun setSuperKey(c: Context, v: String) {
        sp(c).edit().putString("superkey", v).apply()
    }

    /** "Auto timeout" switch on the log screen: on by default, matches the module's
     * default (hb_enabled=1). Kept per-app so it's remembered across targets. */
    fun autoTimeout(c: Context): Boolean = sp(c).getBoolean("auto_timeout", true)

    fun setAutoTimeout(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("auto_timeout", v).apply()
    }
}
