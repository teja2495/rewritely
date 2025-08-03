package com.tk.rewritely

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api

class MainActivity : ComponentActivity() {

    private val TAG = "MainActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme()
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun MainScreen() {
        val context = LocalContext.current
        var apiKey by remember { mutableStateOf("") }
        var hasApiKey by remember { mutableStateOf(false) }
        var isAccessibilityEnabled by remember { mutableStateOf(false) }
        var hasOverlayPermission by remember { mutableStateOf(false) }

        // Update states when the screen is displayed
        LaunchedEffect(Unit) {
            updateStates(context) { apiKeyExists, accessibility, overlay ->
                hasApiKey = apiKeyExists
                isAccessibilityEnabled = accessibility
                hasOverlayPermission = overlay
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Rewritely") },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // API Key Section
                ApiKeySection(
                    apiKey = apiKey,
                    onApiKeyChange = { apiKey = it },
                    hasApiKey = hasApiKey,
                    onSaveKey = {
                        if (apiKey.trim().length == 164 && apiKey.startsWith("sk-proj-")) {
                            SecurePrefs.saveApiKey(context, apiKey)
                            apiKey = ""
                            hasApiKey = true
                            Toast.makeText(context, "API Key saved securely.", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Please enter a valid OpenAI API Key (should start with 'sk-proj').", Toast.LENGTH_LONG).show()
                        }
                    },
                    onResetKey = {
                        SecurePrefs.clearApiKey(context)
                        hasApiKey = false
                        Toast.makeText(context, "API Key reset.", Toast.LENGTH_SHORT).show()
                    }
                )

                // Divider
                HorizontalDivider()

                // Permissions Section
                PermissionsSection(
                    isAccessibilityEnabled = isAccessibilityEnabled,
                    hasOverlayPermission = hasOverlayPermission,
                    onGrantAccessibility = {
                        try {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            context.startActivity(intent)
                            Toast.makeText(context, "Please find and enable 'Input Assistant Service'.", Toast.LENGTH_LONG).show()
                        } catch (e: Exception) {
                            Log.e(TAG, "Could not open Accessibility Settings.", e)
                            Toast.makeText(context, "Could not open Accessibility Settings.", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onGrantOverlay = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            try {
                                val intent = Intent(
                                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                                Toast.makeText(context, "Please grant the 'Draw over other apps' permission.", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                Log.e(TAG, "Could not open Overlay Settings.", e)
                                Toast.makeText(context, "Could not open Overlay Settings.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                )
            }
        }
    }

    @Composable
    fun ApiKeySection(
        apiKey: String,
        onApiKeyChange: (String) -> Unit,
        hasApiKey: Boolean,
        onSaveKey: () -> Unit,
        onResetKey: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "OpenAI API Key",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                // API Key Status Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Status:",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (hasApiKey) {
                            val storedKey = SecurePrefs.getApiKey(LocalContext.current)
                            if (storedKey != null && storedKey.length > 4) {
                                stringResource(R.string.api_key_status_masked, storedKey.takeLast(4))
                            } else {
                                "Set (Invalid length)"
                            }
                        } else {
                            stringResource(R.string.api_key_status_not_set)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (hasApiKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // API Key Input (only show if no key exists)
                if (!hasApiKey) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = onApiKeyChange,
                        label = { Text("Enter your OpenAI API Key (sk-...)") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                            focusedLabelColor = MaterialTheme.colorScheme.primary,
                            unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )

                    Button(
                        onClick = onSaveKey,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Save API Key")
                    }
                } else {
                    Button(
                        onClick = onResetKey,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Reset Key")
                    }
                }
            }
        }
    }

    @Composable
    fun PermissionsSection(
        isAccessibilityEnabled: Boolean,
        hasOverlayPermission: Boolean,
        onGrantAccessibility: () -> Unit,
        onGrantOverlay: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Permissions",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Text(
                    text = stringResource(R.string.permissions_info),
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Accessibility Permission
                PermissionRow(
                    label = stringResource(R.string.accessibility_permission),
                    isGranted = isAccessibilityEnabled,
                    onGrant = onGrantAccessibility
                )

                // Overlay Permission
                PermissionRow(
                    label = stringResource(R.string.overlay_permission),
                    isGranted = hasOverlayPermission,
                    onGrant = onGrantOverlay
                )
            }
        }
    }

    @Composable
    fun PermissionRow(
        label: String,
        isGranted: Boolean,
        onGrant: () -> Unit
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = if (isGranted) {
                            stringResource(R.string.permission_granted)
                        } else {
                            stringResource(R.string.permission_not_granted)
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                    )
                }

                if (!isGranted) {
                    Button(
                        onClick = onGrant,
                        modifier = Modifier.wrapContentWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(
                            text = if (label.contains("Accessibility")) {
                                stringResource(R.string.grant_accessibility_permission)
                            } else {
                                stringResource(R.string.grant_overlay_permission)
                            }
                        )
                    }
                }
            }
        }
    }

    private fun updateStates(
        context: android.content.Context,
        onStatesUpdated: (Boolean, Boolean, Boolean) -> Unit
    ) {
        val apiKey = SecurePrefs.getApiKey(context)
        val hasApiKey = !apiKey.isNullOrBlank()
        val isAccessibilityEnabled = isAccessibilityServiceEnabled()
        val hasOverlayPermission = hasOverlayPermission()
        
        onStatesUpdated(hasApiKey, isAccessibilityEnabled, hasOverlayPermission)
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