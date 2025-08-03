package com.tk.rewritely

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object SecurePrefs {

    private const val PREF_FILE_SECURE = "secure_api_prefs"
    private const val KEY_OPENAI_API = "openai_api_key"

    private const val TAG = "SecurePrefs"

    private fun getEncryptedSharedPreferences(context: Context): SharedPreferences? {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                PREF_FILE_SECURE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating EncryptedSharedPreferences", e)
            null // Return null if creation fails
        }
    }

    fun saveApiKey(context: Context, apiKey: String) {
        getEncryptedSharedPreferences(context)?.edit()?.putString(KEY_OPENAI_API, apiKey)?.apply()
            ?: Log.e(TAG, "Failed to save API key, SharedPreferences instance is null")
    }

    fun getApiKey(context: Context): String? {
        return getEncryptedSharedPreferences(context)?.getString(KEY_OPENAI_API, null)
    }

    fun clearApiKey(context: Context) {
        getEncryptedSharedPreferences(context)?.edit()?.remove(KEY_OPENAI_API)?.apply()
            ?: Log.e(TAG, "Failed to clear API key, SharedPreferences instance is null")
    }


}