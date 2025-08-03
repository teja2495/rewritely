package com.tk.rewritely

import kotlinx.serialization.Serializable

@Serializable
data class AppSelectionSettings(
    val showInAllApps: Boolean = false,
    val selectedAppPackages: Set<String> = emptySet()
) 