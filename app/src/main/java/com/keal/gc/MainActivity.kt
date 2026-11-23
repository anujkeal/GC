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
    val preferences = remember { context.getSharedPreferences("gc_settings", android.content.Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var isImporting by remember { mutableStateOf(false) }
    var isSearching by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var apiKey by remember { mutableStateOf(preferences.getString("serper_api_key", "").orEmpty()) }
    var keyDraft by remember { mutableStateOf(apiKey) }

    fun searchPhotos(items: List<Product>) {
        if (apiKey.isBlank()) {
            status = "Imported. Add your Serper API key in API settings to fetch Google photos."
            keyDraft = apiKey
            showSettings = true
            return
        }
        if (isSearching || items.isEmpty()) return
        isSearching = true
        scope.launch {
            var done = 0
            var withPhotos = 0
            var errors = 0
            status = "Finding Google photos: 0 / " + items.size
            try {
                for (product in items) {
                    try {
                        val candidates = withContext(Dispatchers.IO) {
                            ImageSearch(apiKey).search(product.ean, product.productName, product.brand)
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
                        if (errors == 1) status = "Image search error: " + (e.message ?: "Check API key and internet.")
                    }
                    done++
                    status = "Finding Google photos: " + done + " / " + items.size + " · " + withPhotos + " found"
                }
                status = "Photo search finished: " + withPhotos + " products with photos. " +
                    (items.size - withPhotos) + " without results" + if (errors > 0) " · $errors errors" else "."
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
                    TextButton(onClick = { keyDraft = apiKey; showSettings = true }) { Text("API settings") }
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
                    color = if (status.startsWith("Import failed") || status.startsWith("Image search error")) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
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
                            val pending = withContext(Dispatchers.IO) { db.products().needingImages() }
                            searchPhotos(pending)
                        }
                    }) { Text(if (isSearching) "Searching photos…" else "Find missing photos") }
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
                                        Text(if (product.imageStatus == "ERROR") "IMAGE SEARCH FAILED · TAP TO RETRY" else if (product.imageStatus == "DONE") "NO PHOTOS FOUND" else "PHOTOS NOT SEARCHED")
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

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Google Images API") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Paste your Serper API key. It is saved in this app on this device and is not built into the APK.")
                    OutlinedTextField(value = keyDraft, onValueChange = { keyDraft = it }, label = { Text("Serper API key") }, singleLine = true)
                    TextButton(onClick = {
                        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://serper.dev/")))
                    }) { Text("Create a key at serper.dev") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    apiKey = keyDraft.trim()
                    preferences.edit().putString("serper_api_key", apiKey).apply()
                    showSettings = false
                    if (apiKey.isNotBlank()) searchPhotos(products.filter { it.imageUrls.isBlank() || it.imageStatus != "DONE" })
                }) { Text("Save and find photos") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Cancel") } }
        )
    }
}
