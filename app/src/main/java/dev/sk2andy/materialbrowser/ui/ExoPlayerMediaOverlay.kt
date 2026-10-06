package dev.sk2andy.materialbrowser.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

data class VideoStreamPayload(
    val url: String,
    val title: String? = null,
    val referer: String? = null,
    val userAgent: String? = null,
    val headers: Map<String, String> = emptyMap(),
)

@OptIn(UnstableApi::class)
@Composable
fun ExoPlayerMediaOverlay(
    payload: VideoStreamPayload,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()

    // Player state
    var isPlaying by remember { mutableStateOf(true) }
    var currentPositionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var resizeMode by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }

    // UI overlays & gesture indicators
    var showControls by remember { mutableStateOf(true) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var gestureIndicatorText by remember { mutableStateOf<String?>(null) }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }

    // Initialize ExoPlayer with custom HTTP headers (Referer, User-Agent)
    val exoPlayer = remember(payload.url) {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory().apply {
            payload.userAgent?.let { setUserAgent(it) }
            val customHeaders = payload.headers.toMutableMap()
            payload.referer?.let { customHeaders["Referer"] = it }
            if (customHeaders.isNotEmpty()) {
                setDefaultRequestProperties(customHeaders)
            }
        }

        val mediaSourceFactory: MediaSource.Factory = when {
            payload.url.contains(".m3u8", ignoreCase = true) -> {
                HlsMediaSource.Factory(httpDataSourceFactory)
            }
            payload.url.contains(".mpd", ignoreCase = true) -> {
                DashMediaSource.Factory(httpDataSourceFactory)
            }
            else -> {
                DefaultMediaSourceFactory(context).setDataSourceFactory(httpDataSourceFactory)
            }
        }

        val mediaItem = MediaItem.fromUri(payload.url)
        val mediaSource = mediaSourceFactory.createMediaSource(mediaItem)

        ExoPlayer.Builder(context).build().apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onIsPlayingChanged(playing: Boolean) {
                    isPlaying = playing
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    durationMs = duration.coerceAtLeast(0L)
                }
            })
        }
    }

    // Auto-update position tick
    LaunchedEffect(exoPlayer, isPlaying) {
        while (isPlaying) {
            currentPositionMs = exoPlayer.currentPosition
            durationMs = exoPlayer.duration.coerceAtLeast(0L)
            delay(500)
        }
    }

    // Auto-hide controls
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(4000)
            showControls = false
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    BackHandler {
        onDismiss()
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        showControls = !showControls
                    },
                    onDoubleTap = { offset ->
                        val isLeft = offset.x < size.width / 2
                        val seekDelta = if (isLeft) -10000L else 10000L
                        val newPos = (exoPlayer.currentPosition + seekDelta).coerceIn(0L, exoPlayer.duration.coerceAtLeast(0L))
                        exoPlayer.seekTo(newPos)
                        gestureIndicatorText = if (isLeft) "-10s" else "+10s"
                        scope.launch {
                            delay(1000)
                            gestureIndicatorText = null
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                var totalDragX = 0f
                var totalDragY = 0f
                var startX = 0f

                detectDragGestures(
                    onDragStart = { offset ->
                        totalDragX = 0f
                        totalDragY = 0f
                        startX = offset.x
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        totalDragX += dragAmount.x
                        totalDragY += dragAmount.y

                        if (abs(totalDragY) > abs(totalDragX)) {
                            // Vertical swipe: Left side = Brightness, Right side = Volume
                            val isLeft = startX < size.width / 2
                            if (isLeft && activity != null) {
                                val currentBrightness = activity.window.attributes.screenBrightness.let {
                                    if (it < 0) 0.5f else it
                                }
                                val delta = -dragAmount.y / size.height
                                val newBrightness = (currentBrightness + delta).coerceIn(0.01f, 1.0f)
                                val layoutParams = activity.window.attributes
                                layoutParams.screenBrightness = newBrightness
                                activity.window.attributes = layoutParams
                                gestureIndicatorText = "Brightness: ${(newBrightness * 100).roundToInt()}%"
                            } else {
                                val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                                val delta = (-dragAmount.y / size.height * maxVolume).roundToInt()
                                val newVol = (currentVol + delta).coerceIn(0, maxVolume)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, 0)
                                gestureIndicatorText = "Volume: ${(newVol * 100 / maxVolume)}%"
                            }
                        } else {
                            // Horizontal swipe: Seek
                            val seekRatio = totalDragX / size.width
                            val seekTimeDelta = (seekRatio * 60000).toLong() // up to 60s per swipe
                            val targetPos = (exoPlayer.currentPosition + seekTimeDelta).coerceIn(0L, exoPlayer.duration.coerceAtLeast(0L))
                            gestureIndicatorText = "Seek: ${formatTime(targetPos)}"
                        }
                    },
                    onDragEnd = {
                        scope.launch {
                            delay(1000)
                            gestureIndicatorText = null
                        }
                    }
                )
            }
    ) {
        // Player Surface View
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    useController = false
                    this.player = exoPlayer
                    this.resizeMode = resizeMode
                }
            },
            update = { playerView ->
                playerView.player = exoPlayer
                playerView.resizeMode = resizeMode
            },
            modifier = Modifier.fillMaxSize()
        )

        // Gesture Feedback Indicator (Floating Center Capsule)
        gestureIndicatorText?.let { text ->
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp)
            ) {
                Text(
                    text = text,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)
                )
            }
        }

        // Overlay Controls
        AnimatedVisibility(
            visible = showControls,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.45f))
            ) {
                // Top Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Close", tint = Color.White)
                    }

                    Text(
                        text = payload.title ?: "Streaming Media",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                    )

                    Row {
                        // External Player Intent Button
                        IconButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(Uri.parse(payload.url), "video/*")
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(Intent.createChooser(intent, "Open in External Player"))
                        }) {
                            Icon(Icons.Default.OpenInNew, contentDescription = "Open Externally", tint = Color.White)
                        }

                        // External Downloader Intent Button
                        IconButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse(payload.url)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(Intent.createChooser(intent, "Download with..."))
                        }) {
                            Icon(Icons.Default.Download, contentDescription = "Download Video", tint = Color.White)
                        }

                        // PiP Button
                        IconButton(onClick = {
                            activity?.enterPictureInPictureMode()
                        }) {
                            Icon(Icons.Default.PictureInPicture, contentDescription = "Picture in Picture", tint = Color.White)
                        }

                        // Speed Dialog Button
                        IconButton(onClick = { showSpeedDialog = true }) {
                            Icon(Icons.Default.Speed, contentDescription = "Speed", tint = Color.White)
                        }
                    }
                }

                // Center Play/Pause & Skip Controls
                Row(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = {
                            val newPos = (exoPlayer.currentPosition - 10000L).coerceAtLeast(0L)
                            exoPlayer.seekTo(newPos)
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.Replay10, contentDescription = "-10s", tint = Color.White, modifier = Modifier.size(40.dp))
                    }

                    IconButton(
                        onClick = {
                            if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
                        },
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.2f))
                    ) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(48.dp)
                        )
                    }

                    IconButton(
                        onClick = {
                            val newPos = (exoPlayer.currentPosition + 10000L).coerceAtMost(exoPlayer.duration)
                            exoPlayer.seekTo(newPos)
                        },
                        modifier = Modifier.size(56.dp)
                    ) {
                        Icon(Icons.Default.Forward10, contentDescription = "+10s", tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                }

                // Bottom Timeline and Time Labels
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(text = formatTime(currentPositionMs), color = Color.White, style = MaterialTheme.typography.bodySmall)
                        Text(text = formatTime(durationMs), color = Color.White, style = MaterialTheme.typography.bodySmall)
                    }

                    Slider(
                        value = if (durationMs > 0) currentPositionMs.toFloat() / durationMs.toFloat() else 0f,
                        onValueChange = { ratio ->
                            val target = (ratio * durationMs).toLong()
                            exoPlayer.seekTo(target)
                        },
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        // Playback Speed Selector Dialog
        if (showSpeedDialog) {
            AlertDialog(
                onDismissRequest = { showSpeedDialog = false },
                title = { Text("Playback Speed") },
                text = {
                    Column {
                        listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { speed ->
                            TextButton(
                                onClick = {
                                    playbackSpeed = speed
                                    exoPlayer.playbackParameters = PlaybackParameters(speed)
                                    showSpeedDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("${speed}x", style = if (playbackSpeed == speed) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSpeedDialog = false }) {
                        Text("Done")
                    }
                }
            )
        }
    }
}

private fun formatTime(millis: Long): String {
    if (millis <= 0) return "00:00"
    val seconds = (millis / 1000) % 60
    val minutes = (millis / (1000 * 60)) % 60
    val hours = millis / (1000 * 60 * 60)
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
