package com.torrentpro.app.ui.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.torrentpro.app.data.engine.StorageHelper
import com.torrentpro.app.data.repository.TorrentRepository
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

class VideoPlayerActivity : ComponentActivity() {

    private var exoPlayer: ExoPlayer? = null
    private var noisyAudioReceiver: BroadcastReceiver? = null
    private var originalBrightness: Float = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
    private var lastSavedPositionMs: Long = 0L

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        originalBrightness = window.attributes.screenBrightness

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            android.util.Log.e("VideoPlayerActivity", "Uncaught video player error", throwable)
            runOnUiThread {
                Toast.makeText(applicationContext, "Video stream error: ${throwable.localizedMessage ?: "Playback failed"}", Toast.LENGTH_SHORT).show()
                finish()
            }
        }

        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: ""
        val videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: "Video Player"
        val infoHash = intent.getStringExtra(EXTRA_INFO_HASH)

        if (videoUrl.isBlank()) {
            Toast.makeText(this, "No video source available to stream", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Prioritize pieces for streaming if torrent is still active
        if (!infoHash.isNullOrEmpty()) {
            TorrentRepository.getInstance(this).engine.prioritizeForStreaming(infoHash)
        }

        registerNoisyAudioReceiver()

        setContent {
            MaterialTheme {
                VideoPlayerScreen(
                    videoTitle = videoTitle,
                    videoUrl = videoUrl,
                    onBack = { finish() },
                    onOpenExternal = { openInExternalPlayer(videoUrl) },
                    onBrightnessChange = { adjustBrightness(it) },
                    onToggleOrientation = { toggleOrientation() }
                )
            }
        }
    }

    private fun registerNoisyAudioReceiver() {
        try {
            noisyAudioReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                        exoPlayer?.pause()
                    }
                }
            }
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            androidx.core.content.ContextCompat.registerReceiver(
                this,
                noisyAudioReceiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            android.util.Log.w("VideoPlayerActivity", "Could not register noisy receiver: ${e.message}")
        }
    }

    private fun adjustBrightness(delta: Float) {
        val lp = window.attributes
        var current = if (lp.screenBrightness < 0f) 0.5f else lp.screenBrightness
        current = (current + delta).coerceIn(0.01f, 1.0f)
        lp.screenBrightness = current
        window.attributes = lp
    }

    private fun toggleOrientation() {
        requestedOrientation = if (requestedOrientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun openInExternalPlayer(url: String) {
        try {
            val uri: Uri = if (url.startsWith("http")) {
                Uri.parse(url)
            } else {
                val file = File(url)
                if (file.exists()) {
                    StorageHelper.getSharableUri(this, file)
                } else {
                    Uri.parse(url)
                }
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Play with external video player"))
        } catch (e: Exception) {
            Toast.makeText(this, "No external video player found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        lastSavedPositionMs = exoPlayer?.currentPosition ?: 0L
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        noisyAudioReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        val lp = window.attributes
        lp.screenBrightness = originalBrightness
        window.attributes = lp

        exoPlayer?.release()
        exoPlayer = null
    }

    @OptIn(UnstableApi::class)
    @Composable
    private fun VideoPlayerScreen(
        videoTitle: String,
        videoUrl: String,
        onBack: () -> Unit,
        onOpenExternal: () -> Unit,
        onBrightnessChange: (Float) -> Unit,
        onToggleOrientation: () -> Unit
    ) {
        val context = LocalContext.current
        val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

        var isPlaying by remember { mutableStateOf(true) }
        var isBuffering by remember { mutableStateOf(true) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var currentPositionMs by remember { mutableLongStateOf(0L) }
        var totalDurationMs by remember { mutableLongStateOf(0L) }
        var controlsVisible by remember { mutableStateOf(true) }

        // Double-tap seek visual indicators
        var doubleTapRewindActive by remember { mutableStateOf(false) }
        var doubleTapForwardActive by remember { mutableStateOf(false) }

        // Swipe HUD indicators
        var gestureVolumePercent by remember { mutableStateOf<Int?>(null) }
        var gestureBrightnessPercent by remember { mutableStateOf<Int?>(null) }

        // Track & Speed Dialog states
        var showAudioDialog by remember { mutableStateOf(false) }
        var showSubtitleDialog by remember { mutableStateOf(false) }
        var showSpeedDialog by remember { mutableStateOf(false) }
        var currentSpeed by remember { mutableFloatStateOf(1.0f) }

        var availableTracks by remember { mutableStateOf<Tracks?>(null) }

        val player = remember {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    1500,  // minBufferMs
                    12000, // maxBufferMs
                    1000,  // bufferForPlaybackMs
                    2000   // bufferForPlaybackAfterRebufferMs
                )
                .build()

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .setUsage(C.USAGE_MEDIA)
                .build()

            ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setAudioAttributes(audioAttributes, true) // Handles audio focus automatically
                .setSeekForwardIncrementMs(10000)
                .setSeekBackIncrementMs(10000)
                .build().apply {
                    val mediaUri = when {
                        videoUrl.startsWith("http://", ignoreCase = true) ||
                        videoUrl.startsWith("https://", ignoreCase = true) ||
                        videoUrl.startsWith("content://", ignoreCase = true) ||
                        videoUrl.startsWith("file://", ignoreCase = true) -> Uri.parse(videoUrl)
                        else -> Uri.fromFile(File(videoUrl))
                    }
                    val mediaItem = MediaItem.fromUri(mediaUri)
                    setMediaItem(mediaItem)
                    if (lastSavedPositionMs > 0) {
                        seekTo(lastSavedPositionMs)
                    }
                    prepare()
                    playWhenReady = true

                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            isBuffering = (playbackState == Player.STATE_BUFFERING)
                            if (playbackState == Player.STATE_READY) {
                                totalDurationMs = duration.coerceAtLeast(0L)
                                errorMessage = null
                            }
                        }

                        override fun onIsPlayingChanged(playing: Boolean) {
                            isPlaying = playing
                        }

                        override fun onTracksChanged(tracks: Tracks) {
                            availableTracks = tracks
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            val msg = when (error.errorCode) {
                                PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ->
                                    "Torrent stream piece waiting on network..."
                                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
                                    "Video container format initializing..."
                                else -> "Streaming buffer waiting for torrent pieces...\n(${error.localizedMessage ?: "Connecting"})"
                            }
                            errorMessage = msg
                        }
                    })
                }.also {
                    exoPlayer = it
                }
        }

        // Timeline position update loop
        LaunchedEffect(player) {
            while (true) {
                if (player.isPlaying) {
                    currentPositionMs = player.currentPosition.coerceAtLeast(0L)
                    totalDurationMs = player.duration.coerceAtLeast(0L)
                }
                delay(500)
            }
        }

        // Auto-hide controls timer
        LaunchedEffect(controlsVisible, isPlaying) {
            if (controlsVisible && isPlaying) {
                delay(4500)
                controlsVisible = false
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .pointerInput(Unit) {
                    // Detect taps & double-taps
                    detectTapGestures(
                        onTap = {
                            controlsVisible = !controlsVisible
                            gestureVolumePercent = null
                            gestureBrightnessPercent = null
                        },
                        onDoubleTap = { offset ->
                            val halfWidth = size.width / 2
                            if (offset.x < halfWidth) {
                                // Rewind 10s
                                val newPos = (player.currentPosition - 10000).coerceAtLeast(0L)
                                player.seekTo(newPos)
                                doubleTapRewindActive = true
                            } else {
                                // Fast-forward 10s
                                val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                                val newPos = (player.currentPosition + 10000).coerceAtMost(dur)
                                player.seekTo(newPos)
                                doubleTapForwardActive = true
                            }
                        }
                    )
                }
                .pointerInput(Unit) {
                    // Vertical drag for volume (right side) and brightness (left side)
                    detectDragGestures(
                        onDragEnd = {
                            gestureVolumePercent = null
                            gestureBrightnessPercent = null
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val isLeftSide = change.position.x < (size.width / 2)
                            val delta = -dragAmount.y / size.height

                            if (isLeftSide) {
                                onBrightnessChange(delta)
                                val currentLp = (context as? ComponentActivity)?.window?.attributes?.screenBrightness ?: 0.5f
                                val pct = (if (currentLp < 0f) 50 else (currentLp * 100).toInt()).coerceIn(1, 100)
                                gestureBrightnessPercent = pct
                                gestureVolumePercent = null
                            } else {
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val step = if (delta > 0) 1 else if (delta < 0) -1 else 0
                                if (step != 0) {
                                    val newVol = (currentVol + step).coerceIn(0, maxVol)
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                    gestureVolumePercent = (newVol * 100 / maxVol)
                                }
                                gestureBrightnessPercent = null
                            }
                        }
                    )
                }
        ) {
            // Player Surface
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = false // Custom Compose controls used
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Double Tap Rewind Animation (-10s)
            if (doubleTapRewindActive) {
                LaunchedEffect(Unit) {
                    delay(700)
                    doubleTapRewindActive = false
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.45f)
                        .align(Alignment.CenterStart)
                        .background(Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(topEnd = 100.dp, bottomEnd = 100.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Replay10, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                        Text("-10s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }

            // Double Tap Forward Animation (+10s)
            if (doubleTapForwardActive) {
                LaunchedEffect(Unit) {
                    delay(700)
                    doubleTapForwardActive = false
                }
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.45f)
                        .align(Alignment.CenterEnd)
                        .background(Color.White.copy(alpha = 0.12f), shape = RoundedCornerShape(topStart = 100.dp, bottomStart = 100.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Forward10, contentDescription = null, tint = Color.White, modifier = Modifier.size(48.dp))
                        Text("+10s", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }

            // Volume HUD Indicator
            gestureVolumePercent?.let { vol ->
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Icon(
                            imageVector = if (vol == 0) Icons.Default.VolumeMute else Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = Color.Cyan,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Volume: $vol%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }

            // Brightness HUD Indicator
            gestureBrightnessPercent?.let { bri ->
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.BrightnessMedium, contentDescription = null, tint = Color(0xFFFFD166), modifier = Modifier.size(28.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Brightness: $bri%", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }

            // Buffering Indicator
            if (isBuffering && errorMessage == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.Cyan)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Buffering stream (Downloading pieces)...",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Error Overlay
            errorMessage?.let { msg ->
                Surface(
                    color = Color.Black.copy(alpha = 0.88f),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = Color(0xFFFF5370), modifier = Modifier.size(40.dp))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = msg,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Row {
                            Button(onClick = {
                                errorMessage = null
                                player.prepare()
                                player.play()
                            }) {
                                Text("Retry")
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            OutlinedButton(onClick = onOpenExternal) {
                                Text("Try VLC / External")
                            }
                        }
                    }
                }
            }

            // Animated Controller Overlay
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(animationSpec = tween(200)),
                exit = fadeOut(animationSpec = tween(200)),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.5f))
                ) {
                    // Top App Bar Controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .padding(horizontal = 8.dp, vertical = 6.dp)
                            .align(Alignment.TopCenter),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                        }

                        Text(
                            text = videoTitle,
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 8.dp)
                        )

                        // Audio Track Selector
                        IconButton(onClick = { showAudioDialog = true }) {
                            Icon(Icons.Default.Audiotrack, contentDescription = "Audio Tracks", tint = Color.White)
                        }

                        // Subtitle Selector
                        IconButton(onClick = { showSubtitleDialog = true }) {
                            Icon(Icons.Default.Subtitles, contentDescription = "Subtitles", tint = Color.White)
                        }

                        // Playback Speed
                        IconButton(onClick = { showSpeedDialog = true }) {
                            Icon(Icons.Default.Speed, contentDescription = "Speed", tint = Color.White)
                        }

                        // External Player
                        IconButton(onClick = onOpenExternal) {
                            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "External Player", tint = Color.Cyan)
                        }
                    }

                    // Center Play/Pause & Quick Seek Controls
                    Row(
                        modifier = Modifier.align(Alignment.Center),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(28.dp)
                    ) {
                        IconButton(
                            onClick = {
                                val newPos = (player.currentPosition - 10000).coerceAtLeast(0L)
                                player.seekTo(newPos)
                            },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", tint = Color.White, modifier = Modifier.size(36.dp))
                        }

                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color.Cyan),
                            contentAlignment = Alignment.Center
                        ) {
                            IconButton(
                                onClick = {
                                    if (player.isPlaying) player.pause() else player.play()
                                },
                                modifier = Modifier.size(64.dp)
                            ) {
                                Icon(
                                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                    contentDescription = if (isPlaying) "Pause" else "Play",
                                    tint = Color.Black,
                                    modifier = Modifier.size(40.dp)
                                )
                            }
                        }

                        IconButton(
                            onClick = {
                                val dur = if (player.duration > 0) player.duration else Long.MAX_VALUE
                                val newPos = (player.currentPosition + 10000).coerceAtMost(dur)
                                player.seekTo(newPos)
                            },
                            modifier = Modifier.size(52.dp)
                        ) {
                            Icon(Icons.Default.Forward10, contentDescription = "Forward 10s", tint = Color.White, modifier = Modifier.size(36.dp))
                        }
                    }

                    // Bottom Seekbar & Time Row
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = formatDuration(currentPositionMs),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )

                            val safeDuration = maxOf(1f, totalDurationMs.toFloat())
                            val safePosition = currentPositionMs.toFloat().coerceIn(0f, safeDuration)
                            Slider(
                                value = safePosition,
                                onValueChange = { newPos ->
                                    currentPositionMs = newPos.toLong()
                                    player.seekTo(currentPositionMs)
                                },
                                valueRange = 0f..safeDuration,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 12.dp),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Cyan,
                                    activeTrackColor = Color.Cyan,
                                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                                )
                            )

                            Text(
                                text = formatDuration(totalDurationMs),
                                color = Color.White,
                                fontSize = 12.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                            )

                            Spacer(modifier = Modifier.width(8.dp))

                            IconButton(
                                onClick = onToggleOrientation,
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate", tint = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // Subtitle Track Selection Dialog
        if (showSubtitleDialog) {
            val textTracks = remember(availableTracks) {
                val list = mutableListOf<Pair<TrackGroup, Int>>()
                availableTracks?.groups?.forEach { group ->
                    if (group.type == C.TRACK_TYPE_TEXT) {
                        for (i in 0 until group.length) {
                            list.add(group.mediaTrackGroup to i)
                        }
                    }
                }
                list
            }

            AlertDialog(
                onDismissRequest = { showSubtitleDialog = false },
                title = { Text("Select Subtitles", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        TextButton(
                            onClick = {
                                player.trackSelectionParameters = player.trackSelectionParameters
                                    .buildUpon()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                                    .build()
                                showSubtitleDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Disable Subtitles (Off)", color = Color(0xFFFF5370))
                        }
                        HorizontalDivider()

                        if (textTracks.isEmpty()) {
                            Text("No subtitle tracks found in stream", color = Color.Gray, modifier = Modifier.padding(12.dp))
                        } else {
                            textTracks.forEach { (group, trackIndex) ->
                                val format = group.getFormat(trackIndex)
                                val lang = format.language?.let { Locale(it).displayLanguage } ?: "Track ${trackIndex + 1}"
                                val label = format.label ?: lang
                                TextButton(
                                    onClick = {
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                                            .setOverrideForType(TrackSelectionOverride(group, trackIndex))
                                            .build()
                                        showSubtitleDialog = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSubtitleDialog = false }) { Text("Close") }
                }
            )
        }

        // Audio Track Selection Dialog
        if (showAudioDialog) {
            val audioTracks = remember(availableTracks) {
                val list = mutableListOf<Pair<TrackGroup, Int>>()
                availableTracks?.groups?.forEach { group ->
                    if (group.type == C.TRACK_TYPE_AUDIO) {
                        for (i in 0 until group.length) {
                            list.add(group.mediaTrackGroup to i)
                        }
                    }
                }
                list
            }

            AlertDialog(
                onDismissRequest = { showAudioDialog = false },
                title = { Text("Select Audio Track", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        if (audioTracks.isEmpty()) {
                            Text("No alternate audio tracks available", color = Color.Gray, modifier = Modifier.padding(12.dp))
                        } else {
                            audioTracks.forEach { (group, trackIndex) ->
                                val format = group.getFormat(trackIndex)
                                val lang = format.language?.let { Locale(it).displayLanguage } ?: "Track ${trackIndex + 1}"
                                val label = if (!format.label.isNullOrBlank()) "${format.label} ($lang)" else lang
                                TextButton(
                                    onClick = {
                                        player.trackSelectionParameters = player.trackSelectionParameters
                                            .buildUpon()
                                            .setOverrideForType(TrackSelectionOverride(group, trackIndex))
                                            .build()
                                        showAudioDialog = false
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(label)
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showAudioDialog = false }) { Text("Close") }
                }
            )
        }

        // Playback Speed Selection Dialog
        if (showSpeedDialog) {
            val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
            AlertDialog(
                onDismissRequest = { showSpeedDialog = false },
                title = { Text("Playback Speed", fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        speeds.forEach { speed ->
                            TextButton(
                                onClick = {
                                    currentSpeed = speed
                                    player.setPlaybackSpeed(speed)
                                    showSpeedDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "${speed}x" + if (speed == currentSpeed) " (Current)" else "",
                                    fontWeight = if (speed == currentSpeed) FontWeight.Bold else FontWeight.Normal,
                                    color = if (speed == currentSpeed) Color.Cyan else Color.White
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSpeedDialog = false }) { Text("Close") }
                }
            )
        }
    }

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "00:00"
        val totalSecs = ms / 1000
        val s = totalSecs % 60
        val m = (totalSecs / 60) % 60
        val h = totalSecs / 3600
        return if (h > 0) {
            String.format("%02d:%02d:%02d", h, m, s)
        } else {
            String.format("%02d:%02d", m, s)
        }
    }

    companion object {
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_INFO_HASH = "extra_info_hash"
    }
}
