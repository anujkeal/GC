package com.keal.gc.engine

data class CsvProduct(
    val original: String,
    val name: String,
    val brand: String,
    val ean: String,
    val article: String,
    val mrp: Double?,
    val quantity: Int?,
    val warning: String? = null
)

object CsvReader {
    private fun detectDelimiter(text: String): Char {
        val candidates = listOf(',', ';', '\t')
        val counts = candidates.associateWith { delimiter ->
            var quoted = false
            var count = 0
            var i = 0
            while (i < text.length && text[i] != '\n' && text[i] != '\r') {
                val c = text[i]
                if (c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"') {
                    i++
                } else if (c == '"') {
                    quoted = !quoted
                } else if (c == delimiter && !quoted) {
                    count++
                }
                i++
            }
            count
        }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    fun parse(text: String): List<List<String>> {
        val delimiter = detectDelimiter(text)
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0

        while (i < text.length) {
            val c = text[i]
            when {
                c == '"' && quoted && i + 1 < text.length && text[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' -> quoted = !quoted
                c == delimiter && !quoted -> {
                    row.add(cell.toString().trim())
                    cell.setLength(0)
                }
                (c == '\n' || c == '\r') && !quoted -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(cell.toString().trim())
                    cell.setLength(0)
                    if (row.any { it.isNotBlank() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }

        row.add(cell.toString().trim())
        if (row.any { it.isNotBlank() }) rows.add(row)
        return rows
    }

    private fun norm(value: String) =
        value.removePrefix("\uFEFF").lowercase().replace(Regex("[^a-z0-9]"), "")

    private fun find(headers: List<String>, vararg names: String): Int =
        headers.indexOfFirst { header ->
            val key = norm(header)
            names.any { key == norm(it) }
        }

    fun products(csv: String): List<CsvProduct> {
        val rows = parse(csv.removePrefix("\uFEFF"))
        require(rows.isNotEmpty()) { "The selected file is empty." }
        require(rows.size >= 2) { "No product rows found. Check that the CSV contains a header and product rows." }

        val headers = rows.first()
        val ean = find(headers, "EAN No.", "EAN", "EAN Code", "Barcode", "Barcode No", "GTIN")
        val article = find(headers, "Article Code", "Article", "Article No", "Article Number", "SKU", "Item Code")
        val description = find(
            headers,
            "Article Description", "Article Desc", "Short Text", "Product Description", "Product Name",
            "Description", "Item Description", "Material Description"
        )
        val brand = find(headers, "Brand Desc.", "Brand Description", "Brand", "Brand Name")
        val price = find(headers, "MRP", "MRP Rs", "MRP Value", "Price", "Selling Price")
        val quantity = find(headers, "Qty", "Quantity", "Stock Qty", "Available Qty")

        require(description >= 0) {
            "Product description column not found. Found columns: ${headers.joinToString()}"
        }

        return rows.drop(1).mapNotNull { cells ->
            val descriptionValue = cells.getOrNull(description)?.trim().orEmpty()
            if (descriptionValue.isBlank() && cells.all { it.isBlank() }) return@mapNotNull null

            val eanValue = cells.getOrNull(ean)?.trim().orEmpty()
            val articleValue = cells.getOrNull(article)?.trim().orEmpty()
            val brandValue = cells.getOrNull(brand)?.trim().orEmpty()
            val priceValue = cells.getOrNull(price)?.replace(",", "")?.replace("₹", "")?.trim()?.toDoubleOrNull()
            val quantityValue = cells.getOrNull(quantity)?.replace(",", "")?.trim()?.toDoubleOrNull()?.toInt()

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
