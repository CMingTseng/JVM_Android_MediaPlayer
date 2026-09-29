package idv.neo.ffmpeg.media.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

@Composable
actual fun rememberPlayer(): Player {
    val context = LocalContext.current
    val player = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri("https://github.com/rambod-rahmani/ffmpeg-video-player/raw/refs/heads/master/Iron_Man-Trailer_HD.mp4"))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }
    return player
}
