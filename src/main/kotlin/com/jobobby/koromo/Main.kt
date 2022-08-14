package com.jobobby.koromo

import com.willowtreeapps.fuzzywuzzy.ToStringFunction
import com.willowtreeapps.fuzzywuzzy.diffutils.FuzzySearch
import com.willowtreeapps.fuzzywuzzy.diffutils.model.BoundExtractedResult
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.cookies.addCookie
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Cookie
import io.ktor.http.encodeURLPath
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.encodeBase64
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.impl.SimpleLogger
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

suspend fun main(args: Array<String>) {
    val debug = args.contains("debug")
    if (debug) {
        System.setProperty(SimpleLogger.DEFAULT_LOG_LEVEL_KEY, "DEBUG")
    }

    val logger = LoggerFactory.getLogger("Main")
    val apiKey = args.getOrElse(0) {
        logger.error("Missing LANraragi api key")
        exitProcess(201)
    }
    val lanraragiLink = args.getOrElse(1) {
        logger.error("Missing LANraragi link")
        exitProcess(202)
    }.trimEnd('/')
    val sid = args.getOrElse(2) {
        logger.error("Missing FAKKU sid cookie")
        exitProcess(203)
    }
    val amount = args.getOrElse(3) {
        logger.error("Missing amount to find(use 0 for unlimited)")
        exitProcess(204)
    }.toInt()

    val offset = args.getOrNull(4)?.toIntOrNull() ?: 0

    val onlyUntagged = args.any { it.equals("onlyUntagged", true) }

    val cleanSearchTitles = args.any { it.equals("cleanSearchTitles", true) }

    val resetAllTags = args.any { it.equals("resetAllTags", true) }

    val lanraragiClient = HttpClient(OkHttp) {
        expectSuccess = true
        install(DefaultRequest) {
            headers {
                append("Authorization", "Bearer " + apiKey.encodeBase64())
            }
        }
        install(ContentNegotiation) {
            json()
        }
        if (debug) {
            install(Logging) {
                level = LogLevel.INFO
            }
        }
        install(HttpTimeout)
    }

    logger.info("Getting lanraragi metadata plugins")
    val plugins = lanraragiClient.get("$lanraragiLink/api/plugins/metadata")
        .body<List<LanraragiPlugin>>()
    val koromoPlugin = plugins.first { it.name == "koromo" }
    val fakkuPlugin = plugins.first { it.name == "FAKKU" }
    val pandaPlugin = plugins.first { it.name == "Chaika.moe" }

    logger.info("Getting all lanraragi galleries")
    val archives = lanraragiClient.get("$lanraragiLink/api/archives")
        .body<List<LanraragiArchive>>()
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
        .let { archives ->
            if (onlyUntagged) {
                logger.info("Getting untagged lanraragi gallery ids")
                val untaggedArchives = lanraragiClient.get("$lanraragiLink/api/archives/untagged")
                    .body<List<String>>()
                archives.filter { it.arcid in untaggedArchives }
            } else {
                archives
            }
        }
        .let { if (offset > 0) it.drop(offset) else it }
        .let { if (amount > 0) it.take(amount) else it }

    val client = HttpClient(OkHttp) {
        engine {
            addInterceptor(RateLimitInterceptor(4, 1, TimeUnit.SECONDS))
        }
        install(ContentNegotiation) {
            json()
        }
        install(HttpCookies) {
            default {
                addCookie(
                    "https://www.fakku.net/",
                    Cookie(
                        name = "fakku_sid",
                        value = sid,
                        httpOnly = true
                    )
                )
            }
        }
        if (debug) {
            install(Logging) {
                level = LogLevel.INFO
            }
        }
        install(HttpTimeout)
    }

    logger.info("Getting FAKKU page to check login status")
    val fakkuPage = client.get("https://www.fakku.net/subscription") {
        expectSuccess = true
    }.bodyAsText()

    logger.info("Checking FAKKU login")
    if (
        Jsoup.parse(fakkuPage)
            .body()
            .selectFirst(".button-green-600")
            ?.attr("href")
            .equals("/subscription/payment")
            .not()
    ) {
        logger.info("Failed FAKKU login, retry with a new sid cookie")
        exitProcess(205)
    }

    var progress = 1 + offset.coerceAtLeast(0)
    val total = archives.size + offset.coerceAtLeast(0)
    archives
        .asFlow()
        .onEach { delay(500) }
        .map { archive ->
            val response = usePlugin(
                client = lanraragiClient,
                lanraragiLink = lanraragiLink,
                apiKey = apiKey,
                plugin = koromoPlugin,
                archive = archive
            )

            if (response.error == null) {
                logger.info("Found koromo json for '${archive.title}'")
                val oldTags = if (!onlyUntagged) {
                    val dateAdded = archive.tags.split(",").map { it.trim() }
                        .find { it.startsWith("date_added:") }
                    logger.info("Cleaning tags for '${archive.title}'")
                    sendUpdatedMetadata(
                        lanraragiClient,
                        "$lanraragiLink/api/archives/${archive.arcid}/metadata",
                        dateAdded.orEmpty(),
                        null
                    )
                    dateAdded
                } else archive.tags

                logger.info("Using koromo plugin for '${archive.title}'")

                val newResponse = usePlugin(
                    client = lanraragiClient,
                    lanraragiLink = lanraragiLink,
                    apiKey = apiKey,
                    plugin = koromoPlugin,
                    archive = archive
                )
                if (!newResponse.data.new_tags.isNullOrBlank()) {
                    val newTags = (oldTags?.plus(",").orEmpty() + newResponse.data.new_tags).trim()
                    logger.info("Found tags for '${archive.title}' ($newTags)")
                    sendUpdatedMetadata(
                        lanraragiClient,
                        "$lanraragiLink/api/archives/${archive.arcid}/metadata",
                        newTags,
                        newResponse.data.title
                    )

                    val fakkuLink = newResponse.data.new_tags.split(',')
                        .map { it.trim() }
                        .find { it.startsWith("source:") && it.contains("fakku.net", true) }
                    if (fakkuLink != null) {
                        KoromoResult.WithFakku(
                            archive.copy(tags = newTags),
                            fakkuLink.substringAfter(':').trimStart()
                        )
                    } else {
                        KoromoResult.WithoutFakku(
                            archive.copy(tags = newTags)
                        )
                    }
                } else {
                    logger.info("No new tags for '${archive.title}'")
                    KoromoResult.KoromoNoNewTags(archive)
                }
            } else {
                if (resetAllTags) {
                    val dateAdded = archive.tags.split(",").map { it.trim() }
                        .find { it.startsWith("date_added:") }
                    logger.info("Cleaning tags for '${archive.title}'")
                    sendUpdatedMetadata(
                        lanraragiClient,
                        "$lanraragiLink/api/archives/${archive.arcid}/metadata",
                        dateAdded.orEmpty(),
                        null
                    )
                }
                logger.info("No koromo metadata for '${archive.title}'")
                KoromoResult.KoromoFailed(archive)
            }
        }
        .map { koromoResult ->
            when (koromoResult) {
                is KoromoResult.WithFakku -> FakkuResult.AlreadyHaveLink(
                    koromoResult.archive,
                    koromoResult.fakkuLink
                )
                is KoromoResult.WithoutFakku,
                is KoromoResult.KoromoFailed,
                is KoromoResult.KoromoNoNewTags -> {
                    val searchTitle = if (cleanSearchTitles) {
                        koromoResult.archive.title
                            .replace(squareBracketsRegex, "")
                            .replace(circleBracketsRegex, "")
                            .replace(curlyBracketsRegex, "")
                            .trim()
                    } else koromoResult.archive.title
                    logger.info("Searching for '${searchTitle}'")
                    val response = client.get(
                        "https://www.fakku.net/suggest/${searchTitle}".encodeURLPath()
                    ) {
                        headers {
                            append(
                                "User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:101.0) Gecko/20100101 Firefox/101.0"
                            )
                            append(
                                "Accept",
                                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                            )
                            append("Accept-Language", "en-GB,en;q=0.5")
                            append("Accept-Encoding", "gzip, deflate, br")
                            append("X-Requested-With", "XMLHttpRequest")
                            append("Referer", "https://www.fakku.net/")
                            append("DNT", "1")
                            append("Connection", "keep-alive")
                            append("Sec-Fetch-Dest", "empty")
                            append("TE", "trailers")
                            append("Sec-Fetch-Mode", "cors")
                            append("Sec-Fetch-Site", "same-origin")
                        }
                    }
                    FakkuResult.WithSearch(
                        koromoResult.archive,
                        if (response.status.isSuccess()) {
                            FuzzySearch.extractAll(
                                query = searchTitle,
                                choices = response.body<List<FakkuSearch>>()
                                    .filter { it.type == "comic" },
                                toStringFunction = FakkuSearch,
                                cutoff = 95
                            )
                        } else null,
                        searchTitle
                    )
                }
            }
        }
        .onEach { fakkuResult ->
            when (fakkuResult) {
                is FakkuResult.AlreadyHaveLink -> logger.info("FAKKU link found for '${fakkuResult.archive.title}'(${fakkuResult.fakkuLink})")
                is FakkuResult.WithSearch -> if (!fakkuResult.results.isNullOrEmpty()) {
                    logger.info("FAKKU search results found for '${fakkuResult.searchTitle}'")
                    fakkuResult.results.forEach {
                        logger.info(it.toString())
                    }
                } else {
                    logger.info("No results on FAKKU for '${fakkuResult.archive.title}'")
                }
            }
        }
        .map { fakkuResult ->
            when (fakkuResult) {
                is FakkuResult.AlreadyHaveLink -> {
                    if (client.get(fakkuResult.fakkuLink).status.isSuccess()) {
                        logger.info("${fakkuResult.fakkuLink} verified")
                        PandaResult.Fakku(fakkuResult.archive, fakkuResult.fakkuLink)
                    } else {
                        logger.info("FAKKU page for '${fakkuResult.archive.title}' is unavailable")
                        val pandaLink = searchPandaForLink(client, fakkuResult.fakkuLink)
                            ?: getPandaLink(logger, client, fakkuResult.archive.title)
                        if (pandaLink != null) {
                            PandaResult.Panda(fakkuResult.archive, pandaLink)
                        } else {
                            PandaResult.None(fakkuResult.archive)
                        }
                    }
                }
                is FakkuResult.WithSearch -> {
                    val pandaResult = chooseFuzzyResult(
                        logger,
                        fakkuResult.results,
                        fakkuResult.searchTitle,
                        { FakkuSearch.apply(this) },
                        { "https://www.fakku.net$link" }
                    )
                    if (pandaResult != null) {
                        PandaResult.Fakku(fakkuResult.archive, pandaResult)
                    } else {
                        val pandaLink = getPandaLink(logger, client, fakkuResult.searchTitle)

                        if (pandaLink != null) {
                            val fakkuLink = Jsoup.parse(client.get(pandaLink).bodyAsText())
                                .body()
                                .selectFirst(".line-top  tbody a")
                                ?.attr("href")
                                ?.takeIf { it.contains("fakku", true) }
                            val result = if (fakkuLink != null) {
                                client.get(fakkuLink).takeIf { it.status.isSuccess() }
                                    ?.let { PandaResult.Fakku(fakkuResult.archive, fakkuLink) }
                            } else null
                            result ?: PandaResult.Panda(fakkuResult.archive, pandaLink)
                        } else PandaResult.None(fakkuResult.archive)
                    }
                }
            }
        }
        .onEach {
            when (it) {
                is PandaResult.Fakku -> logger.info("Found FAKKU link for '${it.archive.title}'")
                is PandaResult.Panda -> logger.info("Found chaika.moe link for '${it.archive.title}'")
                is PandaResult.None -> logger.info("No link found for '${it.archive.title}'")
            }
            logger.debug(it.toString())
        }
        .collect { pandaResult ->
            if (pandaResult is PandaResult.Success) {
                val response = usePlugin(lanraragiClient, lanraragiLink, apiKey,
                    when (pandaResult) {
                        is PandaResult.Fakku -> fakkuPlugin
                        is PandaResult.Panda -> pandaPlugin
                    },
                    pandaResult.archive,
                    pandaResult.link
                )

                if (!response.data.new_tags.isNullOrBlank()) {
                    val newTags = (pandaResult.archive.tags + "," + response.data.new_tags).trim()
                    logger.info("Found new tags for '${pandaResult.archive.title}' (${response.data.new_tags.trimStart()})")
                    sendUpdatedMetadata(
                        lanraragiClient,
                        "$lanraragiLink/api/archives/${pandaResult.archive.arcid}/metadata",
                        newTags,
                        response.data.title
                    )
                    logger.info("Finished metadata process for '${pandaResult.archive.title}'")
                } else {
                    logger.info("No new tags for '${pandaResult.archive.title}'")
                }
            } else {
                logger.info("Failed to get online tags for '${pandaResult.archive.title}'")
            }
            logger.info("Finished ${progress++}/${total}")
        }
}

