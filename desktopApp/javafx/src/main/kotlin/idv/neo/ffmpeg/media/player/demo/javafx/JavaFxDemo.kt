package idv.neo.ffmpeg.media.player.demo.javafx

import idv.neo.ffmpeg.media.player.core.JvmJavaCvPlayer
import idv.neo.ffmpeg.media.player.core.audio.JvmAudioSink
import idv.neo.ffmpeg.media.player.core.video.javafx.JavaFxPixelBufferVideoSink
import androidx.media3.common.MediaItem
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import java.io.File

class JavaFxDemo : Application() {

    private lateinit var player: JvmJavaCvPlayer

    override fun start(primaryStage: Stage) {
        // 1. 手動建立專為 JavaFX 優化的 VideoSink (來自 :core-video-javafx)
        val videoSink = JavaFxPixelBufferVideoSink()
        
        // 2. 根據作業系統自動選擇硬體解碼器 (若該晶片/設備不支援，FFmpegFrameLoader 將自動 Fallback 至軟解)
        val ffmpegOptions = getPlatformHardwareDecoderOption()
        
        // 3. 將 Sink 與選項注入播放器
        player = JvmJavaCvPlayer.create(
            videoSink = videoSink,
            audioSink = JvmAudioSink(),
            options = ffmpegOptions
        )

        // 3. 從 Sink 取得 UI 組件 (StackPane 封裝了 ImageView)
        val videoView = videoSink.getView(null) as Pane
        
        val root = StackPane(videoView)
        val scene = Scene(root, 1280.0, 720.0)
        
        primaryStage.title = "JavaCvPlayer JavaFX Modular Architecture Demo"
        primaryStage.scene = scene
        primaryStage.show()

        val videoFile = File("sample.mp4")
        val path = if (videoFile.exists()) {
            videoFile.absolutePath
        } else {
            "https://github.com/rambod-rahmani/ffmpeg-video-player/raw/refs/heads/master/Iron_Man-Trailer_HD.mp4"
        }

        player.setMediaItem(MediaItem.fromUri(path))
        player.prepare()
        player.play()
    }

    override fun stop() {
        player.release()
        super.stop()
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
}

fun main(args: Array<String>) {
    Application.launch(JavaFxDemo::class.java, *args)
}
