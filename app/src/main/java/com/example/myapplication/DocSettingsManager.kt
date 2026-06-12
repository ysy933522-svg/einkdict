package com.example.myapplication

import android.content.Context
import org.json.JSONObject
import java.io.File

object DocSettingsManager {
    private const val FILE_NAME = "doc_settings.json"
    private val settingsMap = mutableMapOf<String, DocSettings>()
    private var loaded = false

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) {
            try {
                val jsonStr = file.readText()
                val jsonObj = JSONObject(jsonStr)
                val keys = jsonObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val obj = jsonObj.getJSONObject(key)
                    settingsMap[key] = DocSettings(
                        contrast = if (obj.has("contrast")) obj.getDouble("contrast").toFloat() else null,
                        brightness = if (obj.has("brightness")) obj.getInt("brightness") else null,
                        sharpness = if (obj.has("sharpness")) obj.getDouble("sharpness").toFloat() else null,
                        usePrintMode = if (obj.has("use_print_mode")) obj.getBoolean("use_print_mode") else null
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        loaded = true
    }

    fun getSettings(context: Context, docPath: String): DocSettings {
        ensureLoaded(context)
        return settingsMap[docPath] ?: DocSettings()
    }

    fun saveSettings(context: Context, docPath: String, settings: DocSettings) {
        ensureLoaded(context)
        settingsMap[docPath] = settings
        flushToFile(context)
    }

    fun clearSettings(context: Context, docPath: String) {
        ensureLoaded(context)
        settingsMap.remove(docPath)
        flushToFile(context)
    }

    private fun flushToFile(context: Context) {
        val jsonObj = JSONObject()
        for ((path, settings) in settingsMap) {
            val obj = JSONObject()
            settings.contrast?.let { obj.put("contrast", it.toDouble()) }
            settings.brightness?.let { obj.put("brightness", it) }
            settings.sharpness?.let { obj.put("sharpness", it.toDouble()) }
            settings.usePrintMode?.let { obj.put("use_print_mode", it) }
            jsonObj.put(path, obj)
        }
        File(context.filesDir, FILE_NAME).writeText(jsonObj.toString(2))
    }

}