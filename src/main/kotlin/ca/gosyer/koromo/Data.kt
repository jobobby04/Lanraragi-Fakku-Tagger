package ca.gosyer.link

import kotlinx.serialization.Serializable

@Serializable
data class Data(
    val new_tags: String? = null,
    val error: String? = null
)