private suspend fun sendUpdatedMetadata(
    client: HttpClient,
    url: String,
    newTags: String,
    newTitle: String?
) {
    client.put(url) {
        url {
            parameters.append("tags", newTags)
            if (newTitle != null) {
                parameters.append("title", newTitle)
            }
        }
    }
}

private suspend fun usePlugin(
    client: HttpClient,
    lanraragiLink: String,
    apiKey: String,
    plugin: LanraragiPlugin,
    archive: LanraragiArchive,
    arg: String? = null
): LanraragiPluginResponse {
    return client.post("$lanraragiLink/api/plugins/use") {
        url {
            parameters.apply {
                append("key", apiKey)
                append("plugin", plugin.namespace)
                append("id", archive.arcid)
                if (arg != null) {
                    append("arg", arg)
                }
            }
        }
    }.body()
}

sealed class KoromoResult {
    abstract val archive: LanraragiArchive

    data class WithFakku(
        override val archive: LanraragiArchive,
        val fakkuLink: String
    ) : KoromoResult()

    data class WithoutFakku(
        override val archive: LanraragiArchive,
    ) : KoromoResult()

    data class KoromoNoNewTags(
        override val archive: LanraragiArchive
    ) : KoromoResult()

    data class KoromoFailed(
        override val archive: LanraragiArchive
    ) : KoromoResult()
}

