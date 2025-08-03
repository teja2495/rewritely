package com.tk.rewritely

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

object SecurePrefs {

    private const val PREF_FILE_SECURE = "secure_api_prefs"
    private const val KEY_OPENAI_API = "openai_api_key"
    private const val KEY_CUSTOM_OPTIONS = "custom_options"

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

    // Custom Options Management
    fun saveCustomOptions(context: Context, options: List<CustomOption>) {
        try {
            val json = Json.encodeToString(options)
            getEncryptedSharedPreferences(context)?.edit()?.putString(KEY_CUSTOM_OPTIONS, json)?.apply()
                ?: Log.e(TAG, "Failed to save custom options, SharedPreferences instance is null")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving custom options", e)
        }
    }

    fun getCustomOptions(context: Context): List<CustomOption> {
        return try {
            val json = getEncryptedSharedPreferences(context)?.getString(KEY_CUSTOM_OPTIONS, null)
            if (json != null) {
                Json.decodeFromString<List<CustomOption>>(json)
            } else {
                // Return default options if none exist
                getDefaultOptions()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading custom options", e)
            getDefaultOptions()
        }
    }

    private fun getDefaultOptions(): List<CustomOption> {
        return listOf(
            CustomOption(
                id = UUID.randomUUID().toString(),
                name = "Default",
                prompt = "Rewrite in common language, NEVER use Em Dashes: ",
                isDefault = true
            ),
            CustomOption(
                id = UUID.randomUUID().toString(),
                name = "ChatGPT",
                prompt = "Rewrite in common language, NEVER use emdashes: ",
                isChatGpt = true
            )
        )
    }

    fun addCustomOption(context: Context, name: String, prompt: String): CustomOption? {
        val currentOptions = getCustomOptions(context).toMutableList()
        
        // Check if we already have 4 custom options (excluding default and ChatGPT)
        val customOptionsCount = currentOptions.count { !it.isDefault && !it.isChatGpt }
        if (customOptionsCount >= 4) {
            return null // Return null to indicate limit reached
        }
        
        val newOption = CustomOption(
            id = UUID.randomUUID().toString(),
            name = name,
            prompt = prompt
        )
        
        currentOptions.add(newOption)
        saveCustomOptions(context, currentOptions)
        
        return newOption
    }

    fun updateCustomOption(context: Context, optionId: String, name: String, prompt: String) {
        val currentOptions = getCustomOptions(context).toMutableList()
        val index = currentOptions.indexOfFirst { it.id == optionId }
        
        if (index != -1) {
            currentOptions[index] = currentOptions[index].copy(name = name, prompt = prompt)
            saveCustomOptions(context, currentOptions)
        }
    }

    fun deleteCustomOption(context: Context, optionId: String) {
        val currentOptions = getCustomOptions(context).toMutableList()
        currentOptions.removeAll { it.id == optionId }
        saveCustomOptions(context, currentOptions)
    }

    fun resetToDefaults(context: Context) {
        saveCustomOptions(context, getDefaultOptions())
    }
}