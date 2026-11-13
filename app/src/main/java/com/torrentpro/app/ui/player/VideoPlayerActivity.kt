package com.torrentpro.app.ui.player

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.torrentpro.app.data.repository.TorrentRepository
import java.io.File

class VideoPlayerActivity : ComponentActivity() {

    private var exoPlayer: ExoPlayer? = null

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL) ?: ""
        val videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: "Video Player"
        val infoHash = intent.getStringExtra(EXTRA_INFO_HASH)

        if (videoUrl.isEmpty()) {
            Toast.makeText(this, "No video source available", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Prioritize pieces for streaming if torrent is still active
        if (!infoHash.isNullOrEmpty()) {
            TorrentRepository.getInstance(this).engine.prioritizeForStreaming(infoHash)
        }

        setContent {
            MaterialTheme {
                VideoPlayerScreen(
                    videoTitle = videoTitle,
                    videoUrl = videoUrl,
                    onBack = { finish() },
                    onOpenExternal = { openInExternalPlayer(videoUrl) }
                )
            }
        }
    }

    private fun openInExternalPlayer(url: String) {
        try {
            val uri = if (url.startsWith("http")) {
                Uri.parse(url)
            } else {
                Uri.fromFile(File(url))
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Play with video player"))
        } catch (e: Exception) {
            Toast.makeText(this, "No external video player found", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPause() {
        super.onPause()
        exoPlayer?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }

    @OptIn(UnstableApi::class)
    @Composable
    private fun VideoPlayerScreen(
        videoTitle: String,
        videoUrl: String,
        onBack: () -> Unit,
        onOpenExternal: () -> Unit
    ) {
        val context = LocalContext.current
        var errorMessage by remember { mutableStateOf<String?>(null) }
        var isBuffering by remember { mutableStateOf(true) }

        val player = remember {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    1500,  // minBufferMs
                    10000, // maxBufferMs
                    1000,  // bufferForPlaybackMs
                    2000   // bufferForPlaybackAfterRebufferMs
                )
                .build()

            ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setSeekForwardIncrementMs(10000)
                .setSeekBackIncrementMs(10000)
                .build().apply {
                    val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
                    setMediaItem(mediaItem)
                    prepare()
                    playWhenReady = true

                    addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            isBuffering = (playbackState == Player.STATE_BUFFERING)
                        }

                        override fun onPlayerError(error: PlaybackException) {
                            errorMessage = "Streaming buffer waiting for torrent pieces...\n(${error.localizedMessage ?: "Buffering"})"
                        }
                    })
                }.also {
                    exoPlayer = it
                }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                        setShowFastForwardButton(true)
                        setShowRewindButton(true)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Top Bar Overlay
            Surface(
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
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

                    IconButton(onClick = onOpenExternal) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "External Player",
                            tint = Color.Cyan
                        )
                    }
                }
            }

            // Buffering indicator
            if (isBuffering && errorMessage == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Color.Cyan)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Buffering torrent stream...",
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Error message overlay
            errorMessage?.let { msg ->
                Surface(
                    color = Color.Black.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = msg,
                            color = Color(0xFFFF8A9E),
                            style = MaterialTheme.typography.bodyMedium
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
        }
    }

    companion object {
        const val EXTRA_VIDEO_URL = "extra_video_url"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
        const val EXTRA_INFO_HASH = "extra_info_hash"
    }
}
