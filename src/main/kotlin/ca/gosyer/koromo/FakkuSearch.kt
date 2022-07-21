package ca.gosyer.koromo

import com.willowtreeapps.fuzzywuzzy.ToStringFunction
import kotlinx.serialization.Serializable
import org.jsoup.parser.Parser

@Serializable
data class FakkuSearch(
    val image: String,
    val link: String,
    val title: String,
    val type: String
) {
    companion object : ToStringFunction<FakkuSearch> {
        override fun apply(item: FakkuSearch): String {
            return Parser.unescapeEntities(item.title, true)
        }
    }
}