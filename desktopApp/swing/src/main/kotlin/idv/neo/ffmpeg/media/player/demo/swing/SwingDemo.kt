package idv.neo.ffmpeg.media.player.demo.swing

import idv.neo.ffmpeg.media.player.core.JvmJavaCvPlayer
import idv.neo.ffmpeg.media.player.core.audio.JvmAudioSink
import idv.neo.ffmpeg.media.player.core.video.swing.JvmVideoSink
import androidx.media3.common.MediaItem
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Component
import javax.swing.*

fun main() {
    SwingUtilities.invokeLater {
        createAndShowGUI()
    }
}

fun createAndShowGUI() {
    val frame = JFrame("JvmJavaCvPlayer Swing Modular Architecture Demo")
    frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
    frame.layout = BorderLayout()

    // 1. 手動建立專為 Swing 優化的 VideoSink (來自 :core-video-swing)
    val videoSink = JvmVideoSink()
    
    // 2. 根據 OS 自動設定硬體加速，將 Sink 與選項注入播放器
    val ffmpegOptions = getPlatformHardwareDecoderOption()
    val player = JvmJavaCvPlayer.create(
        videoSink = videoSink,
        audioSink = JvmAudioSink(),
        options = ffmpegOptions
    )
    
    // 3. 從 VideoSink 取得加速後的 JPanel
    val videoComponent = videoSink.getView(null) as Component
    videoComponent.preferredSize = Dimension(1280, 720)
    frame.add(videoComponent, BorderLayout.CENTER)

    val controlPanel = JPanel()
    val urlField = JTextField("https://github.com/rambod-rahmani/ffmpeg-video-player/raw/refs/heads/master/Iron_Man-Trailer_HD.mp4", 50)
    val playButton = JButton("Play")
    playButton.addActionListener {
        val url = urlField.text.trim()
        if (url.isNotEmpty()) {
            player.setMediaItem(MediaItem.fromUri(url))
            player.prepare()
            player.play()
        }
    }

    controlPanel.add(urlField)
    controlPanel.add(playButton)
    frame.add(controlPanel, BorderLayout.SOUTH)

    frame.pack()
    frame.setLocationRelativeTo(null)
    frame.setVisible(true)

    frame.addWindowListener(object : java.awt.event.WindowAdapter() {
        override fun windowClosing(e: java.awt.event.WindowEvent?) {
            player.release()
        }
    })
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
