package idv.neo.ffmpeg.media.player

import androidx.compose.runtime.Composable
import androidx.media3.common.Player

@Composable
actual fun rememberPlayer(): Player {
    error("iOS Player is not implemented yet")
}
