package com.keal.gc.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

data class ImageCandidate(
    val url: String,
    val source: String,
    val title: String,
    val confidence: String
)

/** Keyless public Bing Images search. EAN and product name are included in the query. */
class ImageSearch {
    private val client = OkHttpClient.Builder()
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun search(ean: String, name: String, brand: String): List<ImageCandidate> {
        val cleanName = listOf(brand, name).map { it.trim() }
            .filter { it.isNotBlank() }.joinToString(" ")
        require(cleanName.isNotBlank() || ean.isNotBlank()) {
            "This product has no EAN or product name to search."
        }

        val queries = buildList {
            if (ean.isNotBlank()) add("\"" + ean.trim() + "\" " + cleanName)
            if (cleanName.isNotBlank()) add("\"" + cleanName + "\" toy product")
        }.distinct()

        val results = linkedMapOf<String, ImageCandidate>()
        for (query in queries) {
            if (results.size >= 5) break
            val requestUrl = "https://www.bing.com/images/search?q=" +
                URLEncoder.encode(query, Charsets.UTF_8.name()) + "&form=HDRSC3&cc=IN&setlang=en"
            val request = Request.Builder().url(requestUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-IN,en;q=0.9")
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Image search returned HTTP " + response.code + ". Please retry later.")
                val html = response.body?.string().orEmpty()
                require(html.isNotBlank()) { "Image search returned an empty page." }
                val cards = Jsoup.parse(html).select("a.iusc")
                for (card in cards) {
                    if (results.size >= 5) break
                    val metadata = runCatching { JSONObject(card.attr("m")) }.getOrNull() ?: continue
                    val thumbnail = metadata.optString("turl")
                    val original = metadata.optString("murl")
                    val imageUrl = when {
                        thumbnail.startsWith("https://") -> thumbnail
                        original.startsWith("https://") -> original
                        else -> continue
                    }
                    val title = metadata.optString("t")
                        .ifBlank { card.selectFirst("img")?.attr("alt").orEmpty() }
                        .ifBlank { name }
                    val source = metadata.optString("purl")
                    val confidence = if (ean.isNotBlank() && title.contains(ean, true)) "EAN MATCH" else "SEARCH MATCH"
                    results.putIfAbsent(imageUrl, ImageCandidate(imageUrl, source, title, confidence))
                }
            }
        }
        return results.values.take(5)
    }
}
