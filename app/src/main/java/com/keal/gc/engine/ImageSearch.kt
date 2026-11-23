package com.keal.gc.engine

import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

data class ImageCandidate(
    val url: String,
    val source: String,
    val title: String,
    val confidence: String
)

/** Calls GC's hosted DDGS service. The user never enters an API key or server URL. */
class ImageSearch {
    companion object {
        private const val BASE_URL = "https://gc-keal-ddgs-api.onrender.com"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(40, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun search(ean: String, article: String, name: String, brand: String): List<ImageCandidate> {
        require(name.isNotBlank() || ean.isNotBlank() || article.isNotBlank()) {
            "No product name or identifier available for image search."
        }
        val uri = Uri.parse("$BASE_URL/api/search-images").buildUpon()
            .appendQueryParameter("ean", ean)
            .appendQueryParameter("article", article)
            .appendQueryParameter("name", name)
            .appendQueryParameter("brand", brand)
            .build()

        val request = Request.Builder()
            .url(uri.toString())
            .header("Accept", "application/json")
            .header("User-Agent", "GC-Product-Photo-Finder/1.0")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull().orEmpty()
                throw IOException(detail.ifBlank { "GC image service returned HTTP " + response.code })
            }
            val json = JSONObject(body)
            val array = json.optJSONArray("results") ?: return emptyList()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val image = item.optString("image").ifBlank { item.optString("thumbnail") }
                if (!image.startsWith("https://")) return@mapNotNull null
                ImageCandidate(
                    url = image,
                    source = item.optString("url").ifBlank { item.optString("source") },
                    title = item.optString("title"),
                    confidence = "DDGS PRODUCT MATCH"
                )
            }.distinctBy { it.url }.take(5)
        }
    }
}
