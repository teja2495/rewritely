package com.tk.rewritely.ui

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.size
import com.tk.rewritely.AppCache
import com.tk.rewritely.AppInfo
import com.tk.rewritely.AppSelectionSettings
import com.tk.rewritely.R
import com.tk.rewritely.SecurePrefs
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSelectionScreen(onBackPressed: () -> Unit) {
    val context = LocalContext.current
    var appSettings by remember { mutableStateOf(SecurePrefs.getAppSelectionSettings(context)) }
    var searchQuery by remember { mutableStateOf("") }
    var allApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var filteredApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var selectedApps by remember { mutableStateOf(appSettings.selectedAppPackages.toMutableSet()) }
    var isLoading by remember { mutableStateOf(true) }
    
    // Focus management for search bar
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Load all apps with caching
    LaunchedEffect(Unit) {
        isLoading = true
        val cachedApps = AppCache.getCachedApps(context)
        if (cachedApps.isNotEmpty()) {
            allApps = cachedApps
            filteredApps = cachedApps
            isLoading = false
        }
        
        // Load fresh data in background
        val packageManager = context.packageManager
        val installedApps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { packageManager.getLaunchIntentForPackage(it.packageName) != null }
            .map { 
                AppInfo(
                    packageName = it.packageName,
                    appName = it.loadLabel(packageManager).toString(),
                    icon = it.loadIcon(packageManager)
                )
            }
            .sortedBy { it.appName.lowercase() }
        
        // Cache the fresh data
        AppCache.cacheApps(context, installedApps)
        
        allApps = installedApps
        filteredApps = installedApps
        isLoading = false
    }
    
    // Auto-focus search bar and open keyboard when screen appears (only when not loading)
    LaunchedEffect(isLoading) {
        if (!isLoading) {
            delay(100) // Small delay to ensure the screen is fully rendered
            focusRequester.requestFocus()
            keyboardController?.show()
        }
    }

    // Filter apps based on search query
    LaunchedEffect(searchQuery, allApps) {
        filteredApps = if (searchQuery.isBlank()) {
            allApps
        } else {
            allApps.filter { 
                it.appName.contains(searchQuery, ignoreCase = true) ||
                it.packageName.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    // Handle Android back button
    BackHandler {
        onBackPressed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        text = stringResource(R.string.app_selection_title),
                        modifier = Modifier.padding(start = 16.dp),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = onBackPressed) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            // Loading state - show only loading message
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "App list is loading...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            // Normal state - show search bar and content
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Search bar at the top
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search apps...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search"
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                        .focusRequester(focusRequester),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                )

                // Content area
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 20.dp)
                ) {
                    // Search instruction text
                    if (searchQuery.isBlank()) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(top = 32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "Search for apps that you want the floating icon to show up",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                        }
                    } else {
                        // Apps list
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredApps) { app ->
                                AppItem(
                                    app = app,
                                    isSelected = selectedApps.contains(app.packageName),
                                    onSelectionChanged = { isSelected ->
                                        val updatedSelectedApps = if (isSelected) {
                                            selectedApps + app.packageName
                                        } else {
                                            selectedApps - app.packageName
                                        }
                                        selectedApps = updatedSelectedApps.toMutableSet()
                                        
                                        // Save immediately - if no apps selected, show in all apps
                                        val newSettings = AppSelectionSettings(
                                            showInAllApps = updatedSelectedApps.isEmpty(),
                                            selectedAppPackages = updatedSelectedApps
                                        )
                                        SecurePrefs.saveAppSelectionSettings(context, newSettings)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppItem(
    app: AppInfo,
    isSelected: Boolean,
    onSelectionChanged: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelectionChanged(!isSelected) },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = onSelectionChanged,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.outline
                )
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Text(
                text = app.appName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
} 