package com.keal.gc.engine

import android.content.Context
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

/** Android client for the self-hosted DDGS service. No search API key is used. */
class ImageSearch(context: Context) {
    private val preferences = context.getSharedPreferences("gc_settings", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(35, TimeUnit.SECONDS)
        .readTimeout(75, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun search(ean: String, article: String, name: String, brand: String): List<ImageCandidate> {
        val baseUrl = preferences.getString("ddgs_api_url", "https://gc-ddgs-api.onrender.com")
            ?.trim()?.trimEnd('/').orEmpty()
        require(baseUrl.startsWith("https://")) {
            "Set the HTTPS DDGS server URL in API settings before searching."
        }

        val uri = Uri.parse("$baseUrl/api/search-images").buildUpon()
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
                val detail = runCatching {
                    JSONObject(body).optString("detail")
                }.getOrNull().orEmpty()
                throw IOException(
                    detail.ifBlank {
                        "DDGS server returned HTTP \${response.code}. Check that the image service is deployed."
                    }
                )
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
