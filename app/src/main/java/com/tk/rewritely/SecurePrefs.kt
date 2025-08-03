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
    private const val PREF_FILE_FALLBACK = "fallback_api_prefs"
    private const val KEY_OPENAI_API = "openai_api_key"
    private const val KEY_CUSTOM_OPTIONS = "custom_options"
    private const val KEY_APP_SELECTION_SETTINGS = "app_selection_settings"

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
            // Try to clear corrupted keystore and recreate
            if (e is javax.crypto.AEADBadTagException) {
                Log.w(TAG, "Detected keystore corruption, attempting to clear and recreate")
                clearCorruptedKeystore(context)
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
                } catch (retryException: Exception) {
                    Log.e(TAG, "Failed to recreate EncryptedSharedPreferences after clearing", retryException)
                    getFallbackSharedPreferences(context)
                }
            } else {
                getFallbackSharedPreferences(context)
            }
        }
    }

    private fun clearCorruptedKeystore(context: Context) {
        try {
            // Clear the encrypted preferences file
            context.deleteSharedPreferences(PREF_FILE_SECURE)
            
            // Clear the fallback file as well to ensure clean state
            context.deleteSharedPreferences(PREF_FILE_FALLBACK)
            
            Log.i(TAG, "Cleared corrupted keystore files")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing corrupted keystore", e)
        }
    }

    private fun getFallbackSharedPreferences(context: Context): SharedPreferences {
        Log.w(TAG, "Using fallback SharedPreferences (unencrypted)")
        return context.getSharedPreferences(PREF_FILE_FALLBACK, Context.MODE_PRIVATE)
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

    fun resetDefaultOption(context: Context) {
        val currentOptions = getCustomOptions(context).toMutableList()
        val defaultOption = currentOptions.find { it.isDefault }
        if (defaultOption != null) {
            val index = currentOptions.indexOf(defaultOption)
            currentOptions[index] = defaultOption.copy(
                prompt = "Rewrite in common language, NEVER use Em Dashes: "
            )
            saveCustomOptions(context, currentOptions)
        }
    }

    fun resetChatGptOption(context: Context) {
        val currentOptions = getCustomOptions(context).toMutableList()
        val chatGptOption = currentOptions.find { it.isChatGpt }
        if (chatGptOption != null) {
            val index = currentOptions.indexOf(chatGptOption)
            currentOptions[index] = chatGptOption.copy(
                prompt = "Rewrite in common language, NEVER use emdashes: "
            )
            saveCustomOptions(context, currentOptions)
        }
    }

    // App Selection Settings Management
    fun saveAppSelectionSettings(context: Context, settings: AppSelectionSettings) {
        try {
            val json = Json.encodeToString(settings)
            getEncryptedSharedPreferences(context)?.edit()?.putString(KEY_APP_SELECTION_SETTINGS, json)?.apply()
                ?: Log.e(TAG, "Failed to save app selection settings, SharedPreferences instance is null")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving app selection settings", e)
        }
    }

    fun getAppSelectionSettings(context: Context): AppSelectionSettings {
        return try {
            val json = getEncryptedSharedPreferences(context)?.getString(KEY_APP_SELECTION_SETTINGS, null)
            if (json != null) {
                Json.decodeFromString<AppSelectionSettings>(json)
            } else {
                // Return default settings (show in all apps)
                AppSelectionSettings()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading app selection settings", e)
            AppSelectionSettings()
        }
    }

    fun shouldShowIconInApp(context: Context, packageName: String?): Boolean {
        if (packageName == null) return false
        
        val settings = getAppSelectionSettings(context)
        // If showInAllApps is true OR if no apps are selected, show in all apps
        // Otherwise, only show in selected apps
        return settings.showInAllApps || settings.selectedAppPackages.isEmpty() || settings.selectedAppPackages.contains(packageName)
    }

    // Method to clear all data (useful for debugging or resetting)
    fun clearAllData(context: Context) {
        try {
            context.deleteSharedPreferences(PREF_FILE_SECURE)
            context.deleteSharedPreferences(PREF_FILE_FALLBACK)
            Log.i(TAG, "Cleared all SecurePrefs data")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing all data", e)
        }
    }

    // Method to test if EncryptedSharedPreferences can be created successfully
    fun testEncryptedSharedPreferences(context: Context): Boolean {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                context,
                "test_secure_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Test EncryptedSharedPreferences failed", e)
            false
        }
    }

    // Method to reset keystore and migrate data if needed
    fun resetKeystoreAndMigrateData(context: Context) {
        try {
            // First, try to read any existing data from fallback
            val fallbackPrefs = context.getSharedPreferences(PREF_FILE_FALLBACK, Context.MODE_PRIVATE)
            val apiKey = fallbackPrefs.getString(KEY_OPENAI_API, null)
            val customOptions = fallbackPrefs.getString(KEY_CUSTOM_OPTIONS, null)
            val appSettings = fallbackPrefs.getString(KEY_APP_SELECTION_SETTINGS, null)
            
            // Clear all data
            clearAllData(context)
            
            // Try to recreate encrypted preferences
            val testResult = testEncryptedSharedPreferences(context)
            if (testResult) {
                Log.i(TAG, "Keystore reset successful, recreating encrypted preferences")
                // The next call to getEncryptedSharedPreferences should work now
            } else {
                Log.w(TAG, "Keystore reset failed, will use fallback preferences")
            }
            
            // Restore data if it existed
            if (apiKey != null) {
                saveApiKey(context, apiKey)
            }
            if (customOptions != null) {
                getEncryptedSharedPreferences(context)?.edit()?.putString(KEY_CUSTOM_OPTIONS, customOptions)?.apply()
            }
            if (appSettings != null) {
                getEncryptedSharedPreferences(context)?.edit()?.putString(KEY_APP_SELECTION_SETTINGS, appSettings)?.apply()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error during keystore reset and migration", e)
        }
    }
}