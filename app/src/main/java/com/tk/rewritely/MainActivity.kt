package com.tk.rewritely

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible

class MainActivity : AppCompatActivity() {

    // View declarations
    private lateinit var editTextApiKey: EditText
    private lateinit var textViewApiKeyStatus: TextView
    private lateinit var buttonSaveKey: Button
    private lateinit var buttonResetKey: Button
    private lateinit var textViewAccessibilityStatus: TextView
    private lateinit var buttonGrantAccessibility: Button
    private lateinit var textViewOverlayStatus: TextView
    private lateinit var buttonGrantOverlay: Button
    private lateinit var buttonSelectApps: Button

    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize views
        editTextApiKey = findViewById(R.id.editTextApiKey)
        textViewApiKeyStatus = findViewById(R.id.textViewApiKeyStatus)
        buttonSaveKey = findViewById(R.id.buttonSaveKey)
        buttonResetKey = findViewById(R.id.buttonResetKey)
        textViewAccessibilityStatus = findViewById(R.id.textViewAccessibilityStatus)
        buttonGrantAccessibility = findViewById(R.id.buttonGrantAccessibility)
        textViewOverlayStatus = findViewById(R.id.textViewOverlayStatus)
        buttonGrantOverlay = findViewById(R.id.buttonGrantOverlay)

        // Setup listeners
        setupApiKeyListeners()
        setupPermissionButtonListeners()
    }

    override fun onResume() {
        super.onResume()
        // Update status and UI visibility when the activity resumes
        Log.d(TAG, "onResume: Updating UI status...")
        updateApiKeyStatus()
        updatePermissionStatusUI()
        updateApiKeyUIElementsVisibility()  // NEW: Update visibility of API key UI elements
    }

    private fun setupApiKeyListeners() {
        buttonSaveKey.setOnClickListener {
            val apiKey = editTextApiKey.text.toString().trim()
            if (apiKey.trim().length === 164 && apiKey.startsWith("sk-proj-")) {
                SecurePrefs.saveApiKey(this, apiKey)
                editTextApiKey.text.clear()
                updateApiKeyStatus()
                updateApiKeyUIElementsVisibility() // NEW: Update visibility after saving
                Toast.makeText(this, "API Key saved securely.", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Please enter a valid OpenAI API Key (should start with 'sk-proj').", Toast.LENGTH_LONG).show()
            }
        }

        buttonResetKey.setOnClickListener {
            SecurePrefs.clearApiKey(this)
            updateApiKeyStatus()
            updateApiKeyUIElementsVisibility() // NEW: Update visibility after reset
            Toast.makeText(this, "API Key reset.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupPermissionButtonListeners() {
        buttonGrantAccessibility.setOnClickListener {
            Log.d(TAG, "Accessibility grant button clicked. Opening settings.")
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivity(intent)
                Toast.makeText(this, "Please find and enable 'Input Assistant Service'.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Log.e(TAG, "Could not open Accessibility Settings.", e)
                Toast.makeText(this, "Could not open Accessibility Settings.", Toast.LENGTH_SHORT).show()
            }
        }

        buttonGrantOverlay.setOnClickListener {
            Log.d(TAG, "Overlay grant button clicked. Opening settings.")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    startActivity(intent)
                    Toast.makeText(this, "Please grant the 'Draw over other apps' permission.", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Could not open Overlay Settings.", e)
                    Toast.makeText(this, "Could not open Overlay Settings.", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateApiKeyStatus() {
        val storedKey = SecurePrefs.getApiKey(this)
        val statusText = if (storedKey != null && storedKey.length > 4) {
            getString(R.string.api_key_status_masked, storedKey.takeLast(4))
        } else if (storedKey != null) {
            "Set (Invalid length)"
        } else {
            getString(R.string.api_key_status_not_set)
        }
        textViewApiKeyStatus.text = statusText
    }

    private fun updatePermissionStatusUI() {
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        Log.d(TAG, "Accessibility Service Enabled Check Result: $isAccessibilityEnabled")
        textViewAccessibilityStatus.text = if (isAccessibilityEnabled) {
            getString(R.string.permission_granted)
        } else {
            getString(R.string.permission_not_granted)
        }
        buttonGrantAccessibility.isVisible = !isAccessibilityEnabled

        val hasOverlayPerm = hasOverlayPermission()
        Log.d(TAG, "Overlay Permission Enabled Check Result: $hasOverlayPerm")
        textViewOverlayStatus.text = if (hasOverlayPerm) {
            getString(R.string.permission_granted)
        } else {
            getString(R.string.permission_not_granted)
        }
        buttonGrantOverlay.isVisible = !hasOverlayPerm && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
    }

    /**
     * NEW FUNCTION: Controls the visibility of API key related UI elements.
     * Hides the input field and save button when a key exists,
     * and hides the reset button when no key exists.
     */
    private fun updateApiKeyUIElementsVisibility() {
        val apiKey = SecurePrefs.getApiKey(this)
        val hasApiKey = !apiKey.isNullOrBlank()

        editTextApiKey.isVisible = !hasApiKey
        buttonSaveKey.isVisible = !hasApiKey
        buttonResetKey.isVisible = hasApiKey
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val expectedComponentName = ComponentName(this, RewritelyService::class.java)
        val expectedComponentNameString = expectedComponentName.flattenToString()
        Log.d(TAG, "Checking Settings.Secure for: $expectedComponentNameString")

        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        Log.d(TAG, "Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES: $enabledServicesSetting")

        if (enabledServicesSetting == null) {
            Log.w(TAG, "Enabled accessibility services setting is null.")
            return false
        }

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()
            Log.d(TAG, "Checking against enabled service: $componentNameString")
            if (expectedComponentNameString.equals(componentNameString, ignoreCase = true)) {
                Log.i(TAG, "Accessibility Service IS ENABLED (found in Settings.Secure).")
                return true
            }
        }

        Log.i(TAG, "Accessibility Service IS DISABLED (not found in Settings.Secure).")
        return false
    }

    private fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(this)
        } else {
            true
        }
    }
}