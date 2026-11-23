package com.keal.gc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.keal.gc.data.Product
import com.keal.gc.engine.ImageCandidate
import com.keal.gc.engine.ImageSearch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ImageSearchPanel(product: Product, onDismiss: () -> Unit, onSelect: (ImageCandidate) -> Unit) {
    var results by remember(product.id) { mutableStateOf<List<ImageCandidate>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("Search exact EAN first, then brand and product name.") }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Find product photos") },
        text = {
            Column {
                Text(product.productName, style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                Button(enabled = !loading, onClick = {
                    loading = true
                    message = "Searching image provider…"
                    scope.launch {
                        try {
                            results = withContext(Dispatchers.IO) {
                                ImageSearch().search(product.ean, product.productName, product.brand)
                            }
                            message = if (results.isEmpty()) "No image candidates returned. Check credentials, quota or try manual search." else "${results.size} candidates — verify the exact product before selecting."
                        } catch (e: Exception) {
                            message = e.message ?: "Image search failed"
                        } finally { loading = false }
                    }
                }) { Text(if (loading) "Searching…" else "Search images") }
                Text(message, style = MaterialTheme.typography.bodySmall)
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(results) { candidate ->
                        Column(Modifier.fillMaxWidth().clickable { onSelect(candidate) }.padding(vertical = 8.dp)) {
                            AsyncImage(candidate.url, candidate.title, modifier = Modifier.fillMaxWidth().height(130.dp))
                            Text(candidate.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Text("${candidate.confidence} confidence · ${candidate.source}", style = MaterialTheme.typography.labelSmall, maxLines = 2)
                            Text("Select this image", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                            HorizontalDivider()
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
