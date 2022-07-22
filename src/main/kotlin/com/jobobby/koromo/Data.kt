package com.jobobby.koromo

import kotlinx.serialization.Serializable

@Serializable
data class Data(
    val new_tags: String? = null,
    val error: String? = null
)