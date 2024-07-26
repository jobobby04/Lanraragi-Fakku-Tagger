package com.jobobby.koromo

import com.willowtreeapps.fuzzywuzzy.ToStringFunction
import kotlinx.serialization.Serializable
import org.jsoup.parser.Parser

@Serializable
data class FakkuSearch(
    val results: List<FakkuSearchItem>,
    val total: Int
)

@Serializable
data class FakkuSearchItem(
    val image: String,
    val link: String,
    val title: String,
    val type: String
) {
    companion object : ToStringFunction<FakkuSearchItem> {
        override fun apply(item: FakkuSearchItem): String {
            return Parser.unescapeEntities(item.title, true)
        }
    }
}