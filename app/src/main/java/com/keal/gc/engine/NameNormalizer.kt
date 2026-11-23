package com.keal.gc.engine
object NameNormalizer {
 private val inventoryNoise=setOf("NE","NEW","E","EX","STK","MS")
 private val dictionary=mapOf("HM" to "Hamleys","MIR" to "Mirada","PP" to "Paw Patrol","BSCVH" to "Vehicle","SS" to "Silicon","SKM" to "Sand Tastic")
 fun clean(description:String,brand:String=""):String {
  val words=description.trim().split(Regex("\\s+")).filter(String::isNotBlank)
  if(words.isEmpty()) return ""
  val expanded=words.mapIndexedNotNull { i,w ->
   val key=w.uppercase().trim(',', '.', '-', '_')
   when { key in inventoryNoise -> null; dictionary.containsKey(key)->dictionary.getValue(key); else->w.trim(',', '.', '-', '_') }
  }.filter(String::isNotBlank).toMutableList()
  val vehicleIndex=expanded.indexOf("Vehicle")
  if(vehicleIndex in 0 until expanded.lastIndex) {
   expanded.removeAt(vehicleIndex)
   expanded.add("Vehicle")
  }
  val title=expanded.joinToString(" ") { w ->
   if(w.matches(Regex("[0-9]+(CM|MM|PCS|PC|IN)?",RegexOption.IGNORE_CASE))) w.lowercase()
   else w.lowercase().replaceFirstChar { it.uppercase() }
  }
  val selectedBrand=brand.trim().ifBlank { expanded.firstOrNull { it in setOf("Hamleys","Mirada","Paw Patrol","PalsPlush") }.orEmpty() }
  val rest=if(selectedBrand.isNotBlank() && title.startsWith(selectedBrand,true)) title.drop(selectedBrand.length).trim() else title
  return if(selectedBrand.isNotBlank() && rest.isNotBlank()) "$selectedBrand – $rest" else title
 }
}
