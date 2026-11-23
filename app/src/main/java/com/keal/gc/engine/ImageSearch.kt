package com.keal.gc.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ImageCandidate(val url:String,val source:String,val title:String,val confidence:String)

class ImageSearch {
 private val client=OkHttpClient.Builder().callTimeout(25,TimeUnit.SECONDS).followRedirects(true).build()
 private val domains=listOf("hamleys.in","hamleys.com","firstcry.com","toysrus.in","amazon.in","landmarkshops.in")
 private val stop=setOf("the","and","for","with","toy","toys","product","kids","boys","girls","age","years","new","assorted","multicolour","multicolor")
 private fun expand(raw:String):String {
  var s=raw.lowercase(Locale.ROOT)
  val map=listOf(Regex("\\br\\s*/?\\s*c\\b") to "remote control",Regex("\\brc\\b") to "remote control",Regex("\\btrck\\b") to "truck",Regex("\\bmnstr\\b") to "monster",Regex("\\bhighspd\\b") to "high speed",Regex("\\bamphi\\b") to "amphibious",Regex("\\bclimbin\\b") to "climbing",Regex("\\bwtch\\b") to "watch",Regex("\\brmt\\b") to "remote",Regex("\\bgry\\b") to "grey",Regex("\\bblmg\\b") to "bubble",Regex("\\bicecream\\b") to "ice cream")
  map.forEach{(p,v)->s=p.replace(s,v)}
  return s.replace(Regex("[^a-z0-9]+")," ").replace(Regex("\\s+")," ").trim()
 }
 private fun tokens(s:String)=expand(s).split(" ").filter{it.length>=2&&it !in stop}.toSet()
 private fun score(product:String,brand:String,title:String):Int {
  val wanted=tokens("$brand $product"); val found=tokens(title)
  if(wanted.isEmpty()||found.isEmpty()) return 0
  val overlap=wanted.intersect(found); val ratio=overlap.size.toDouble()/wanted.size
  if(overlap.none{it.length>=4||it.any{ch->ch.isDigit()}}) return 0
  return when { ratio>=0.72->100; ratio>=0.55&&overlap.size>=2->85; ratio>=0.40&&overlap.size>=3->70; else->0 }
 }
 fun search(ean:String,article:String,name:String,brand:String):List<ImageCandidate> {
  val clean=listOf(brand,expand(name)).filter{it.isNotBlank()}.joinToString(" ")
  require(clean.isNotBlank()||ean.isNotBlank()||article.isNotBlank()){ "No product identifier or name to search." }
  val queries=linkedSetOf<String>()
  if(ean.isNotBlank()) queries.add("\"${ean.trim()}\" $clean toy")
  if(article.isNotBlank()) queries.add("\"${article.trim()}\" $clean toy")
  queries.add("site:hamleys.in \"$clean\"")
  queries.add("site:hamleys.com OR site:firstcry.com OR site:toysrus.in OR site:amazon.in \"$clean\"")
  queries.add("\"$clean\" toy product")
  val results=linkedMapOf<String,Pair<Int,ImageCandidate>>()
  for(q in queries){
   if(results.size>=15) break
   val url="https://www.bing.com/images/search?q="+URLEncoder.encode(q,Charsets.UTF_8.name())+"&form=HDRSC3&cc=IN&setlang=en"
   val req=Request.Builder().url(url).header("User-Agent","Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36").header("Accept","text/html,application/xhtml+xml").header("Accept-Language","en-IN,en;q=0.9").build()
   client.newCall(req).execute().use{response->
    if(!response.isSuccessful)return@use
    val html=response.body?.string().orEmpty(); if(html.isBlank())return@use
    for(card in Jsoup.parse(html).select("a.iusc")){
     val meta=runCatching{JSONObject(card.attr("m"))}.getOrNull()?:continue
     val title=meta.optString("t").ifBlank{card.selectFirst("img")?.attr("alt").orEmpty()}
     val relevance=score(name,brand,title); if(relevance==0)continue
     val original=meta.optString("murl"); val thumb=meta.optString("turl")
     val image=if(original.startsWith("https://"))original else if(thumb.startsWith("https://"))thumb else continue
     val sourcePage=meta.optString("purl")
     val sourceBonus=if(sourcePage.contains("hamleys.",true)) 12 else if(domains.any{sourcePage.contains(it,true)}) 6 else 0
     val candidate=ImageCandidate(image,sourcePage,title,if(relevance>=85)"STRONG TITLE MATCH" else "TITLE MATCH")
     val finalScore=relevance+sourceBonus
     val previous=results[image]; if(previous==null||finalScore>previous.first)results[image]=finalScore to candidate
    }
   }
  }
  return results.values.sortedByDescending{it.first}.map{it.second}.distinctBy{it.url}.take(5)
 }
}
