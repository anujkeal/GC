package com.keal.gc

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.room.Room
import coil.compose.AsyncImage
import com.keal.gc.data.*
import com.keal.gc.engine.CsvReader
import com.keal.gc.engine.ImageSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val db = Room.databaseBuilder(applicationContext, GcDatabase::class.java, "gc-catalogue.db").build()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = androidx.compose.ui.graphics.Color(0xFF202124))) {
                CatalogueScreen(db)
            }
        }
    }
}

private fun decodeImageUrls(raw: String): List<String> = runCatching {
    val array = JSONArray(raw)
    (0 until array.length()).mapNotNull { array.optString(it).takeIf { url -> url.isNotBlank() } }
}.getOrDefault(emptyList())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogueScreen(db: GcDatabase) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val products by db.products().observeAll().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var showApiSettings by remember { mutableStateOf(false) }
    var apiUrl by remember { mutableStateOf(context.getSharedPreferences("gc_settings", 0).getString("ddgs_api_url", "https://gc-ddgs-api.onrender.com") ?: "") }

    fun searchPhotos(items: List<Product>) {
        if (isSearching || items.isEmpty()) return
        isSearching = true
        scope.launch {
            var done = 0
            var withPhotos = 0
            var errors = 0
            var firstError = ""
            status = "Finding product photos: 0 / " + items.size
            try {
                for (product in items) {
                    try {
                        val candidates = withContext(Dispatchers.IO) {
                            ImageSearch(context).search(product.ean, product.articleCode, product.productName, product.brand)
                        }
                        val urls = JSONArray().apply { candidates.forEach { put(it.url) } }.toString()
                        db.products().update(
                            product.copy(
                                imageUrls = urls,
                                selectedImage = candidates.firstOrNull()?.url.orEmpty(),
                                imageSources = candidates.joinToString("|") { it.source },
                                imageConfidence = candidates.firstOrNull()?.confidence ?: "NONE",
                                imageStatus = "DONE"
                            )
                        )
                        if (candidates.isNotEmpty()) withPhotos++
                    } catch (e: Exception) {
                        errors++
                        db.products().update(product.copy(imageStatus = "ERROR"))
                        if (firstError.isBlank()) firstError = e.message ?: "Unknown image search error."
                    }
                    done++
                    status = "Finding product photos: " + done + " / " + items.size + " · " + withPhotos + " products found"
                }
                status = if (errors > 0) {
                    "Photo search issue: " + withPhotos + " / " + items.size + " products have photos; " + errors + " searches failed. " + firstError
                } else if (withPhotos == 0) {
                    "No relevant product photos were found on the searched retailer websites. Try again later or check the product description."
                } else {
                    "Photo search finished: " + withPhotos + " / " + items.size + " products have image results."
                }
            } finally {
                isSearching = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null && !isImporting) {
            isImporting = true
            status = "Opening stock file…"
            scope.launch {
                try {
                    val csv = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: error("Android could not open this file.")
                    }
                    require(csv.isNotBlank()) { "The selected file is empty." }
                    val parsed = withContext(Dispatchers.Default) { CsvReader.products(csv) }
                    require(parsed.isNotEmpty()) { "No product rows were found." }
                    val importId = db.imports().insert(
                        ImportRecord(fileName = uri.lastPathSegment ?: "catalogue.csv", rowCount = parsed.size)
                    )
                    withContext(Dispatchers.IO) {
                        db.products().insertAll(parsed.map { p ->
                            Product(
                                importId = importId,
                                originalDescription = p.original,
                                productName = p.name,
                                brand = p.brand,
                                ean = p.ean,
                                articleCode = p.article,
                                mrp = p.mrp,
                                quantity = p.quantity
                            )
                        })
                    }
                    val imported = withContext(Dispatchers.IO) { db.products().byImportId(importId) }
                    status = "Successfully imported " + parsed.size + " products."
                    searchPhotos(imported)
                } catch (e: Exception) {
                    status = "Import failed: " + (e.message ?: "Unknown error")
                } finally {
                    isImporting = false
                }
            }
        }
    }

    if (showApiSettings) {
        AlertDialog(
            onDismissRequest = { showApiSettings = false },
            title = { Text("DDGS image search") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the HTTPS address of the GC DDGS image service. No API key is required.")
                    OutlinedTextField(
                        value = apiUrl,
                        onValueChange = { apiUrl = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("DDGS server URL") },
                        placeholder = { Text("https://your-service.onrender.com") }
                    )
                    Text("Default: https://gc-ddgs-api.onrender.com", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clean = apiUrl.trim().trimEnd('/')
                    if (clean.startsWith("https://")) {
                        context.getSharedPreferences("gc_settings", 0).edit().putString("ddgs_api_url", clean).apply()
                        apiUrl = clean
                        showApiSettings = false
                        status = "DDGS server address saved."
                    } else {
                        status = "Please enter a valid HTTPS server URL."
                    }
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showApiSettings = false }) { Text("Cancel") } }
        )
    }

    val filtered = products.filter { p ->
        listOf(p.productName, p.ean, p.articleCode, p.brand).any { it.contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("GC", fontWeight = FontWeight.Bold)
                        Text("PRODUCT PHOTO FINDER", style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    TextButton(onClick = { showApiSettings = true }) { Text("API settings") }
                    TextButton(enabled = !isImporting, onClick = { picker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text(if (isImporting) "Importing…" else "Import CSV")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 18.dp)) {
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search name, EAN or article") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Catalogue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(filtered.size.toString() + " products", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status.isNotBlank()) {
                Surface(
                    color = if (status.startsWith("Import failed") || status.startsWith("Photo search issue")) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
                ) {
                    Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                }
            }
            if (filtered.isEmpty()) {
                Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("Your catalogue starts here", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Import a stock CSV to find and organise product photos.", modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { picker.launch(arrayOf("*/*")) }, modifier = Modifier.padding(top = 18.dp)) { Text("Choose CSV file") }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(enabled = !isSearching, onClick = {
                        scope.launch {
                            val all = withContext(Dispatchers.IO) { db.products().allForImageRefresh() }
                            withContext(Dispatchers.IO) {
                                all.forEach { item ->
                                    db.products().update(item.copy(imageUrls = "", selectedImage = "", imageSources = "", imageConfidence = "NONE", imageStatus = "NOT_SEARCHED"))
                                }
                            }
                            val fresh = withContext(Dispatchers.IO) { db.products().allForImageRefresh() }
                            searchPhotos(fresh)
                        }
                    }) { Text(if (isSearching) "Searching photos…" else "Recheck all product photos") }
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(filtered, key = { it.id }) { product ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(product.productName.ifBlank { product.originalDescription }, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Spacer(Modifier.height(6.dp))
                            val urls = decodeImageUrls(product.imageUrls)
                            if (urls.isNotEmpty()) {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(urls) { url ->
                                        AsyncImage(model = url, contentDescription = product.productName, modifier = Modifier.size(width = 112.dp, height = 112.dp))
                                    }
                                }
                            } else {
                                Surface(
                                    Modifier.fillMaxWidth().height(64.dp).clickable {
                                        if (!isSearching) searchPhotos(listOf(product))
                                    },
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(if (product.imageStatus == "ERROR") "PHOTO SEARCH FAILED · TAP TO RETRY" else if (product.imageStatus == "DONE") "NO PHOTOS FOUND" else "PHOTOS NOT SEARCHED")
                                    }
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("EAN: " + product.ean.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
                            Text("Article: " + product.articleCode.ifBlank { "—" }, style = MaterialTheme.typography.bodySmall)
                            Text("MRP: " + (product.mrp?.let { "₹" + "%.2f".format(it) } ?: "—") + "   Qty: " + (product.quantity?.toString() ?: "—"), style = MaterialTheme.typography.bodySmall)
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}
