package ca.gosyer.link

import kotlinx.serialization.Serializable

@Serializable
data class Parameter(
    val default_value: String? = null,
    val desc: String,
    val type: String
)