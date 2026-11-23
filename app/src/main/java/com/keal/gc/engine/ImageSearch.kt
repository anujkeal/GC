package com.keal.gc.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ImageCandidate(val url: String, val source: String, val title: String, val confidence: String)

/** Direct DuckDuckGo Images lookup. No API key or separate user-managed server. */
class ImageSearch {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val stopWords = setOf("the", "and", "for", "with", "toy", "toys", "product", "kids", "boys", "girls", "age", "years", "new", "assorted", "remote", "control", "car", "vehicle", "color", "colour", "red", "blue", "green", "grey", "gray", "black", "white", "pink", "ghz", "mhz")

    private fun normalize(value: String): String {
        var text = value.lowercase(Locale.ROOT)
        val expansions = listOf(
            Regex("\\br\\s*/?\\s*c\\b") to "remote control",
            Regex("\\brc\\b") to "remote control",
            Regex("\\btrck\\b") to "truck",
            Regex("\\bmnstr\\b") to "monster",
            Regex("\\bhighspd\\b") to "high speed",
            Regex("\\bamphi\\b") to "amphibious",
            Regex("\\bclimbin\\b") to "climbing",
            Regex("\\bwtch\\b") to "with",
            Regex("\\brmt\\b") to "remote",
            Regex("\\bgry\\b") to "grey",
            Regex("\\bblmg\\b") to "bubble",
            Regex("\\bicecream\\b") to "ice cream",
            Regex("\\b2\\.4\\s*g\\b") to "2.4 ghz",
            Regex("\\b2\\.4ghz\\b") to "2.4 ghz"
        )
        expansions.forEach { (pattern, replacement) -> text = pattern.replace(text, replacement) }
        return text.replace(Regex("[^a-z0-9]+"), " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun tokens(value: String): Set<String> =
        normalize(value).split(" ").filter { it.length >= 3 && it !in stopWords }.toSet()

    private fun score(productName: String, brand: String, title: String, pageUrl: String): Int {
        val wanted = tokens("$brand $productName")
        val found = tokens("$title $pageUrl")
        if (wanted.isEmpty() || found.isEmpty()) return 0
        val overlap = wanted.intersect(found)
        val distinctive = overlap.count { it.length >= 4 || it.any(Char::isDigit) }
        val ratio = overlap.size.toDouble() / wanted.size
        return when {
            distinctive >= 1 && ratio >= 0.70 -> 100
            distinctive >= 2 && ratio >= 0.50 -> 85
            distinctive >= 3 && ratio >= 0.35 -> 70
            else -> 0
        }
    }

    private fun get(url: String, referer: String? = null): String {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
            .header("Accept", "text/html,application/xhtml+xml,application/json")
            .header("Accept-Language", "en-IN,en;q=0.9")
        if (referer != null) builder.header("Referer", referer)
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) error("DuckDuckGo returned HTTP " + response.code)
            return response.body?.string().orEmpty()
        }
    }

    private fun vqdToken(html: String): String {
        val patterns = listOf(Regex("""vqd=['"]([^'"]+)['"]"""), Regex("""vqd=([\d-]+)"""))
        return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.getOrNull(1) }
            ?: error("DuckDuckGo did not provide an image-search token. Try again later.")
    }

    private fun searchOne(query: String, productName: String, brand: String, results: MutableMap<String, Pair<Int, ImageCandidate>>) {
        val encoded = URLEncoder.encode(query, Charsets.UTF_8.name())
        val landing = "https://duckduckgo.com/?q=" + encoded + "&iax=images&ia=images"
        val token = vqdToken(get(landing))
        val imageUrl = "https://duckduckgo.com/i.js?q=" + encoded + "&o=json&l=in-en&vqd=" +
            URLEncoder.encode(token, "UTF-8") + "&f=,,,,,"
        val json = JSONObject(get(imageUrl, landing))
        val array = json.optJSONArray("results") ?: JSONArray()

        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val image = item.optString("image").ifBlank { item.optString("thumbnail") }
            val title = item.optString("title")
            val page = item.optString("url")
            if (!image.startsWith("https://")) continue
            val relevance = score(productName, brand, title, page)
            if (relevance < 70) continue
            val candidate = ImageCandidate(image, page, title, "PRODUCT TITLE MATCH")
            val previous = results[image]
            if (previous == null || relevance > previous.first) results[image] = relevance to candidate
        }
    }

    fun search(ean: String, article: String, name: String, brand: String): List<ImageCandidate> {
        require(name.isNotBlank() || ean.isNotBlank() || article.isNotBlank()) {
            "No product name or identifier available for image search."
        }
        val cleanName = normalize(name)
        val core = tokens(cleanName).filterNot {
            it.matches(Regex("[a-z]*\\d+[a-z0-9]*")) ||
                it in setOf("grey", "gray", "red", "blue", "green", "yellow", "black", "white", "pink")
        }
        val queries = linkedSetOf<String>()
        if (cleanName.isNotBlank()) queries.add("\"" + cleanName + "\" toy product")
        if (brand.isNotBlank() && cleanName.isNotBlank()) queries.add("\"" + brand + " " + cleanName + "\"")
        if (core.isNotEmpty()) queries.add(core.joinToString(" "))
        if (ean.isNotBlank()) queries.add("\"" + ean.trim() + "\"")
        if (article.isNotBlank()) queries.add("\"" + article.trim() + "\" toy")

        val matches = linkedMapOf<String, Pair<Int, ImageCandidate>>()
        var lastError: Exception? = null
        for (query in queries) {
            try {
                searchOne(query, name, brand, matches)
            } catch (error: Exception) {
                lastError = error
            }
            if (matches.size >= 5) break
        }
        val ordered = matches.values.sortedByDescending { it.first }
            .map { it.second }.distinctBy { it.url }.take(5)
        if (ordered.isEmpty() && lastError != null) {
            throw IllegalStateException(lastError.message ?: "DuckDuckGo image search failed.", lastError)
        }
        return ordered
    }
}