sealed class FakkuResult {
    abstract val archive: LanraragiArchive

    data class AlreadyHaveLink(
        override val archive: LanraragiArchive,
        val fakkuLink: String
    ) : FakkuResult()

    data class WithSearch(
        override val archive: LanraragiArchive,
        val results: List<BoundExtractedResult<FakkuSearch>>?,
        val searchTitle: String
    ) : FakkuResult()
}

sealed class PandaResult {
    abstract val archive: LanraragiArchive

    sealed class Success : PandaResult() {
        abstract val link: String
    }

    data class Fakku(
        override val archive: LanraragiArchive,
        override val link: String
    ) : Success()

    data class Panda(
        override val archive: LanraragiArchive,
        override val link: String
    ) : Success()

    data class None(
        override val archive: LanraragiArchive
    ) : PandaResult()
}

object ElementsToString : ToStringFunction<Element> {
    override fun apply(item: Element): String {
        return item.text()
    }
}

private suspend fun searchPanda(
    client: HttpClient,
    search: String
): Elements {
    return Jsoup.parse(
        client.get("https://panda.chaika.moe/search/?qsearch=${search}".encodeURLPath())
            .bodyAsText()
    )
        .body()
        .select(".result-list a")
}

private suspend fun searchPandaForLink(
    client: HttpClient,
    link: String
): String? {
    val searchResults = searchPanda(client, link)
    return if (searchResults.size == 1) {
        "https://panda.chaika.moe" + searchResults.first()!!.attr("href")
    } else {
        null
    }
}

