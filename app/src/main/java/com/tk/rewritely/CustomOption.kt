package com.tk.rewritely

import kotlinx.serialization.Serializable

@Serializable
data class CustomOption(
    val id: String,
    val name: String,
    val prompt: String,
    val isDefault: Boolean = false,
    val isChatGpt: Boolean = false
) 