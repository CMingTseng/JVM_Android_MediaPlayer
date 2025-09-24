package idv.neo.ffmpeg.media.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

@Composable
fun MainScreen(modifier: Modifier = Modifier) {
    val viewModel: MainViewModel = getViewModel()
    val countdown by viewModel.countdownValue.collectAsState()
    val imageBitmap: ImageBitmap? by viewModel.videoFrameBitmap.collectAsState() // Collect the bitmap
    var videoUrl by remember { mutableStateOf("https://github.com/rambod-rahmani/ffmpeg-video-player/raw/refs/heads/master/Iron_Man-Trailer_HD.mp4") }
    val textMeasurer = rememberTextMeasurer()
    viewModel.startVideoStreaming(videoUrl)

    Canvas(
        modifier = Modifier.fillMaxSize()
    ) {
        if (imageBitmap != null) {
            drawImage(
                image = imageBitmap!!,
                dstOffset = IntOffset.Zero, // Draw from top-left corner of the Canvas
                dstSize = IntSize(
                    size.width.toInt(),
                    size.height.toInt()
                )
            )
        } else {
            drawRect(
                color = Color.LightGray,
                size = size // Fills the entire Canvas
            )
            if (countdown >= 0) {
                val textToDraw = countdown.toString()
                val textStyle = TextStyle(
                    fontSize = 100.sp,
                    color = Color.Black,
                    fontWeight = FontWeight.Bold
                )
                val textLayoutResult = textMeasurer.measure(
                    text = textToDraw,
                    style = textStyle
                )
                val textWidth = textLayoutResult.size.width
                val textHeight = textLayoutResult.size.height
                val centerX = (size.width - textWidth) / 2
                val centerY = (size.height - textHeight) / 2

                drawText(
                    textLayoutResult = textLayoutResult,
                    topLeft = Offset(centerX, centerY)
                )
            }
        }
    }
}