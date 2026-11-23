package com.keal.gc.engine
import com.keal.gc.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
data class ImageCandidate(val url:String,val source:String,val title:String,val confidence:String)
class ImageSearch {
 private val client=OkHttpClient.Builder().callTimeout(20,TimeUnit.SECONDS).build()
 fun search(ean:String,name:String,brand:String):List<ImageCandidate>{
  require(BuildConfig.GOOGLE_CSE_KEY.isNotBlank()&&BuildConfig.GOOGLE_CSE_ID.isNotBlank()){"Configure GOOGLE_CSE_KEY and GOOGLE_CSE_ID in local.properties"}
  val query=listOf(ean.takeIf{it.isNotBlank()},listOf(brand,name).filter{it.isNotBlank()}.joinToString(" ")).filterNotNull().joinToString(" ")
  val url="https://www.googleapis.com/customsearch/v1?key=${BuildConfig.GOOGLE_CSE_KEY}&cx=${BuildConfig.GOOGLE_CSE_ID}&searchType=image&num=5&q="+URLEncoder.encode(query,"UTF-8")
  client.newCall(Request.Builder().url(url).build()).execute().use{res->
   if(!res.isSuccessful)error("Image search failed: HTTP ${res.code}")
   val items=JSONObject(res.body?.string().orEmpty()).optJSONArray("items")?:return emptyList()
   return (0 until items.length()).mapNotNull{idx->val item=items.optJSONObject(idx)?:return@mapNotNull null;val link=item.optString("link");if(!link.startsWith("https://"))return@mapNotNull null;val title=item.optString("title");val src=item.optJSONObject("image")?.optString("contextLink").orEmpty();val conf=when{ean.isNotBlank()&&(title+" "+src).contains(ean,true)->"HIGH";name.isNotBlank()&&title.contains(name,true)->"MEDIUM";else->"LOW"};ImageCandidate(link,src,title,conf)}.distinctBy{it.url}.take(5)
  }
 }
}
