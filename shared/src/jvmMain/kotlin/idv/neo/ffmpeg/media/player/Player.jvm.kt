package idv.neo.ffmpeg.media.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import idv.neo.ffmpeg.media.player.core.JvmJavaCvPlayer
import idv.neo.ffmpeg.media.player.core.video.skia.SkiaVideoSink

@Composable
actual fun rememberPlayer(): Player {
    val player = remember {
        val videoSink = SkiaVideoSink()
        JvmJavaCvPlayer.create(videoSink).apply {
            setMediaItem(MediaItem.fromUri("https://github.com/rambod-rahmani/ffmpeg-video-player/raw/refs/heads/master/Iron_Man-Trailer_HD.mp4"))
            prepare()
            play()
        }
    }
    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }
    return player
}
