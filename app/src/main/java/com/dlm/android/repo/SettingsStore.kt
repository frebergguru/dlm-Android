package com.dlm.android.repo

import android.content.Context
import androidx.core.content.edit

/** Durable global settings. The C daemon kept these in memory only; persisting
 * them survives process death so the queue resumes with the user's choices. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("dlm_settings", Context.MODE_PRIVATE)

    var maxActive: Int
        get() = prefs.getInt(KEY_MAX_ACTIVE, 3)
        set(v) = prefs.edit { putInt(KEY_MAX_ACTIVE, v.coerceAtLeast(1)) }

    var maxSpeed: Long
        get() = prefs.getLong(KEY_MAX_SPEED, 0)
        set(v) = prefs.edit { putLong(KEY_MAX_SPEED, v.coerceAtLeast(0)) }

    var globalAutostart: Boolean
        get() = prefs.getBoolean(KEY_GLOBAL_AUTOSTART, true)
        set(v) = prefs.edit { putBoolean(KEY_GLOBAL_AUTOSTART, v) }

    /** Persisted SAF tree URI for exporting finished downloads (null => none). */
    var downloadTreeUri: String?
        get() = prefs.getString(KEY_TREE_URI, null)
        set(v) = prefs.edit { putString(KEY_TREE_URI, v) }

    var autoExport: Boolean
        get() = prefs.getBoolean(KEY_AUTO_EXPORT, false)
        set(v) = prefs.edit { putBoolean(KEY_AUTO_EXPORT, v) }

    private companion object {
        const val KEY_MAX_ACTIVE = "max_active"
        const val KEY_MAX_SPEED = "max_speed"
        const val KEY_GLOBAL_AUTOSTART = "global_autostart"
        const val KEY_TREE_URI = "download_tree_uri"
        const val KEY_AUTO_EXPORT = "auto_export"
    }
}
