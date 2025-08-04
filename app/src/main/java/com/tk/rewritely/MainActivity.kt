package com.tk.rewritely

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.wrapContentHeight

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.tk.rewritely.ui.CustomOptionsScreen
import com.tk.rewritely.ui.AppSelectionScreen

class MainActivity : ComponentActivity() {

        private val TAG = "MainActivity"

    private fun handleKeystoreIssues() {
        try {
            // Test if EncryptedSharedPreferences can be created
            if (!SecurePrefs.testEncryptedSharedPreferences(this)) {
                Log.w(TAG, "Keystore corruption detected, attempting to reset and migrate data")
                SecurePrefs.resetKeystoreAndMigrateData(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling keystore issues", e)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check for keystore corruption and handle it
        handleKeystoreIssues()
        
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

    override fun onResume() {
        super.onResume()
        // Refresh states when returning from settings
        updateStates(this) { apiKeyExists, accessibility, overlay ->
            // Update the UI state if needed
            // Note: Since we're using Compose, the UI will be updated through LaunchedEffect
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
        var showCustomOptionsScreen by remember { mutableStateOf(false) }
        var showAppSelectionScreen by remember { mutableStateOf(false) }

        // Update states when the screen is displayed
        LaunchedEffect(Unit) {
            updateStates(context) { apiKeyExists, accessibility, overlay ->
                hasApiKey = apiKeyExists
                isAccessibilityEnabled = accessibility
                hasOverlayPermission = overlay
            }
        }
        
        // Refresh states when activity resumes (e.g., returning from settings)
        val lifecycleOwner = LocalLifecycleOwner.current
        LaunchedEffect(lifecycleOwner) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                updateStates(context) { apiKeyExists, accessibility, overlay ->
                    hasApiKey = apiKeyExists
                    isAccessibilityEnabled = accessibility
                    hasOverlayPermission = overlay
                }
            }
        }

        if (showCustomOptionsScreen) {
            CustomOptionsScreen(
                onBackPressed = { showCustomOptionsScreen = false }
            )
        } else if (showAppSelectionScreen) {
            AppSelectionScreen(
                onBackPressed = { showAppSelectionScreen = false }
            )
        } else {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { 
                            Text(
                                text = "Rewritely",
                                modifier = Modifier.padding(start = 16.dp),
                                fontWeight = FontWeight.Bold
                            ) 
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                }
            ) { paddingValues ->
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 20.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // API Key Section - show when permissions are granted OR when API key is set
                    if ((isAccessibilityEnabled && hasOverlayPermission) || hasApiKey) {
                        item {
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
                        }

                        // Divider
                        item {
                            HorizontalDivider()
                        }
                    }

                    // App Selection Section - only show when all permissions are granted
                    if (isAccessibilityEnabled && hasOverlayPermission) {
                        item {
                            AppSelectionSection(
                                onChooseApps = { showAppSelectionScreen = true }
                            )
                        }

                        // Divider
                        item {
                            HorizontalDivider()
                        }
                    }

                    // Custom Options Section - only show when all permissions are granted
                    if (isAccessibilityEnabled && hasOverlayPermission) {
                        item {
                            CustomOptionsSection(
                                onManageCustomOptions = { showCustomOptionsScreen = true }
                            )
                        }

                        // Divider
                        item {
                            HorizontalDivider()
                        }
                    }

                    // Permissions Section (only show if any permission is not granted)
                    if (!isAccessibilityEnabled || !hasOverlayPermission) {
                        item {
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
                // API Key Status (only show if key exists)
                if (hasApiKey) {
                    val storedKey = SecurePrefs.getApiKey(LocalContext.current)
                    if (storedKey != null && storedKey.length > 4) {
                        Text(
                            text = "OpenAI API Key: ****${storedKey.takeLast(4)}",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                // API Key Input (only show if no key exists)
                if (!hasApiKey) {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = onApiKeyChange,
                        label = { Text("Enter your OpenAI API Key") },
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

                    Spacer(modifier = Modifier.height(5.dp))

                    Button(
                        onClick = onSaveKey,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("Save")
                    }
                } else {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onResetKey,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.wrapContentWidth()
                        ) {
                            Text(
                                text = "Reset Key",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun AppSelectionSection(onChooseApps: () -> Unit) {
        val context = LocalContext.current
        var appSettings by remember { mutableStateOf(SecurePrefs.getAppSelectionSettings(context)) }
        
        // Update settings when the composable is recomposed
        LaunchedEffect(Unit) {
            appSettings = SecurePrefs.getAppSelectionSettings(context)
        }
        
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
                    text = stringResource(R.string.app_selection_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = stringResource(R.string.app_selection_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Show current selection status
                val context = LocalContext.current
                val selectedAppInfo = remember(appSettings.selectedAppPackages) {
                    if (appSettings.selectedAppPackages.isEmpty()) {
                        emptyList()
                    } else {
                        val packageManager = context.packageManager
                        appSettings.selectedAppPackages.mapNotNull { packageName ->
                            try {
                                val appInfo = packageManager.getApplicationInfo(packageName, 0)
                                AppInfo(
                                    packageName = packageName,
                                    appName = appInfo.loadLabel(packageManager).toString(),
                                    icon = appInfo.loadIcon(packageManager)
                                )
                            } catch (e: Exception) {
                                null
                            }
                        }
                    }
                }
                
                if (selectedAppInfo.isEmpty()) {
                    Text(
                        text = stringResource(R.string.all_apps_enabled),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    // Show selected apps count and chips
                    Column(
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Selected Apps (${selectedAppInfo.size}):",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(selectedAppInfo) { app ->
                                AssistChip(
                                    onClick = {
                                        val updatedSelectedApps = appSettings.selectedAppPackages.toMutableSet()
                                        updatedSelectedApps.remove(app.packageName)
                                        val newSettings = AppSelectionSettings(
                                            showInAllApps = updatedSelectedApps.isEmpty(),
                                            selectedAppPackages = updatedSelectedApps
                                        )
                                        SecurePrefs.saveAppSelectionSettings(context, newSettings)
                                        appSettings = newSettings
                                    },
                                    label = { 
                                        Text(
                                            text = app.appName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        ) 
                                    },
                                    trailingIcon = {
                                        Icon(
                                            imageVector = Icons.Default.Clear,
                                            contentDescription = "Remove ${app.appName}",
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.onSurface
                                        )
                                    },
                                    colors = AssistChipDefaults.assistChipColors(
                                        containerColor = androidx.compose.ui.graphics.Color.Transparent,
                                        labelColor = MaterialTheme.colorScheme.onSurface
                                    ),
                                    border = androidx.compose.foundation.BorderStroke(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                )
                            }
                        }
                    }
                }

                Button(
                    onClick = onChooseApps,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.wrapContentWidth()
                ) {
                    Text(stringResource(R.string.choose_apps))
                }
            }
        }
    }

    @Composable
    fun CustomOptionsSection(onManageCustomOptions: () -> Unit) {
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
                    text = stringResource(R.string.custom_options_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(
                    text = stringResource(R.string.custom_options_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = onManageCustomOptions,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier.wrapContentWidth()
                ) {
                    Text(stringResource(R.string.manage_custom_options))
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
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Text(
                    text = stringResource(R.string.permissions_info),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Only show permissions that are not granted
                if (!isAccessibilityEnabled) {
                    PermissionRow(
                        label = stringResource(R.string.accessibility_permission),
                        onGrant = onGrantAccessibility
                    )
                }

                if (!hasOverlayPermission) {
                    PermissionRow(
                        label = stringResource(R.string.overlay_permission),
                        onGrant = onGrantOverlay
                    )
                }
            }
        }
    }

    @Composable
    fun PermissionRow(
        label: String,
        onGrant: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 0.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                modifier = Modifier.wrapContentWidth()
            ) {
                Text(
                    text = "Grant Permission",
                    style = MaterialTheme.typography.bodyMedium
                )
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
        val enabledServicesSetting = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        if (enabledServicesSetting == null) {
            Log.w(TAG, "Enabled accessibility services setting is null.")
            return false
        }

        val colonSplitter = TextUtils.SimpleStringSplitter(':')
        colonSplitter.setString(enabledServicesSetting)

        while (colonSplitter.hasNext()) {
            val componentNameString = colonSplitter.next()

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