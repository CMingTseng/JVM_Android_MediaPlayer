package idv.neo.ffmpeg.media.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.compose.material3.buttons.NextButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.media3.ui.compose.material3.buttons.SeekBackButton
import androidx.media3.ui.compose.material3.buttons.SeekForwardButton
import androidx.media3.ui.compose.material3.indicator.PositionAndDurationText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import idv.neo.ffmpeg.media.player.core.BaseJavaCvPlayer
import kotlinx.coroutines.delay

/**
 * A playback control view that interacts with a [Player].
 * Aligned with Media3's Material3 implementation.
 */
@UnstableApi
@Composable
fun PlayerControlView(
    player: Player,
    modifier: Modifier = Modifier,
    isVisible: Boolean = true,
    onVisibilityChange: (Boolean) -> Unit = {}
) {
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var playbackState by remember { mutableIntStateOf(player.playbackState) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
            }
            override fun onIsPlayingChanged(isPlayingParam: Boolean) {
                isPlaying = isPlayingParam
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    // Auto-hide logic
    LaunchedEffect(isVisible, isPlaying, playbackState) {
        if (isVisible && isPlaying && playbackState == Player.STATE_READY) {
            delay(5000)
            onVisibilityChange(false)
        }
    }

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    onVisibilityChange(false)
                }
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 16.dp)
                    .clickable(enabled = false) {} // Prevent click-through
            ) {
                // Time Bar / Progress Slider
                @OptIn(UnstableApi::class)
                    ProgressSlider(
                    player = player,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Time Labels
                    CompositionLocalProvider(LocalContentColor provides Color.White) {
                        @OptIn(UnstableApi::class)
                        PositionAndDurationText(
                            player = player
                        )
                    }

                    // Central Controls
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        @OptIn(UnstableApi::class)
                        PreviousButton(player = player)
                        @OptIn(UnstableApi::class)
                        SeekBackButton(player = player)
                        
                        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(64.dp)) {
                            if (playbackState == Player.STATE_BUFFERING) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                @OptIn(UnstableApi::class)
                                PlayPauseButton(player = player)
                            }
                        }

                        IconButton(onClick = { player.stop() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Stop", tint = Color.White)
                        }

                        @OptIn(UnstableApi::class)
                        SeekForwardButton(player = player)
                        @OptIn(UnstableApi::class)
                        NextButton(player = player)
                    }

                    // Settings / Resize Mode
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (player is BaseJavaCvPlayer<*, *>) {
                            IconButton(onClick = {
                                val nextMode = (player.resizeMode + 1) % 3
                                player.resizeMode = nextMode
                            }) {
                                Icon(
                                    imageVector = when (player.resizeMode) {
                                        1 -> Icons.Default.Fullscreen // FILL
                                        2 -> Icons.Default.ZoomIn     // ZOOM
                                        else -> Icons.Default.AspectRatio // FIT
                                    },
                                    contentDescription = "Resize Mode",
                                    tint = Color.White
                                )
                            }
                        }
                        IconButton(onClick = { /* Settings */ }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}
