package ca.gosyer.koromo

import com.willowtreeapps.fuzzywuzzy.ToStringFunction
import kotlinx.serialization.Serializable

@Serializable
data class FakkuSearch(
    val image: String,
    val link: String,
    val title: String,
    val type: String
) {
    companion object : ToStringFunction<FakkuSearch> {
        override fun apply(item: FakkuSearch): String {
            return item.title
        }
    }
}