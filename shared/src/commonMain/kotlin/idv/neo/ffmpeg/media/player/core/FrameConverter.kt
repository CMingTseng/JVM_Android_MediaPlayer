package idv.neo.ffmpeg.media.player.core

import androidx.compose.ui.graphics.ImageBitmap
import java.nio.ByteBuffer

expect fun convertToImageBitmap(imageWidth:Int,imageHeight:Int,imageStride:Int,imageDepth:Int,imageChannels:Int,imageBuffer:ByteBuffer,  actualFramePixelFormat: Int=0,isUseJava2DFrameConverter: Boolean = false): ImageBitmap?

