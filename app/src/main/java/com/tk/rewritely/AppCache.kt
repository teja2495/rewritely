package com.tk.rewritely

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.TimeUnit

object AppCache {
    private const val PREF_FILE_CACHE = "app_cache_prefs"
    private const val KEY_CACHED_APPS = "cached_apps"
    private const val KEY_CACHE_TIMESTAMP = "cache_timestamp"
    private const val CACHE_DURATION_HOURS = 24L // Cache for 24 hours

    @kotlinx.serialization.Serializable
    private data class CacheableAppInfo(
        val packageName: String,
        val appName: String
    )

    private fun getSharedPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_FILE_CACHE, Context.MODE_PRIVATE)
    }

    fun cacheApps(context: Context, apps: List<AppInfo>) {
        try {
            val cacheableApps = apps.map { CacheableAppInfo(it.packageName, it.appName) }
            val json = Json.encodeToString(cacheableApps)
            val timestamp = System.currentTimeMillis()
            
            getSharedPreferences(context).edit()
                .putString(KEY_CACHED_APPS, json)
                .putLong(KEY_CACHE_TIMESTAMP, timestamp)
                .apply()
        } catch (e: Exception) {
            // Log error if needed
        }
    }

    fun getCachedApps(context: Context): List<AppInfo> {
        return try {
            val prefs = getSharedPreferences(context)
            val json = prefs.getString(KEY_CACHED_APPS, null)
            val timestamp = prefs.getLong(KEY_CACHE_TIMESTAMP, 0L)
            
            if (json != null && isCacheValid(timestamp)) {
                val cacheableApps = Json.decodeFromString<List<CacheableAppInfo>>(json)
                cacheableApps.map { AppInfo(it.packageName, it.appName) }
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun isCacheValid(timestamp: Long): Boolean {
        val currentTime = System.currentTimeMillis()
        val cacheAge = currentTime - timestamp
        return cacheAge < TimeUnit.HOURS.toMillis(CACHE_DURATION_HOURS)
    }

    fun clearCache(context: Context) {
        getSharedPreferences(context).edit().clear().apply()
    }
} 