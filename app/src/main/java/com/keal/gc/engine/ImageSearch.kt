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
    val confidence: String,
    val score: Int = 0,
    val needsReview: Boolean = true
)

/** Calls GC's hosted DDGS service. No API key or server URL is entered in the app. */
class ImageSearch {
    companion object {
        private const val BASE_URL = "https://gc-keal-ddgs-api.onrender.com"
        private val RETRYABLE_CODES = setOf(408, 429, 500, 502, 503, 504)
    }

    private class SearchHttpException(message: String, val retryable: Boolean) : IOException(message)

    private val client = OkHttpClient.Builder()
        .connectTimeout(40, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
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

        var lastError: IOException? = null
        for (attempt in 0..2) {
            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        val detail = runCatching { JSONObject(body).optString("detail") }
                            .getOrNull().orEmpty()
                        throw SearchHttpException(
                            detail.ifBlank { "GC image service returned HTTP " + response.code },
                            response.code in RETRYABLE_CODES
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
                            confidence = if (item.optBoolean("needs_review", true)) "NEEDS STAFF REVIEW" else "VISION VERIFIED",
                            score = item.optInt("score", 0),
                            needsReview = item.optBoolean("needs_review", true)
                        )
                    }.distinctBy { it.url }.take(5)
                }
            } catch (error: SearchHttpException) {
                if (!error.retryable) throw error
                lastError = error
            } catch (error: IOException) {
                lastError = error
            }

            if (attempt < 2) {
                try {
                    Thread.sleep(1000L * (1 shl attempt))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Image search retry was interrupted.", interrupted)
                }
            }
        }
        throw lastError ?: IOException("GC image search service is temporarily unavailable.")
    }
}
