package com.kowsiq.creativescan.data

import android.content.Context
import com.kowsiq.creativescan.core.ScanFilter

data class AppSettings(
    val jpegQuality: Int = 95,
    val pdfHigh: Boolean = true,
    val fitToPage: Boolean = true,
    val autoCapture: Boolean = true,
    val saveOriginals: Boolean = false,
    val defaultFilter: ScanFilter = ScanFilter.ENHANCED
)

object SettingsStore {
    private const val FILE = "settings"

    fun load(ctx: Context): AppSettings {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = AppSettings()
        return AppSettings(
            jpegQuality = p.getInt("jpegQuality", d.jpegQuality),
            pdfHigh = p.getBoolean("pdfHigh", d.pdfHigh),
            fitToPage = p.getBoolean("fitToPage", d.fitToPage),
            autoCapture = p.getBoolean("autoCapture", d.autoCapture),
            saveOriginals = p.getBoolean("saveOriginals", d.saveOriginals),
            defaultFilter = runCatching { ScanFilter.valueOf(p.getString("defaultFilter", d.defaultFilter.name)!!) }
                .getOrDefault(d.defaultFilter)
        )
    }

    fun save(ctx: Context, s: AppSettings) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putInt("jpegQuality", s.jpegQuality)
            .putBoolean("pdfHigh", s.pdfHigh)
            .putBoolean("fitToPage", s.fitToPage)
            .putBoolean("autoCapture", s.autoCapture)
            .putBoolean("saveOriginals", s.saveOriginals)
            .putString("defaultFilter", s.defaultFilter.name)
            .apply()
    }
}
