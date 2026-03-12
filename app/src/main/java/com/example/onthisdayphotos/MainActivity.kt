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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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

// DataStore
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    val activity = LocalContext.current as ComponentActivity
    val context = activity

    // --- Runtime permission for images ---
    var hasPermission by remember { mutableStateOf(false) }
    val permission =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES
        else Manifest.permission.READ_EXTERNAL_STORAGE

    val launcher = remember {
        activity.registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            hasPermission = granted
        }
    }
    LaunchedEffect(Unit) {
        hasPermission = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        if (!hasPermission) launcher.launch(permission)
    }

    // --- Preferences / filters ---
    val FOLDER_FILTER_KEY = remember { stringSetPreferencesKey("folder_filter_paths") }
    val scope = rememberCoroutineScope()

    var selectedDate by remember { mutableStateOf(LocalDate.now()) }
    var sortAscending by remember { mutableStateOf(false) }

    // Load persisted folder selections
    var allowedFolders by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(Unit) {
        val prefs = activity.dataStore.data.first()
        allowedFolders = prefs[FOLDER_FILTER_KEY] ?: emptySet()
    }

    // Discover folders
    var availableFolders by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            availableFolders = withContext(Dispatchers.IO) { loadAvailableFolders(context) }
        }
    }

    // Photos (grouped by year)
    var photosByYear by remember { mutableStateOf<Map<Int, List<PhotoItem>>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(false) }

    suspend fun refresh() {
        if (!hasPermission) return
        isLoading = true
        val items = withContext(Dispatchers.IO) {
            queryPhotosOnMonthDay(context, selectedDate.monthValue, selectedDate.dayOfMonth, allowedFolders)
        }
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
                    TextButton(onClick = { sortAscending = !sortAscending }) {
                        Text(if (sortAscending) "Oldest → Newest" else "Newest → Oldest")
                    }
                    Box {
