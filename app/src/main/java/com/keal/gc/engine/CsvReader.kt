package com.keal.gc.engine
data class CsvProduct(val original:String,val name:String,val brand:String,val ean:String,val article:String,val mrp:Double?,val quantity:Int?,val warning:String?=null)
object CsvReader {
 fun parse(text:String):List<List<String>> {
  val rows=mutableListOf<List<String>>(); var row=mutableListOf<String>(); val cell=StringBuilder(); var quoted=false; var i=0
  while(i<text.length){val c=text[i];when{
   c=='"' && quoted && i+1<text.length && text[i+1]=='"'->{cell.append('"');i++}
   c=='"'->quoted=!quoted
   c==',' && !quoted->{row.add(cell.toString().trim());cell.setLength(0)}
   (c=='\n'||c=='\r')&&!quoted->{if(c=='\r'&&i+1<text.length&&text[i+1]=='\n')i++;row.add(cell.toString().trim());cell.setLength(0);if(row.any{it.isNotBlank()})rows.add(row);row=mutableListOf()}
   else->cell.append(c)
  };i++}
  row.add(cell.toString().trim());if(row.any{it.isNotBlank()})rows.add(row);return rows
 }
 private fun norm(s:String)=s.lowercase().replace(Regex("[^a-z0-9]"),"")
 private fun find(h:List<String>,vararg names:String)=h.indexOfFirst{v->names.any{norm(v)==norm(it)}}
 fun products(csv:String):List<CsvProduct>{
  val rows=parse(csv.removePrefix("\uFEFF"));if(rows.size<2)return emptyList()
  val h=rows.first();val e=find(h,"EAN No.","EAN","Barcode","Barcode No");val a=find(h,"Article Code","Article","Article No");val d=find(h,"Article Description","Product Description","Product Name","Description");val b=find(h,"Brand Desc.","Brand","Brand Name");val p=find(h,"MRP","Price","Selling Price");val q=find(h,"Qty","Quantity","Stock Qty")
  require(d>=0){"Product description column not found"}
  return rows.drop(1).map { cells ->
   val eanValue = cells.getOrNull(e)?.trim().orEmpty()
   val articleValue = cells.getOrNull(a)?.trim().orEmpty()
   val descriptionValue = cells.getOrNull(d)?.trim().orEmpty()
   val brandValue = cells.getOrNull(b)?.trim().orEmpty()
   val priceValue = cells.getOrNull(p)?.replace(",", "")?.replace("₹", "")?.toDoubleOrNull()
   val quantityValue = cells.getOrNull(q)?.replace(",", "")?.toDoubleOrNull()?.toInt()
   CsvProduct(
    original = descriptionValue,
    name = NameNormalizer.clean(descriptionValue, brandValue),
    brand = brandValue,
    ean = eanValue,
    article = articleValue,
    mrp = priceValue,
    quantity = quantityValue,
    warning = if (descriptionValue.isBlank()) "Missing description" else if (eanValue.isBlank()) "EAN missing" else null
   )
  }
 }
}
