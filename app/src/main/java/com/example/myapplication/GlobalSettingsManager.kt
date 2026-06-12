package com.example.myapplication

import android.content.Context

object GlobalSettingsManager {
    private const val PREFS_NAME = "global_display_settings"

    fun load(context: Context): GlobalSettings {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return GlobalSettings(
            contrast = prefs.getFloat("contrast", 1.6f),
            brightness = prefs.getInt("brightness", 0),
            sharpness = prefs.getFloat("sharpness", 0f),
            usePrintMode = prefs.getBoolean("use_print_mode", false)
        )
    }

    fun save(context: Context, settings: GlobalSettings) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putFloat("contrast", settings.contrast)
            putInt("brightness", settings.brightness)
            putFloat("sharpness", settings.sharpness)
            putBoolean("use_print_mode", settings.usePrintMode)
            apply()
        }
    }
}