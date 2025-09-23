package org.bytedeco.javacv

import javafx.scene.image.Image as JavaFxImage
import org.bytedeco.ffmpeg.global.avutil
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A universal frame converter object to convert org.bytedeco.javacv.Frame
 * to UI-specific image types (JavaFX Image or Swing BufferedImage).
 */
object UniversalFrameConverter {

    // Lazy initialization for converters
    private val fxConverter by lazy { JavaFXFrameConverter() }
    private val swingConverter by lazy { Java2DFrameConverter() }

    /**
     * Converts a Javacv Frame to a JavaFX Image.
     */
    @JvmStatic
    fun convertToFxImage(frame: Frame?): JavaFxImage? {
        if (frame == null || frame.image == null) {
            println("UniversalFrameConverter: Input frame or its image data is null for JavaFX conversion.")
            return null
        }
        return try {
            fxConverter.convert(frame)
        } catch (e: Exception) {
            println("UniversalFrameConverter: Error converting frame to JavaFX Image: ${e.message}")
            // e.printStackTrace() // For debugging
            null
        }
    }

    /**
     * Converts a Javacv Frame to a Swing BufferedImage.
     * This method attempts to handle various pixel formats directly for BufferedImage.
     * Based on the logic from your original Swing FrameConverter.kt.
     *
     * @param frame The Frame to convert.
     * @param actualFramePixelFormat The actual pixel format of the frame,
     *                               obtained from FFmpegFrameGrabber.getPixelFormat().
     * @return BufferedImage or null if conversion fails.
     */
    @JvmStatic
    fun convertToBufferedImage(frame: Frame?, actualFramePixelFormat: Int): BufferedImage? {
        if (frame == null || frame.imageWidth <= 0 || frame.imageHeight <= 0 || frame.image == null || frame.image[0] == null) {
            println("UniversalFrameConverter: Invalid frame data for BufferedImage conversion.")
            return null
        }

        val width = frame.imageWidth
        val height = frame.imageHeight
        val imageBuffer = frame.image[0] as? ByteBuffer
            ?: run {
                println("UniversalFrameConverter: Frame image buffer is not a ByteBuffer.")
                return null
            }
        imageBuffer.rewind()

        var bufferedImage: BufferedImage? = null
        val startTime = System.nanoTime()

        try {
            // Logic adapted from your desktop/swing/src/main/java/idv/neo/ffmpeg/media/player/desktop/FrameConverter.kt
            // Ensure this logic is robust and correct for all expected pixel formats.
            when (actualFramePixelFormat) {
                avutil.AV_PIX_FMT_BGR24 -> {
                    bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
                    val dataBuffer = bufferedImage.raster.dataBuffer as DataBufferByte
                    val imgData = dataBuffer.data
                    val ffmpegStride = frame.imageStride
                    val bufferedImageStride = width * 3

                    if (ffmpegStride == bufferedImageStride) {
                        if (imageBuffer.remaining() >= imgData.size) {
                            imageBuffer.get(imgData, 0, imgData.size)
                        } else {
                            println("UniversalFrameConverter (BGR24): Buffer underflow. Remaining: ${imageBuffer.remaining()}, Needed: ${imgData.size}")
                            return null
                        }
                    } else {
                        for (y in 0 until height) {
                            imageBuffer.position(y * ffmpegStride)
                            if (imageBuffer.remaining() >= bufferedImageStride) {
                                imageBuffer.get(imgData, y * bufferedImageStride, bufferedImageStride)
                            } else {
                                println("UniversalFrameConverter (BGR24): Row buffer underflow at y=$y.")
                                return null
                            }
                        }
                    }
                }
                avutil.AV_PIX_FMT_BGRA -> {
                    bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    val dataBuffer = bufferedImage.raster.dataBuffer as DataBufferInt
                    val imgData = dataBuffer.data
                    val ffmpegStride = frame.imageStride
                    imageBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    for (y in 0 until height) {
                        val rowStartInByteBuffer = y * ffmpegStride
                        for (x in 0 until width) {
                            val pixelStartInByteBuffer = rowStartInByteBuffer + x * 4
                            if (pixelStartInByteBuffer + 3 < imageBuffer.limit()) {
                                val b = imageBuffer.get(pixelStartInByteBuffer + 0).toInt() and 0xFF
                                val g = imageBuffer.get(pixelStartInByteBuffer + 1).toInt() and 0xFF
                                val r = imageBuffer.get(pixelStartInByteBuffer + 2).toInt() and 0xFF
                                val a = imageBuffer.get(pixelStartInByteBuffer + 3).toInt() and 0xFF
                                imgData[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                            } else { return null } // Underflow
                        }
                    }
                }
                avutil.AV_PIX_FMT_RGBA -> {
                    bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    val dataBuffer = bufferedImage.raster.dataBuffer as DataBufferInt
                    val imgData = dataBuffer.data
                    val ffmpegStride = frame.imageStride
                    imageBuffer.order(ByteOrder.LITTLE_ENDIAN)
                    for (y in 0 until height) {
                        val rowStartInByteBuffer = y * ffmpegStride
                        for (x in 0 until width) {
                            val pixelStartInByteBuffer = rowStartInByteBuffer + x * 4
                            if (pixelStartInByteBuffer + 3 < imageBuffer.limit()) {
                                val r = imageBuffer.get(pixelStartInByteBuffer + 0).toInt() and 0xFF
                                val g = imageBuffer.get(pixelStartInByteBuffer + 1).toInt() and 0xFF
                                val b = imageBuffer.get(pixelStartInByteBuffer + 2).toInt() and 0xFF
                                val a = imageBuffer.get(pixelStartInByteBuffer + 3).toInt() and 0xFF
                                imgData[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                            } else { return null } // Underflow
                        }
                    }
                }
                avutil.AV_PIX_FMT_ARGB -> {
                    bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    val dataBuffer = bufferedImage.raster.dataBuffer as DataBufferInt
                    val imgData = dataBuffer.data
                    val ffmpegStride = frame.imageStride
                    for (y in 0 until height) {
                        val rowStartInByteBuffer = y * ffmpegStride
                        for (x in 0 until width) {
                            val pixelStartInByteBuffer = rowStartInByteBuffer + x * 4
                            if (pixelStartInByteBuffer + 3 < imageBuffer.limit()) {
                                val a = imageBuffer.get(pixelStartInByteBuffer + 0).toInt() and 0xFF
                                val r = imageBuffer.get(pixelStartInByteBuffer + 1).toInt() and 0xFF
                                val g = imageBuffer.get(pixelStartInByteBuffer + 2).toInt() and 0xFF
                                val b = imageBuffer.get(pixelStartInByteBuffer + 3).toInt() and 0xFF
                                imgData[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                            } else { return null } // Underflow
                        }
                    }
                }
                // Add other pixel format handlers if needed
                else -> {
                    // Fallback to Java2DFrameConverter for unhandled packed formats
                    // Note: Java2DFrameConverter might be slower for these if not optimized internally.
                    println("UniversalFrameConverter: Pixel format ${actualFramePixelFormat} not directly handled, trying fallback Java2DFrameConverter.")
                    return try {
                        swingConverter.getBufferedImage(frame, 1.0) // gamma = 1.0
                    } catch (e: Exception) {
                        println("UniversalFrameConverter: Fallback Java2DFrameConverter failed for format ${actualFramePixelFormat}: ${e.message}")
                        null
                    }
                }
            }
        } catch (e: Exception) {
            println("UniversalFrameConverter: Exception during BufferedImage conversion for format ${actualFramePixelFormat}: ${e.message}")
            // e.printStackTrace() // For debugging
            return null
        } finally {
            val endTime = System.nanoTime()
            val durationMs = (endTime - startTime) / 1_000_000
            // if (bufferedImage != null || durationMs > 1) { // Log if successful or took time
            //     println("UniversalFrameConverter: BufferedImage conversion for ${actualFramePixelFormat} took $durationMs ms. Result not null: ${bufferedImage != null}")
            // }
        }
        return bufferedImage
    }
}