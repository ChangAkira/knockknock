package com.example.testppppp.remote

import android.content.Context
import android.content.SharedPreferences

class InteractionModePreferences(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): InteractionMode =
        InteractionMode.fromPrefsString(prefs.getString(KEY_MODE, null))

    fun save(mode: InteractionMode) {
        prefs.edit().putString(KEY_MODE, InteractionMode.toPrefsString(mode)).apply()
    }

    companion object {
        private const val PREFS_NAME = "interaction_mode"
        private const val KEY_MODE = "mode"
    }
}
