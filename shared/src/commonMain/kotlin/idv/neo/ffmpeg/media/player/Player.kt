package idv.neo.ffmpeg.media.player

import androidx.compose.runtime.Composable
import androidx.media3.common.Player

@Composable
expect fun rememberPlayer(): Player
