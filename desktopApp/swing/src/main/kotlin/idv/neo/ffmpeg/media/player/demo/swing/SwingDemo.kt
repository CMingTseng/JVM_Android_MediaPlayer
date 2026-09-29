package idv.neo.ffmpeg.media.player.demo.swing

import idv.neo.ffmpeg.media.player.core.JvmJavaCvPlayer
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
    
    // 2. 將 Sink 注入播放器
    val player = JvmJavaCvPlayer.create(videoSink)
    
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
