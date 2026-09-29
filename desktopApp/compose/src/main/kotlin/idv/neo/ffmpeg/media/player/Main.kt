package idv.neo.ffmpeg.media.player

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.media3.common.MediaItem
import idv.neo.ffmpeg.media.player.core.JavaCvPlayer
import idv.neo.ffmpeg.media.player.core.audio.JvmAudioSink
import idv.neo.ffmpeg.media.player.core.video.skia.SkiaVideoSink

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "JavaCvPlayer Demo - Multi-Stream") {

        val player = remember {
            val videoSink = SkiaVideoSink()
            val audioSink = JvmAudioSink()
            JavaCvPlayer.create(videoSink, audioSink).apply {
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
        App(player)
    }
}
