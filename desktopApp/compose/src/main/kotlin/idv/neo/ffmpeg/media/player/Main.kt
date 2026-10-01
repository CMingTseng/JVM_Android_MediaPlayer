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
            val ffmpegOptions = getPlatformHardwareDecoderOption()
            JavaCvPlayer.create(
                videoSink = videoSink,
                audioSink = audioSink,
                options = ffmpegOptions
            ).apply {
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

/**
 * 根據 OS 平台自動選擇對應的硬體加速解碼器：
 * - macOS: VideoToolbox ("h264_videotoolbox")
 * - Windows: NVIDIA NVDEC ("h264_nvdec")
 * - Linux / Intel: QuickSync ("h264_qsv")
 */
private fun getPlatformHardwareDecoderOption(): Map<String, String> {
    val osName = System.getProperty("os.name", "").lowercase()
    val vcodec = when {
        osName.contains("mac") || osName.contains("darwin") -> "h264_videotoolbox"
        osName.contains("win") -> "h264_nvdec"
        osName.contains("nux") || osName.contains("nix") -> "h264_qsv"
        else -> null
    }
    return if (vcodec != null) mapOf("vcodec" to vcodec) else emptyMap()
}
