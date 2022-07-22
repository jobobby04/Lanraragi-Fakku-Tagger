package com.jobobby.koromo

import kotlinx.serialization.Serializable

@Serializable
data class LanraragiPluginResponse(
    val `data`: Data,
    val error: String?,
    val operation: String,
    val success: Int,
    val type: String
)