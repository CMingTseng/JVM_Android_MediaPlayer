package idv.neo.ffmpeg.media.player.core

import androidx.compose.ui.graphics.ImageBitmap
import java.nio.ByteBuffer


actual fun convertToImageBitmap(
    imageWidth: Int,
    imageHeight: Int,
    imageStride: Int,
    imageDepth: Int,
    imageChannels: Int,
    imageBuffer: ByteBuffer,
    actualFramePixelFormat: Int,
    isUseJava2DFrameConverter: Boolean
): ImageBitmap? {
    TODO("Not yet implemented")
}