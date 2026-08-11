package com.fnmusic.tv.update

import android.content.Context
import androidx.core.content.edit

internal class UpdatePreferences(context: Context) {
    private val preferences = context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    fun isIgnored(versionCode: Long): Boolean = versionCode.toString() in ignoredVersionCodes()

    fun ignore(versionCode: Long) {
        if (versionCode <= 0) return
        preferences.edit { putStringSet(KEY_IGNORED, ignoredVersionCodes() + versionCode.toString()) }
    }

    fun cleanBelow(currentVersionCode: Long) {
        val cleaned = ignoredVersionCodes().filterTo(mutableSetOf()) {
            it.toLongOrNull()?.let { code -> code >= currentVersionCode } == true
        }
        preferences.edit { putStringSet(KEY_IGNORED, cleaned) }
    }

    private fun ignoredVersionCodes(): Set<String> = preferences.getStringSet(KEY_IGNORED, emptySet())
        ?.filterTo(mutableSetOf()) { it.toLongOrNull()?.let { code -> code > 0 } == true }
        .orEmpty()

    private companion object {
        const val FILE_NAME = "device_update_preferences"
        const val KEY_IGNORED = "ignored_version_codes"
    }
}
