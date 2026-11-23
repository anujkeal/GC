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
import androidx.lifecycle.lifecycleScope
import androidx.room.Room
import coil.compose.AsyncImage
import com.keal.gc.data.*
import com.keal.gc.engine.CsvReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogueScreen(db: GcDatabase) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val products by db.products().observeAll().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<Product?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            status = "Reading CSV…"
            (context as ComponentActivity).lifecycleScope.launch {
                try {
                    val csv = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                            ?: error("Cannot read file")
                    }
                    val parsed = withContext(Dispatchers.Default) { CsvReader.products(csv) }
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
                    status = "Imported ${parsed.size} products"
                } catch (e: Exception) {
                    status = e.message ?: "Import failed"
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
                    TextButton(onClick = { picker.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel")) }) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Text("Import CSV")
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Catalogue", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("${filtered.size} products", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (status.isNotBlank()) {
                Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(vertical = 8.dp))
            }
            if (filtered.isEmpty()) {
                Column(
                    Modifier.fillMaxWidth().weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("Your catalogue starts here", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Import a stock CSV to find and organise product photos.",
                        modifier = Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { picker.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel")) },
                        modifier = Modifier.padding(top = 18.dp)) { Text("Choose CSV file") }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(filtered, key = { it.id }) { product ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selected = product }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Surface(Modifier.size(104.dp), shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant) {
                                if (product.selectedImage.isNotBlank()) {
                                    AsyncImage(product.selectedImage, contentDescription = product.productName)
                                } else {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text("NO IMAGE", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                                Text(product.productName.ifBlank { product.originalDescription },
                                    fontWeight = FontWeight.SemiBold, maxLines = 2)
                                Spacer(Modifier.height(5.dp))
                                Text("EAN: ${product.ean.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                                Text("Article: ${product.articleCode.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                                Text(
                                    "MRP: ${product.mrp?.let { "₹" + "%.2f".format(it) } ?: "—"}   Qty: ${product.quantity ?: "—"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }

    selected?.let { product ->
        var edited by remember(product.id) { mutableStateOf(product.productName) }
        var showImageSearch by remember(product.id) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("Product details") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (product.selectedImage.isNotBlank()) {
                        AsyncImage(product.selectedImage, contentDescription = null,
                            modifier = Modifier.fillMaxWidth().height(140.dp))
                    }
                    OutlinedTextField(value = edited, onValueChange = { edited = it }, label = { Text("Clean product name") })
                    Text("Original: ${product.originalDescription}")
                    Text("EAN: ${product.ean}")
                    Text("Article: ${product.articleCode}")
                    Text("MRP: ${product.mrp ?: "—"}   Quantity: ${product.quantity ?: "—"}")
                    Button(onClick = { showImageSearch = true }) { Text("Find product photos") }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    (context as ComponentActivity).lifecycleScope.launch {
                        db.products().update(product.copy(productName = edited))
                    }
                    selected = null
                }) { Text("Save name") }
            },
            dismissButton = { TextButton(onClick = { selected = null }) { Text("Close") } }
        )
        if (showImageSearch) {
            ImageSearchPanel(
                product = product,
                onDismiss = { showImageSearch = false },
                onSelect = { candidate ->
                    (context as ComponentActivity).lifecycleScope.launch {
                        db.products().update(
                            product.copy(
                                selectedImage = candidate.url,
                                imageUrls = candidate.url,
                                imageSources = candidate.source,
                                imageConfidence = candidate.confidence,
                                imageStatus = "SELECTED"
                            )
                        )
                    }
                    showImageSearch = false
                    selected = null
                }
            )
        }
    }
}
