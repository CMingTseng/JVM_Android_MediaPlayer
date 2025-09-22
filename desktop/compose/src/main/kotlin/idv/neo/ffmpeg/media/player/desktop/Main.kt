package idv.neo.ffmpeg.media.player.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import idv.neo.ffmpeg.media.player.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "RealTimeStreaming",
    ) {
        App()
    }
}

