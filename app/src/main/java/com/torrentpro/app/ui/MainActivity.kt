package com.torrentpro.app.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.torrentpro.app.data.engine.StorageHelper
import com.torrentpro.app.data.model.AppSettings
import com.torrentpro.app.data.model.TorrentItem
import com.torrentpro.app.ui.player.VideoPlayerActivity
import java.io.File

// App Theme Palette
val DarkBg = Color(0xFF0F111A)
val CardBg = Color(0xFF181B28)
val CardBorder = Color(0xFF25293D)
val AccentCyan = Color(0xFF00E5FF)
val AccentGreen = Color(0xFF7EE8A0)
val AccentRed = Color(0xFFFF5370)
val AccentYellow = Color(0xFFFFD166)
val TextDim = Color(0xFF8E95AA)

class MainActivity : ComponentActivity() {

    private val viewModel: TorrentViewModel by viewModels()
    private var lastStreamLaunchTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermission()
        handleIncomingIntent(intent)

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    background = DarkBg,
                    surface = CardBg,
                    primary = AccentCyan,
                    secondary = AccentGreen
                )
            ) {
                MainScreen(
                    viewModel = viewModel,
                    onOpenVideoPlayer = { url, title, hash ->
                        val now = System.currentTimeMillis()
                        // Debounce rapid clicks (within 1000ms) to prevent duplicate player activities
                        if (now - lastStreamLaunchTime > 1000) {
                            lastStreamLaunchTime = now
                            val pIntent = Intent(this, VideoPlayerActivity::class.java).apply {
                                putExtra(VideoPlayerActivity.EXTRA_VIDEO_URL, url)
                                putExtra(VideoPlayerActivity.EXTRA_VIDEO_TITLE, title)
                                putExtra(VideoPlayerActivity.EXTRA_INFO_HASH, hash)
                            }
                            startActivity(pIntent)
                        }
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme == "magnet") {
            viewModel.addMagnet(data.toString())
            Toast.makeText(this, "Magnet added from link", Toast.LENGTH_SHORT).show()
        } else if (data.scheme == "content" || data.scheme == "file") {
            try {
                contentResolver.openInputStream(data)?.use { stream ->
                    val bytes = stream.readBytes()
                    viewModel.addTorrentFile(bytes)
                    Toast.makeText(this, "Torrent file added", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this, "Failed to read torrent: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                val launcher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: TorrentViewModel,
    onOpenVideoPlayer: (url: String, title: String, hash: String) -> Unit
) {
    val context = LocalContext.current
    val torrents by viewModel.torrents.collectAsState()
    val completedTorrents by viewModel.completedTorrents.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val dlSpeed by viewModel.totalDownloadSpeed.collectAsState()
    val ulSpeed by viewModel.totalUploadSpeed.collectAsState()
    val activeCount by viewModel.activeCount.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var showAddDialog by rememberSaveable { mutableStateOf(false) }
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(AccentCyan),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Download,
                                contentDescription = null,
                                tint = DarkBg,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "TorrentPro",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color.White
                            )
                            Text(
                                text = "↓ ${TorrentItem.formatBytes(dlSpeed)}/s   ↑ ${TorrentItem.formatBytes(ulSpeed)}/s",
                                fontSize = 11.sp,
                                color = if (dlSpeed > 0) AccentCyan else TextDim,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (activeCount > 0) viewModel.pauseAll() else viewModel.resumeAll()
                    }) {
                        Icon(
                            imageVector = if (activeCount > 0) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = "Pause/Resume All",
                            tint = if (activeCount > 0) AccentYellow else AccentGreen
                        )
                    }
                    IconButton(onClick = { showSettingsDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkBg)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = AccentCyan,
                contentColor = DarkBg
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Torrent")
            }
        },
        containerColor = DarkBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Tabs: Downloading & Completed
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = DarkBg,
                contentColor = AccentCyan,
                divider = { HorizontalDivider(color = CardBorder) }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = {
                        Text(
                            text = "Downloading (${torrents.size})",
                            fontWeight = if (selectedTab == 0) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = {
                        Text(
                            text = "Completed (${completedTorrents.size})",
                            fontWeight = if (selectedTab == 1) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                )
            }

            if (selectedTab == 0) {
                if (torrents.isEmpty()) {
                    EmptyTorrentList(onAddClick = { showAddDialog = true })
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(torrents, key = { it.infoHash }) { item ->
                            DownloadingTorrentCard(
                                item = item,
                                onPause = { viewModel.pauseTorrent(item.infoHash) },
                                onResume = { viewModel.resumeTorrent(item.infoHash) },
                                onRemove = { deleteFiles ->
                                    viewModel.removeTorrent(item.infoHash, deleteFiles)
                                },
                                onPlayVideo = {
                                    val videoPath = item.videoFilePath ?: run {
                                        val potentialFile = File(item.savePath, item.name)
                                        if (potentialFile.exists()) potentialFile.absolutePath else null
                                    }
                                    if (videoPath == null) {
                                        Toast.makeText(context, "Preparing video pieces... please wait a moment", Toast.LENGTH_SHORT).show()
                                    } else {
                                        val streamUrl = viewModel.getStreamUrl(videoPath)
                                        onOpenVideoPlayer(streamUrl, item.name, item.infoHash)
                                    }
                                },
                                onRestart = {
                                    viewModel.resumeTorrent(item.infoHash)
                                }
                            )
                        }
                    }
                }
            } else {
                if (completedTorrents.isEmpty()) {
                    EmptyCompletedList()
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(completedTorrents, key = { it.infoHash }) { item ->
                            CompletedTorrentCard(
                                item = item,
                                onRemove = { deleteFiles ->
                                    viewModel.removeCompletedTorrent(item.infoHash, deleteFiles)
                                },
                                onPlayVideo = {
                                    val localPath = item.videoFilePath ?: File(item.savePath, item.name).absolutePath
                                    onOpenVideoPlayer(localPath, item.name, item.infoHash)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddTorrentDialog(
            defaultDir = settings.downloadDirectory,
            onDismiss = { showAddDialog = false },
            onAddMagnet = { magnet, customDir ->
                viewModel.addMagnet(magnet, customDir)
                showAddDialog = false
            },
            onAddTorrentFile = { bytes, customDir ->
                viewModel.addTorrentFile(bytes, customDir)
                showAddDialog = false
            }
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            currentSettings = settings,
            onDismiss = { showSettingsDialog = false },
            onTestConnection = { cb -> viewModel.testConnection(cb) },
            onSave = { updated ->
                viewModel.updateSettings(updated)
                showSettingsDialog = false
            }
        )
    }
}

@Composable
fun DownloadingTorrentCard(
    item: TorrentItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRemove: (Boolean) -> Unit,
    onPlayVideo: () -> Unit,
    onRestart: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CardBorder))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Row 1: Name and Badges
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = item.name,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                if (item.isVideo) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        color = AccentCyan.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "VIDEO",
                            color = AccentCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Clearly visible destination folder
            Text(
                text = "📁 " + if (item.displayDestination.isNotBlank()) item.displayDestination else item.savePath,
                color = TextDim,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))

            // State & Percentage
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = item.stateString,
                    color = when {
                        item.fileMissing -> AccentRed
                        item.isPaused -> AccentYellow
                        item.stateString.contains("metadata") -> Color(0xFFB388FF)
                        else -> AccentCyan
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = item.progressPercent,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Progress Bar
            LinearProgressIndicator(
                progress = { item.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = if (item.isFinished) AccentGreen else AccentCyan,
                trackColor = Color(0xFF0F111A)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Stats info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "↓ ${item.formattedDownloadSpeed}   ↑ ${item.formattedUploadSpeed}",
                    color = TextDim,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "${item.formattedDownloaded} / ${item.formattedTotal}",
                    color = TextDim,
                    fontSize = 11.sp
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "ETA: ${item.formattedEta}",
                    color = TextDim,
                    fontSize = 11.sp
                )
                Text(
                    text = "Peers: ${item.peers} (${item.seeds} seeds)",
                    color = TextDim,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Action Buttons Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End
            ) {
                if (item.fileMissing) {
                    Button(
                        onClick = onRestart,
                        colors = ButtonDefaults.buttonColors(containerColor = AccentYellow, contentColor = Color.Black),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text("Restart Download", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Play Video Stream Button (If video file is detected)
                if (item.isVideo && !item.fileMissing) {
                    Button(
                        onClick = onPlayVideo,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentCyan.copy(alpha = 0.2f),
                            contentColor = AccentCyan
                        ),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Stream Video", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Pause / Resume Button
                if (!item.fileMissing) {
                    OutlinedButton(
                        onClick = if (item.isPaused) onResume else onPause,
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = if (item.isPaused) AccentGreen else Color.White
                        )
                    ) {
                        Icon(
                            imageVector = if (item.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (item.isPaused) "Resume" else "Pause", fontSize = 12.sp)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                // Delete Button
                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = AccentRed.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        var deleteDataChecked by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remove Torrent?") },
            text = {
                Column {
                    Text("Are you sure you want to remove '${item.name}' from the list?")
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = deleteDataChecked,
                            onCheckedChange = { deleteDataChecked = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Also delete downloaded files from storage", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(deleteDataChecked)
                    showDeleteConfirm = false
                }) {
                    Text("Remove", color = AccentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun CompletedTorrentCard(
    item: TorrentItem,
    onRemove: (Boolean) -> Unit,
    onPlayVideo: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = CardBg),
        border = CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(CardBorder))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (item.fileMissing) Icons.Default.Warning else Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = if (item.fileMissing) AccentYellow else AccentGreen,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = item.name,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Clear visible destination
            Text(
                text = "📁 " + if (item.displayDestination.isNotBlank()) item.displayDestination else item.savePath,
                color = TextDim,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = if (item.fileMissing) "Status: File was moved or deleted externally" else "Size: ${item.formattedTotal} • Seeding Stopped",
                color = if (item.fileMissing) AccentYellow else TextDim,
                fontSize = 12.sp
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (item.isVideo && !item.fileMissing) {
                    Button(
                        onClick = onPlayVideo,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentGreen.copy(alpha = 0.2f),
                            contentColor = AccentGreen
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play Video", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }

                IconButton(
                    onClick = { showDeleteConfirm = true },
                    modifier = Modifier.size(34.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = AccentRed.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }

    if (showDeleteConfirm) {
        var deleteDataChecked by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Remove from History?") },
            text = {
                Column {
                    Text("Remove '${item.name}' from completed list?")
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = deleteDataChecked,
                            onCheckedChange = { deleteDataChecked = it }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Also delete file from disk", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRemove(deleteDataChecked)
                    showDeleteConfirm = false
                }) {
                    Text("Delete", color = AccentRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun EmptyTorrentList(onAddClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.Download,
                contentDescription = null,
                tint = TextDim.copy(alpha = 0.4f),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No active downloads",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Tap + to paste a magnet link or open a .torrent file",
                color = TextDim,
                fontSize = 13.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = onAddClick,
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = DarkBg)
            ) {
                Text("Add Torrent")
            }
        }
    }
}

@Composable
fun EmptyCompletedList() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = TextDim.copy(alpha = 0.4f),
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No completed downloads yet",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Finished downloads stop seeding and appear here",
                color = TextDim,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
fun AddTorrentDialog(
    defaultDir: String,
    onDismiss: () -> Unit,
    onAddMagnet: (String, String) -> Unit,
    onAddTorrentFile: (ByteArray, String) -> Unit
) {
    val context = LocalContext.current
    var magnetText by rememberSaveable { mutableStateOf("") }
    // Per-download destination independent of global default
    var chosenDir by rememberSaveable { mutableStateOf(defaultDir) }

    val torrentFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val bytes = stream.readBytes()
                    onAddTorrentFile(bytes, chosenDir)
                }
            } catch (e: Exception) {
                Toast.makeText(context, "Error opening file: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            StorageHelper.persistTreeUri(context, uri)
            chosenDir = uri.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Torrent / Magnet", fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                // Magnet input
                OutlinedTextField(
                    value = magnetText,
                    onValueChange = { magnetText = it },
                    label = { Text("Magnet link (magnet:?xt=...)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    trailingIcon = {
                        IconButton(onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                            if (!clip.isNullOrBlank() && clip.startsWith("magnet:")) {
                                magnetText = clip
                            } else {
                                Toast.makeText(context, "No magnet link on clipboard", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                        }
                    }
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Individual Destination selection
                Text("Destination Folder for this file:", fontSize = 12.sp, color = TextDim)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = StorageHelper.getHumanReadablePath(context, chosenDir),
                        fontSize = 12.sp,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { folderLauncher.launch(null) }) {
                        Text("Choose Folder", color = AccentCyan)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = CardBorder)
                Spacer(modifier = Modifier.height(10.dp))

                // Select .torrent file button
                OutlinedButton(
                    onClick = { torrentFileLauncher.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Folder, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select .torrent File From Storage")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val m = magnetText.trim()
                    if (m.startsWith("magnet:")) {
                        onAddMagnet(m, chosenDir)
                    } else {
                        Toast.makeText(context, "Please enter a valid magnet link", Toast.LENGTH_SHORT).show()
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = DarkBg)
            ) {
                Text("Start Download")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
fun SettingsDialog(
    currentSettings: AppSettings,
    onDismiss: () -> Unit,
    onTestConnection: ((Boolean, String) -> Unit) -> Unit,
    onSave: (AppSettings) -> Unit
) {
    val context = LocalContext.current
    var stopSeeding by rememberSaveable { mutableStateOf(currentSettings.stopSeedingOnComplete) }
    var autoRemove by rememberSaveable { mutableStateOf(currentSettings.autoRemoveOnComplete) }
    var sequentialVideo by rememberSaveable { mutableStateOf(currentSettings.enableSequentialVideo) }
    var maxConn by rememberSaveable { mutableStateOf(currentSettings.maxConnections.toString()) }
    var maxConnPerTorrent by rememberSaveable { mutableStateOf(currentSettings.maxConnectionsPerTorrent.toString()) }
    var listenPort by rememberSaveable { mutableStateOf(currentSettings.listenPort.toString()) }
    var maxDlSpeed by rememberSaveable { mutableStateOf(currentSettings.maxDownloadSpeedKbps.toString()) }
    var maxUlSpeed by rememberSaveable { mutableStateOf(currentSettings.maxUploadSpeedKbps.toString()) }
    var dhtNodes by rememberSaveable { mutableStateOf(currentSettings.dhtBootstrapNodes) }
    var enableDht by rememberSaveable { mutableStateOf(currentSettings.enableDht) }
    var enableLsd by rememberSaveable { mutableStateOf(currentSettings.enableLsd) }
    var onlyWifi by rememberSaveable { mutableStateOf(currentSettings.onlyOnWifi) }
    var downloadDir by rememberSaveable { mutableStateOf(currentSettings.downloadDirectory) }

    // Test connection feedback
    var testResultMsg by remember { mutableStateOf<String?>(null) }
    var isTestingConnection by remember { mutableStateOf(false) }

    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            StorageHelper.persistTreeUri(context, uri)
            downloadDir = uri.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings & Connections", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            ) {
                // Section 1: Default Storage Location
                Text("GLOBAL DEFAULT DOWNLOAD FOLDER", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = StorageHelper.getHumanReadablePath(context, downloadDir),
                        fontSize = 12.sp,
                        color = Color.White,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { folderLauncher.launch(null) }) {
                        Text("Change", color = AccentCyan)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = CardBorder)
                Spacer(modifier = Modifier.height(10.dp))

                // Section 2: Download & Seeding Behaviors
                Text("DOWNLOAD & SEEDING BEHAVIOR", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Stop Seeding When Complete", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Cuts upload bandwidth to 0 to save battery & data", fontSize = 11.sp, color = TextDim)
                    }
                    Switch(checked = stopSeeding, onCheckedChange = { stopSeeding = it })
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Auto-Remove on Finish", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Moves finished torrent to Completed tab", fontSize = 11.sp, color = TextDim)
                    }
                    Switch(checked = autoRemove, onCheckedChange = { autoRemove = it })
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Stream Video While Downloading", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text("Sequential piece priority for smooth playback", fontSize = 11.sp, color = TextDim)
                    }
                    Switch(checked = sequentialVideo, onCheckedChange = { sequentialVideo = it })
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = CardBorder)
                Spacer(modifier = Modifier.height(10.dp))

                // Section 3: Network & Thermal Connection Settings
                Text("NETWORK & CONNECTION SETTINGS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AccentCyan)
                Spacer(modifier = Modifier.height(6.dp))

                OutlinedTextField(
                    value = listenPort,
                    onValueChange = { listenPort = it },
                    label = { Text("Listen Port (1 - 65535)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = maxDlSpeed,
                        onValueChange = { maxDlSpeed = it },
                        label = { Text("Max DL (KB/s, 0=unlimited)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = maxUlSpeed,
                        onValueChange = { maxUlSpeed = it },
                        label = { Text("Max UL (KB/s)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Connection Limits (Custom text boxes)
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = maxConn,
                        onValueChange = { maxConn = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Global Max Conn") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedTextField(
                        value = maxConnPerTorrent,
                        onValueChange = { maxConnPerTorrent = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Max Conn/Torrent") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                OutlinedTextField(
                    value = dhtNodes,
                    onValueChange = { dhtNodes = it },
                    label = { Text("DHT Bootstrap Nodes") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Test Connection Button
                OutlinedButton(
                    onClick = {
                        isTestingConnection = true
                        testResultMsg = "Testing connection to bootstrap nodes..."
                        onTestConnection { success, message ->
                            isTestingConnection = false
                            testResultMsg = message
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isTestingConnection) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text("Test Connection")
                }

                testResultMsg?.let { res ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = res,
                        fontSize = 11.sp,
                        color = if (res.contains("Successfully")) AccentGreen else AccentYellow
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val portNum = listenPort.toIntOrNull() ?: 6881
                    val dlNum = maxDlSpeed.toIntOrNull() ?: 0
                    val ulNum = maxUlSpeed.toIntOrNull() ?: 50
                    val connNum = maxConn.toIntOrNull() ?: 60
                    val connPerTorrentNum = maxConnPerTorrent.toIntOrNull() ?: 25

                    val updated = currentSettings.copy(
                        downloadDirectory = downloadDir,
                        stopSeedingOnComplete = stopSeeding,
                        autoRemoveOnComplete = autoRemove,
                        enableSequentialVideo = sequentialVideo,
                        listenPort = portNum.coerceIn(1, 65535),
                        maxConnections = connNum.coerceIn(1, 10000),
                        maxConnectionsPerTorrent = connPerTorrentNum.coerceIn(1, 5000),
                        maxDownloadSpeedKbps = dlNum.coerceAtLeast(0),
                        maxUploadSpeedKbps = ulNum.coerceAtLeast(0),
                        dhtBootstrapNodes = dhtNodes,
                        enableDht = enableDht,
                        enableLsd = enableLsd,
                        onlyOnWifi = onlyWifi
                    )
                    onSave(updated)
                },
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan, contentColor = DarkBg)
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