private suspend fun getPandaLink(
    logger: Logger,
    client: HttpClient,
    title: String,
): String? {
    val searchResults = Jsoup.parse(
        client.get("https://panda.chaika.moe/search/?qsearch=${title}".encodeURLPath())
            .bodyAsText()
    )
        .body()
        .select(".result-list a")

    logger.debug(searchResults.toString())

    val fuzzyResults = FuzzySearch.extractAll(
        title,
        searchResults,
        ElementsToString,
        95
    )

    logger.debug(fuzzyResults.toString())

    return chooseFuzzyResult(
        logger = logger,
        results = fuzzyResults,
        title = title,
        toText = { text() },
        toLink = { "https://panda.chaika.moe" + attr("href") }
    )
}

private fun <T> chooseFuzzyResult(
    logger: Logger,
    results: List<BoundExtractedResult<T>>?,
    title: String,
    toText: T.() -> String,
    toLink: T.() -> String
): String? {
    return when {
        !results.isNullOrEmpty() && results.none { it.score == 100 } -> {
            logger.info(
                "\n\nFound multiple results for '${title}', select the correct result, or 0 if none." +
                    results.mapIndexed { index, boundExtractedResult ->
                        "${index + 1}: ${boundExtractedResult.referent.toText()} (${boundExtractedResult.referent.toLink()})"
                    }.joinToString(separator = "\n", prefix = "\n")
            )
            var result: Int?
            do {
                result = readlnOrNull()?.trim()?.toIntOrNull()?.minus(1)
                if (resultNotValid(result, results)) {
                    logger.info("Retry")
                }
            } while (result == null || resultNotValid(result, results))
            if (result != -1) {
                results[result].referent.toLink()
            } else null
        }
        !results.isNullOrEmpty() -> results.maxBy { it.score }.referent.toLink()
        else -> null
    }
}

fun resultNotValid(result: Int?, results: List<*>) = result == null || (result != -1 && result !in 0..results.lastIndex)

val squareBracketsRegex = "\\[.*?]".toRegex()

val circleBracketsRegex = "\\(.*?\\)".toRegex()

val curlyBracketsRegex = "\\{.*?}".toRegex()