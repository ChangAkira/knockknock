package com.example.testppppp.remote

import android.content.Context
import android.content.SharedPreferences

class RemoteDemoPreferences(
    context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): RemoteDemoSettings {
        return RemoteDemoSettings(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            baseUrl = prefs.getString(KEY_BASE_URL, "") ?: "",
            authToken = prefs.getString(KEY_TOKEN, "") ?: "",
            topAction = readAction(KEY_TOP, RemotePcAction.PPT_PREVIOUS),
            rightAction = readAction(KEY_RIGHT, RemotePcAction.SHOW_DESKTOP),
            bottomAction = readAction(KEY_BOTTOM, RemotePcAction.PPT_NEXT),
        )
    }

    fun save(settings: RemoteDemoSettings) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, settings.enabled)
            .putString(KEY_BASE_URL, settings.baseUrl.trim())
            .putString(KEY_TOKEN, settings.authToken)
            .putString(KEY_TOP, settings.topAction.jsonName)
            .putString(KEY_RIGHT, settings.rightAction.jsonName)
            .putString(KEY_BOTTOM, settings.bottomAction.jsonName)
            .apply()
    }

    private fun readAction(key: String, default: RemotePcAction): RemotePcAction {
        val raw = prefs.getString(key, null) ?: return default
        return RemotePcAction.fromJsonName(raw) ?: default
    }

    companion object {
        private const val PREFS_NAME = "remote_demo"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_BASE_URL = "base_url"
        private const val KEY_TOKEN = "token"
        private const val KEY_TOP = "map_top"
        private const val KEY_RIGHT = "map_right"
        private const val KEY_BOTTOM = "map_bottom"
    }
}
