package com.keal.gc.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

data class ImageCandidate(val url:String,val source:String,val title:String,val confidence:String)

/**
 * Product-first image lookup. Retailer product pages are preferred over generic image search.
 * A result is retained only when its product title is sufficiently similar to the stock description.
 */
class ImageSearch {
 private val client=OkHttpClient.Builder().callTimeout(22,TimeUnit.SECONDS).followRedirects(true).build()
 private var successfulPages=0
 private val stop=setOf("the","and","for","with","toy","toys","product","kids","boys","girls","age","years","new","assorted","multicolour","multicolor","remote","control","car","vehicle")
 private fun expand(raw:String):String {
  var s=raw.lowercase(Locale.ROOT)
  val map=listOf(
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
  map.forEach{(p,v)->s=p.replace(s,v)}
  return s.replace(Regex("[^a-z0-9]+")," ").replace(Regex("\\s+")," ").trim()
 }
 private fun tokens(s:String)=expand(s).split(" ").filter{it.length>=3&&it !in stop}.toSet()
 private fun score(product:String,brand:String,title:String):Int {
  val wanted=tokens("$brand $product"); val found=tokens(title)
  if(wanted.isEmpty()||found.isEmpty())return 0
  val overlap=wanted.intersect(found)
  val ratio=overlap.size.toDouble()/wanted.size
  val distinctive=overlap.count{it.length>=4||it.any(Char::isDigit)}
  if(distinctive==0)return 0
  return when {
   ratio>=0.70->100
   ratio>=0.50&&distinctive>=2->85
   ratio>=0.35&&distinctive>=3->70
   else->0
  }
 }
 private fun request(url:String):String? {
  val req=Request.Builder().url(url)
   .header("User-Agent","Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0.0.0 Mobile Safari/537.36")
   .header("Accept","text/html,application/xhtml+xml")
   .header("Accept-Language","en-IN,en;q=0.9")
   .build()
  return runCatching{client.newCall(req).execute().use{if(it.isSuccessful){successfulPages++;it.body?.string()}else null}}.getOrNull()
 }
 private fun imageFromElement(element:Element):List<String> = element.select("img").mapNotNull{img->
  listOf("src","data-src","data-original","data-lazy-src","data-fynd-img","srcset").asSequence()
   .map{img.attr(it).substringBefore(",").trim().substringBefore(" ").trim()}
   .firstOrNull{it.startsWith("https://")&& !it.contains("logo",true)&& !it.contains("icon",true)}
 }
 private fun productPageImages(pageUrl:String, fallback:List<String>):List<String> {
  val html=request(pageUrl)?:return fallback
  val doc=Jsoup.parse(html,pageUrl)
  val urls=linkedSetOf<String>()
  listOf("meta[property=og:image]","meta[name=twitter:image]","meta[property=og:image:secure_url]").forEach{selector->
   doc.select(selector).forEach{it.attr("content").takeIf{u->u.startsWith("https://")}?.let(urls::add)}
  }
  doc.select("script[type=application/ld+json]").forEach{script->
   runCatching{
    val raw=script.data().ifBlank{script.html()}
    val json=org.json.JSONTokener(raw).nextValue()
    fun collect(value:Any?){
     when(value){
      is JSONObject->{val image=value.opt("image");collect(image);val graph=value.opt("@graph");collect(graph)}
      is JSONArray->for(i in 0 until value.length())collect(value.opt(i))
      is String->if(value.startsWith("https://"))urls.add(value)
     }
    }
    collect(json)
   }
  }
  doc.select("img").forEach{img->
   val alt=img.attr("alt").lowercase(Locale.ROOT)
   val src=listOf("src","data-src","data-original","data-lazy-src").map{img.attr(it)}.firstOrNull{it.startsWith("https://")}
   if(src!=null && (alt.isBlank()||score(pageUrl.substringAfterLast("/"),"",alt)>0))urls.add(src)
  }
  return (urls+fallback).filter{it.startsWith("https://")}.distinct().take(8)
 }
 private fun hamleysSearch(query:String,name:String,brand:String,results:MutableMap<String,Pair<Int,ImageCandidate>>) {
  val url="https://hamleys.in/products?q="+URLEncoder.encode(query,Charsets.UTF_8.name())
  val html=request(url)?:return
  val doc=Jsoup.parse(html,url)
  val links=doc.select("a[href*='/product/'],a[href*='/en/product/']")
  for(link in links){
   val title=link.text().ifBlank{link.attr("title")}.ifBlank{link.selectFirst("img")?.attr("alt").orEmpty()}
   val relevance=score(name,brand,title)
   if(relevance<70)continue
   val pageUrl=link.absUrl("href").ifBlank{link.attr("href")}
   if(!pageUrl.startsWith("https://hamleys.in/"))continue
   val fallbacks=linkedSetOf<String>()
   var parent:Element?=link
   repeat(5){
    parent?.let{imageFromElement(it).forEach(fallbacks::add)}
    parent=parent?.parent()
   }
   val images=productPageImages(pageUrl,fallbacks.toList())
   images.forEach{image->
    val candidate=ImageCandidate(image,pageUrl,title,"HAMLEYS PRODUCT MATCH")
    val score=relevance+30
    val old=results[image]
    if(old==null||score>old.first)results[image]=score to candidate
   }
  }
 }
 private fun otherRetailerSearch(query:String,name:String,brand:String,results:MutableMap<String,Pair<Int,ImageCandidate>>) {
  val q=URLEncoder.encode(query,Charsets.UTF_8.name())
  val endpoints=listOf(
   "https://www.firstcry.com/searchresult?query=$q",
   "https://www.toysrus.in/search?q=$q",
   "https://www.amazon.in/s?k=$q",
   "https://www.landmarkshops.in/search?q=$q"
  )
  for(url in endpoints){
   val html=request(url)?:continue
   val doc=Jsoup.parse(html,url)
   val links=doc.select("a[href]")
   for(link in links){
    val title=link.text().ifBlank{link.attr("title")}.ifBlank{link.selectFirst("img")?.attr("alt").orEmpty()}
    val relevance=score(name,brand,title)
    if(relevance<85)continue
    val href=link.absUrl("href")
    if(!href.startsWith("https://"))continue
    val images=linkedSetOf<String>()
    var parent:Element?=link
    repeat(4){parent?.let{imageFromElement(it).forEach(images::add)};parent=parent?.parent()}
    images.forEach{image->
     val candidate=ImageCandidate(image,href,title,"RETAILER TITLE MATCH")
     val old=results[image];if(old==null||relevance>old.first)results[image]=relevance to candidate
    }
   }
  }
 }
 fun search(ean:String,article:String,name:String,brand:String):List<ImageCandidate>{
  successfulPages=0
  require(name.isNotBlank()||ean.isNotBlank()||article.isNotBlank()){"No product identifier or name to search."}
  val clean=expand(name)
  val queries=linkedSetOf<String>()
  if(ean.isNotBlank())queries.add(ean.trim())
  if(article.isNotBlank())queries.add(article.trim())
  if(clean.isNotBlank())queries.add(clean)
  val core=tokens(clean).filterNot{it.matches(Regex("[a-z]*\\d+[a-z0-9]*")) || it in setOf("grey","gray","red","blue","green","yellow","black","white","pink","ghz","mhz")}.toList()
  if(core.isNotEmpty())queries.add(core.joinToString(" "))
  if(core.size>3)queries.add(core.take(4).joinToString(" "))
  if(brand.isNotBlank()&&core.isNotEmpty())queries.add(brand+" "+core.take(5).joinToString(" "))
  val results=linkedMapOf<String,Pair<Int,ImageCandidate>>()
  // Hamleys is searched first because the stock is intended for Hamleys inventory.
  for(q in queries)hamleysSearch(q,name,brand,results)
  if(results.size<5){
   val combined=listOf(brand,clean).filter{it.isNotBlank()}.joinToString(" ")
   otherRetailerSearch(combined,name,brand,results)
  }
  if(successfulPages==0) error("Could not reach Hamleys or the other product-image websites. Check your internet connection and try again.")
  return results.values.sortedByDescending{it.first}.map{it.second}.distinctBy{it.url}.take(5)
 }
}
