
package com.example.onthisdayphotos

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.stickyHeader
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

private val ComponentActivity.dataStore by preferencesDataStore(name = "settings")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { App() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun App() {
    val activity = LocalContext.current as ComponentActivity
    val context = activity

    var hasPermission by remember { mutableStateOf(false) }
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    val launcher = remember {
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
        }
    }
    LaunchedEffect(Unit) {
        hasPermission = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) launcher.launch(permission)
    }

    val FOLDER_FILTER_KEY = remember { stringSetPreferencesKey("folder_filter_paths") }
    val scope = rememberCoroutineScope()

    var allowedFolders by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        val prefs = activity.dataStore.data.first()
        allowedFolders = prefs[FOLDER_FILTER_KEY] ?: emptySet()
    }

    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var sortAscending by remember { mutableStateOf(false) }

    var availableFolders by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            availableFolders = withContext(Dispatchers.IO) { loadAvailableFolders(context) }
        }
    }

    var photosByYear by remember { mutableStateOf<Map<Int, List<PhotoItem>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(false) }

    suspend fun refresh() {
        if (!hasPermission) return
        isLoading = true
        val items = withContext(Dispatchers.IO) { queryPhotosOnMonthDay(context, selectedDate.monthValue, selectedDate.dayOfMonth, allowedFolders) }
        val grouped = items.groupBy { it.takenDate.year }
        photosByYear = if (sortAscending) grouped.toSortedMap() else grouped.toSortedMap(compareByDescending { it })
        isLoading = false
    }
    LaunchedEffect(selectedDate, sortAscending, allowedFolders, hasPermission) { refresh() }

    var menuExpanded by remember { mutableStateOf(false) }
    var showFolderDialog by remember { mutableStateOf(false) }

    if (showFolderDialog) {
        FolderPickerDialog(
            allFolders = availableFolders,
            initiallyChecked = allowedFolders,
            onDismiss = { showFolderDialog = false },
            onApply = { newSet ->
                scope.launch {
                    activity.dataStore.edit { it[FOLDER_FILTER_KEY] = newSet }
                    allowedFolders = newSet
                    showFolderDialog = false
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("On This Day") },
                actions = {
                    TextButton(onClick = { sortAscending = !sortAscending }) { Text(if (sortAscending) "Oldest → Newest" else "Newest → Oldest") }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) { Icon(Icons.Default.MoreVert, contentDescription = "Menu") }
                        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                            DropdownMenuItem(
                                text = { Text("Select folders…") },
                                onClick = { menuExpanded = false; showFolderDialog = true }
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding)) {
            DatePickerRow(selectedDate) { selectedDate = it }
            if (!hasPermission) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Permission required to read photos") }
                return@Column
            }
            if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (photosByYear.isEmpty() && !isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No photos for this date with current folder filters") }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    photosByYear.forEach { (year, list) ->
                        stickyHeader {
                            Surface(color = MaterialTheme.colorScheme.surface) {
                                Text(year.toString(), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold), modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp))
                            }
                        }
                        items(list) { item -> PhotoRow(item) }
                    }
                }
            }
        }
    }
}

