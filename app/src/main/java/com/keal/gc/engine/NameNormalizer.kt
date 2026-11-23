package com.keal.gc.engine
object NameNormalizer {
 private val prefixes=setOf("NE","NEW","E","EX","STK")
 private val dictionary=mapOf("HM" to "Hamleys","MIR" to "Mirada","PP" to "Paw Patrol","BSCVH" to "Vehicle","SS" to "Silicon","SKM" to "Sand Tastic")
 fun clean(description:String,brand:String=""):String {
  val words=description.trim().split(Regex("\\s+")).filter(String::isNotBlank)
  if(words.isEmpty()) return ""
  val expanded=words.mapIndexedNotNull { i,w -> val key=w.uppercase().trim(',', '.', '-', '_'); when { i==0 && key in prefixes -> null; dictionary.containsKey(key)->dictionary.getValue(key); else->w.trim(',', '.', '-', '_') } }.filter(String::isNotBlank)
  val title=expanded.joinToString(" ") { w -> if(w.matches(Regex("[0-9]+(CM|MM|PCS|PC|IN)?",RegexOption.IGNORE_CASE))) w.lowercase().replaceFirstChar { it.uppercase() } else w.lowercase().replaceFirstChar { it.uppercase() } }
  val b=brand.trim().ifBlank { expanded.firstOrNull { it in setOf("Hamleys","Mirada","Paw Patrol") }.orEmpty() }
  val rest=if(b.isNotBlank() && title.startsWith(b,true)) title.drop(b.length).trim() else title
  return if(b.isNotBlank() && rest.isNotBlank()) "$b – $rest" else title
 }
}
