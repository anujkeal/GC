package com.keal.gc.engine

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ImageCandidate(val url:String,val source:String,val title:String,val confidence:String)

class ImageSearch(private val apiKey:String) {
    private val client = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()
    fun search(ean:String, name:String, brand:String):List<ImageCandidate> {
        require(apiKey.isNotBlank()) { "Add your Serper API key in API settings." }
        val productName = listOf(brand, name).filter { it.isNotBlank() }.joinToString(" ")
        val query = if (ean.isNotBlank()) "\""+ean.trim()+"\" "+productName else productName
        require(query.isNotBlank()) { "Product has no EAN or name to search." }
        val body = JSONObject().put("q", query.trim()).put("num", 5).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("https://google.serper.dev/images")
            .addHeader("X-API-KEY", apiKey).addHeader("Content-Type", "application/json")
            .post(body).build()
        client.newCall(request).execute().use { response ->
            val responseText = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching { JSONObject(responseText).optString("message") }.getOrNull()
                error(detail?.takeIf { it.isNotBlank() } ?: ("Google Images request failed (HTTP " + response.code + "). Check API key/quota."))
            }
            val images = JSONObject(responseText).optJSONArray("images") ?: return emptyList()
            return (0 until images.length()).mapNotNull { i ->
                val item = images.optJSONObject(i) ?: return@mapNotNull null
                val imageUrl = item.optString("imageUrl").ifBlank { item.optString("thumbnailUrl") }
                if (!imageUrl.startsWith("https://")) return@mapNotNull null
                val title = item.optString("title").ifBlank { name }
                val source = item.optString("link").ifBlank { item.optString("source") }
                val confidence = when {
                    ean.isNotBlank() && (title + " " + source).contains(ean, true) -> "EAN MATCH"
                    name.isNotBlank() && title.contains(name, true) -> "NAME MATCH"
                    else -> "CHECK"
                }
                ImageCandidate(imageUrl, source, title, confidence)
            }.distinctBy { it.url }.take(5)
        }
    }
}