@Composable
fun PhotoRow(item: PhotoItem) {
    Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = item.contentUri, contentDescription = null, modifier = Modifier.size(72.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.takenDate.format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm")), style = MaterialTheme.typography.bodyMedium)
            Text(item.displayName ?: "", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerRow(selected: LocalDate, onPicked: (LocalDate) -> Unit) {
    var showPicker by remember { mutableStateOf(false) }
    if (showPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = selected.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { showPicker = false }, confirmButton = {
            TextButton(onClick = {
                val millis = state.selectedDateMillis
                if (millis != null) {
                    val date = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate()
                    onPicked(LocalDate.of(LocalDate.now().year, date.month, date.dayOfMonth))
                }
                showPicker = false
            }) { Text("OK") }
        }, dismissButton = { TextButton(onClick = { showPicker = false }) { Text("Cancel") } }) {
            DatePicker(state = state, showModeToggle = false)
        }
    }
    Row(Modifier.fillMaxWidth().clickable { showPicker = true }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Date: ", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        Text(selected.format(DateTimeFormatter.ofPattern("d MMM")), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.weight(1f))
        Text("Change", color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
fun FolderPickerDialog(allFolders: List<String>, initiallyChecked: Set<String>, onDismiss: () -> Unit, onApply: (Set<String>) -> Unit) {
    val tree = remember(allFolders) { buildFolderTree(allFolders) }
    var checked by remember(initiallyChecked) { mutableStateOf(initiallyChecked.toMutableSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select folders") },
        text = {
            Column(Modifier.heightIn(max = 420.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { checked.clear() }) { Text("Deselect all") }
                    TextButton(onClick = { checked.clear(); allFolders.forEach { checked.add(it) } }) { Text("Select all") }
                }
                Spacer(Modifier.height(8.dp))
                LazyColumn { items(tree) { node -> FolderNodeRow(node, checked, 0) } }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(checked.toSet()) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
fun FolderNodeRow(node: FolderNode, checked: MutableSet<String>, level: Int) {
    val isChecked = checked.contains(node.fullPath)
    Row(Modifier.fillMaxWidth().padding(start = (level * 16).dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = isChecked, onCheckedChange = { new -> if (new) checked.add(node.fullPath) else checked.remove(node.fullPath) })
        Text(node.name)
    }
    node.children.forEach { FolderNodeRow(it, checked, level + 1) }
}

data class FolderNode(val name: String, val fullPath: String, val children: List<FolderNode>)

fun buildFolderTree(paths: List<String>): List<FolderNode> {
    data class TempNode(val name: String) { val children = mutableMapOf<String, TempNode>() }
    val root = mutableMapOf<String, TempNode>()
    for (p in paths) {
        val parts = p.trim('/').split('/')
        var map = root
        for (part in parts) {
            val node = map.getOrPut(part) { TempNode(part) }
            map = node.children
        }
    }
    fun toNodes(map: Map<String, TempNode>, prefix: String = ""): List<FolderNode> =
        map.toSortedMap().map { (name, temp) ->
            val full = (if (prefix.isEmpty()) name else "$prefix/$name") + "/"
            FolderNode(name, full, toNodes(temp.children, full))
        }
    return toNodes(root)
}

// ---------- Media Query ----------

data class PhotoItem(val id: Long, val contentUri: Uri, val takenDate: LocalDateTimeCompat, val displayName: String?, val relativePath: String?)

data class LocalDateTimeCompat(val year: Int, val month: Int, val day: Int, val hour: Int, val minute: Int) {
    fun format(pattern: DateTimeFormatter): String {
        val dt = java.time.LocalDateTime.of(year, month, day, hour, minute)
        return pattern.format(dt)
    }
}

fun queryPhotosOnMonthDay(context: android.content.Context, month: Int, day: Int, allowedFolderPrefixes: Set<String>): List<PhotoItem> {
    val resolver = context.contentResolver
    val projection = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.RELATIVE_PATH,
        MediaStore.Images.Media.DATA
    )
    val sortOrder = MediaStore.Images.Media.DATE_TAKEN + " DESC"
    val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val result = mutableListOf<PhotoItem>()
    resolver.query(uri, projection, null, null, sortOrder)?.use { cursor ->
        val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
        val takenCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
        val addedCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
        val relCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
        val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idCol)
            val name = cursor.getString(nameCol)
            var timeMillis = cursor.getLong(takenCol)
            if (timeMillis == 0L) timeMillis = cursor.getLong(addedCol) * 1000L
            val dt = Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
            if (dt.monthValue != month || dt.dayOfMonth != day) continue
            val rel = if (relCol != -1) cursor.getString(relCol) else null
            val relPath = rel ?: run {
                if (dataCol != -1) {
                    val full = cursor.getString(dataCol) ?: return@run null
                    val markers = listOf("/DCIM/", "/Pictures/", "/Download/", "/Screenshots/")
                    val m = markers.firstOrNull { full.contains(it) }
                    if (m != null) full.substring(full.indexOf(m) + 1, full.lastIndexOf('/') + 1) else null
                } else null
            }
            if (allowedFolderPrefixes.isNotEmpty()) {
                val matches = relPath != null && allowedFolderPrefixes.any { prefix -> relPath.startsWith(prefix) }
                if (!matches) continue
            }
            val contentUri = ContentUris.withAppendedId(uri, id)
            result.add(PhotoItem(id, contentUri, LocalDateTimeCompat(dt.year, dt.monthValue, dt.dayOfMonth, dt.hour, dt.minute), name, relPath))
        }
    }
    return result
}

suspend fun loadAvailableFolders(context: android.content.Context): List<String> = withContext(Dispatchers.IO) {
    val resolver = context.contentResolver
    val projection = arrayOf(MediaStore.Images.Media.RELATIVE_PATH, MediaStore.Images.Media.DATA)
    val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val set = linkedSetOf<String>()
    resolver.query(uri, projection, null, null, null)?.use { cursor ->
        val relCol = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
        val dataCol = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
        while (cursor.moveToNext()) {
            val rel = if (relCol != -1) cursor.getString(relCol) else null
            val path = rel ?: run {
                if (dataCol != -1) {
                    val full = cursor.getString(dataCol)
                    if (full != null) {
                        val markers = listOf("/DCIM/", "/Pictures/", "/Download/", "/Screenshots/")
                        val m = markers.firstOrNull { full.contains(it) }
                        if (m != null) full.substring(full.indexOf(m) + 1, full.lastIndexOf('/') + 1) else null
                    } else null
                } else null
            }
            if (path != null && path.isNotEmpty()) set.add(path)
            if (set.size > 500) break
        }
    }
    return@withContext set.sorted()
}
