package com.jobobby.koromo

import kotlinx.serialization.Serializable

@Serializable
data class LanraragiArchive(
    val arcid: String,
    val extension: String,
    val isnew: String,
    val pagecount: Int,
    val progress: Int,
    val filename: String,
    val tags: String,
    val title: String,
    val summary: String,
)