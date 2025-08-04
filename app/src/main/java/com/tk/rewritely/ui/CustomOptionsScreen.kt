package com.tk.rewritely.ui

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tk.rewritely.CustomOption
import com.tk.rewritely.R
import com.tk.rewritely.SecurePrefs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomOptionsScreen(onBackPressed: () -> Unit) {
    val context = LocalContext.current
    var customOptions by remember { mutableStateOf(SecurePrefs.getCustomOptions(context)) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<CustomOption?>(null) }
    var newOptionName by remember { mutableStateOf("") }
    var newOptionPrompt by remember { mutableStateOf("") }
    var hasApiKey by remember { mutableStateOf(false) }
    
    // Confirmation dialog states
    var showDeleteConfirmation by remember { mutableStateOf<CustomOption?>(null) }
    var showResetDefaultConfirmation by remember { mutableStateOf(false) }
    var showResetChatGptConfirmation by remember { mutableStateOf(false) }

    // Check if API key is set
    LaunchedEffect(Unit) {
        hasApiKey = !SecurePrefs.getApiKey(context).isNullOrBlank()
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
                        text = stringResource(R.string.custom_options_title),
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
        },
        floatingActionButton = {
            // Only show FAB if API key is set and we haven't reached the limit of 4 custom options
            val customOptionsCount = customOptions.count { !it.isDefault && !it.isChatGpt }
            if (hasApiKey && customOptionsCount < 4) {
                FloatingActionButton(
                    onClick = { showAddDialog = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 32.dp, end = 20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Add Option"
                    )
                }
            }
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 20.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Default card (only shown when API key is set)
            if (hasApiKey) {
                item {
                    val defaultOption = customOptions.find { it.isDefault }
                    DefaultOptionCard(
                        prompt = defaultOption?.prompt ?: SecurePrefs.DEFAULT_PROMPT,
                        onEdit = { 
                            if (defaultOption != null) {
                                showEditDialog = defaultOption
                            }
                        },
                        onReset = {
                            showResetDefaultConfirmation = true
                        }
                    )
                }
            }
            
            // ChatGPT card (always shown)
            item {
                val chatGptOption = customOptions.find { it.isChatGpt }
                ChatGptOptionCard(
                    prompt = chatGptOption?.prompt ?: SecurePrefs.DEFAULT_PROMPT,
                    onEdit = { 
                        if (chatGptOption != null) {
                            showEditDialog = chatGptOption
                        }
                    },
                    onReset = {
                        showResetChatGptConfirmation = true
                    }
                )
            }

            // Show API key required message if API key is not set
            if (!hasApiKey) {
                item {
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
                                text = stringResource(R.string.api_key_required_message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            // Custom options list
            items(customOptions.filter { !it.isDefault && !it.isChatGpt }) { option ->
                CustomOptionItem(
                    option = option,
                    onEdit = { showEditDialog = option },
                    onDelete = {
                        showDeleteConfirmation = option
                    }
                )
            }
        }
    }

    // Add Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text(stringResource(R.string.add_new_option)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newOptionName,
                        onValueChange = { newOptionName = it },
                        label = { Text(stringResource(R.string.option_name)) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newOptionPrompt,
                        onValueChange = { newOptionPrompt = it },
                        label = { Text(stringResource(R.string.option_prompt)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newOptionName.isNotBlank() && newOptionPrompt.isNotBlank()) {
                            val newOption = SecurePrefs.addCustomOption(context, newOptionName, newOptionPrompt)
                            if (newOption != null) {
                                customOptions = SecurePrefs.getCustomOptions(context)
                                newOptionName = ""
                                newOptionPrompt = ""
                                showAddDialog = false
                            } else {
                                Toast.makeText(context, "Maximum 4 custom options allowed", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.add))
                }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Edit Dialog
    showEditDialog?.let { option ->
        var editName by remember { mutableStateOf(option.name) }
        var editPrompt by remember { mutableStateOf(option.prompt) }

        AlertDialog(
            onDismissRequest = { showEditDialog = null },
            title = { Text(stringResource(R.string.edit_option)) },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = editName,
                        onValueChange = { editName = it },
                        label = { Text(stringResource(R.string.option_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !option.isDefault && !option.isChatGpt
                    )
                    OutlinedTextField(
                        value = editPrompt,
                        onValueChange = { editPrompt = it },
                        label = { Text(stringResource(R.string.option_prompt)) },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (editName.isNotBlank() && editPrompt.isNotBlank()) {
                            SecurePrefs.updateCustomOption(context, option.id, editName, editPrompt)
                            customOptions = SecurePrefs.getCustomOptions(context)
                            showEditDialog = null
                        }
                    }
                ) {
                    Text(stringResource(R.string.save))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEditDialog = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Delete Confirmation Dialog
    showDeleteConfirmation?.let { option ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = null },
            title = { Text(stringResource(R.string.delete_option)) },
            text = { Text(stringResource(R.string.delete_confirmation_message, option.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        SecurePrefs.deleteCustomOption(context, option.id)
                        customOptions = SecurePrefs.getCustomOptions(context)
                        showDeleteConfirmation = null
                    }
                ) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Reset Default Confirmation Dialog
    if (showResetDefaultConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetDefaultConfirmation = false },
            title = { Text(stringResource(R.string.reset_default_option)) },
            text = { Text(stringResource(R.string.reset_default_confirmation_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        SecurePrefs.resetDefaultOption(context)
                        customOptions = SecurePrefs.getCustomOptions(context)
                        Toast.makeText(context, "Default option reset to default prompt.", Toast.LENGTH_SHORT).show()
                        showResetDefaultConfirmation = false
                    }
                ) {
                    Text(stringResource(R.string.reset_to_default))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDefaultConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Reset ChatGPT Confirmation Dialog
    if (showResetChatGptConfirmation) {
        AlertDialog(
            onDismissRequest = { showResetChatGptConfirmation = false },
            title = { Text(stringResource(R.string.reset_chatgpt_option)) },
            text = { Text(stringResource(R.string.reset_chatgpt_confirmation_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        SecurePrefs.resetChatGptOption(context)
                        customOptions = SecurePrefs.getCustomOptions(context)
                        Toast.makeText(context, "ChatGPT option reset to default prompt.", Toast.LENGTH_SHORT).show()
                        showResetChatGptConfirmation = false
                    }
                ) {
                    Text(stringResource(R.string.reset_to_default))
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetChatGptConfirmation = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
fun DefaultOptionCard(prompt: String, onEdit: () -> Unit, onReset: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Default",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Prompt: $prompt",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // Only show reset button if prompt is different from default
            if (prompt != SecurePrefs.DEFAULT_PROMPT) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.reset_to_default))
                    }
                }
            }
        }
    }
}

@Composable
fun ChatGptOptionCard(prompt: String, onEdit: () -> Unit, onReset: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "ChatGPT",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Prompt: $prompt",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            // Only show reset button if prompt is different from default
            if (prompt != SecurePrefs.DEFAULT_PROMPT) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onReset) {
                        Text(stringResource(R.string.reset_to_default))
                    }
                }
            }
        }
    }
}

@Composable
fun CustomOptionItem(
    option: CustomOption,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onEdit() },
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = option.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = "Prompt: ${option.prompt}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.delete))
                }
            }
        }
    }
} 