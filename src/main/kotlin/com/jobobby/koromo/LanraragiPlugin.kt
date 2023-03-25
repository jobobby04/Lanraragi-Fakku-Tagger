package com.jobobby.koromo

import kotlinx.serialization.Serializable

@Serializable
data class LanraragiPlugin(
    val author: String,
    val cooldown: Int? = null,
    val description: String,
    val icon: String? = null,
    val login_from: String? = null,
    val name: String,
    val namespace: String,
    val oneshot_arg: String? = null,
    val parameters: List<Parameter>,
    val type: String,
    val version: String
)