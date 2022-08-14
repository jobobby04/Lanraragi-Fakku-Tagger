package com.jobobby.koromo

import kotlinx.serialization.Serializable

@Serializable
data class Data(
    val title: String? = null,
    val new_tags: String? = null,
    val error: String? = null
)