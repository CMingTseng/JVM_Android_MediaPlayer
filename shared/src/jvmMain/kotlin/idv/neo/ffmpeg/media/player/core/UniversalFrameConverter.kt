package idv.neo.ffmpeg.media.player.core

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import idv.neo.ffmpeg.media.player.core.utils.getPixelFormatName // Assuming this utility exists
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.Java2DFrameConverter
import org.bytedeco.javacv.JavaFXFrameConverter
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A universal frame converter object to convert org.bytedeco.javacv.Frame
 * to UI-specific image types:
 * - JavaFX Image
 * - Swing BufferedImage
 * - Compose Multiplatform ImageBitmap
 */
object UniversalFrameConverter {

    // Lazy initialization for existing converters for JavaFX and a fallback for Swing
    private val fxConverter by lazy { JavaFXFrameConverter() }
    private val swingJava2DConverter by lazy { Java2DFrameConverter() }

    // --- Public Conversion Methods ---

    /**
     * Converts a Javacv Frame to a JavaFX Image.
     * Uses JavaFXFrameConverter internally.
     */
    @JvmStatic
    fun convertToFxImage(frame: Frame?): javafx.scene.image.Image? {
        if (frame == null || frame.image == null) {
            println("UniversalFrameConverter: Input frame or its image data is null for JavaFX conversion.")
            return null
        }
        return try {
            fxConverter.convert(frame)
        } catch (e: Exception) {
            println("UniversalFrameConverter: Error converting frame to JavaFX Image: ${e.message}")
            null
        }
    }

    /**
     * Converts a Javacv Frame to a Swing BufferedImage.
     * This method attempts to handle various common pixel formats directly for performance.
     * Falls back to Java2DFrameConverter for unhandled formats.
     *
     * @param frame The Frame to convert.
     * @param actualFramePixelFormat The actual pixel format of the frame from FFmpeg.
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
                println("UniversalFrameConverter: Frame image buffer is not a ByteBuffer for BufferedImage.")
                return null
            }
        imageBuffer.rewind()
        var bufferedImage: BufferedImage? = null

        try {
            when (actualFramePixelFormat) {
                avutil.AV_PIX_FMT_BGR24 -> {
                    bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
                    val dataBuffer = bufferedImage.raster.dataBuffer as DataBufferByte
                    val imgData = dataBuffer.data
                    val ffmpegStride = frame.imageStride
                    val bufferedImageStride = width * 3
                    if (ffmpegStride == bufferedImageStride) {
                        if (imageBuffer.remaining() >= imgData.size) imageBuffer.get(imgData, 0, imgData.size)
                        else { println("UniversalFrameConverter (BGR24 to BI): Buffer underflow"); return null }
                    } else {
                        for (y in 0 until height) {
                            imageBuffer.position(y * ffmpegStride)
                            if (imageBuffer.remaining() >= bufferedImageStride) imageBuffer.get(imgData, y * bufferedImageStride, bufferedImageStride)
                            else { println("UniversalFrameConverter (BGR24 to BI): Row buffer underflow y=$y"); return null }
                        }
                    }
                }
                avutil.AV_PIX_FMT_BGRA -> {
                    bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    val imgData = (bufferedImage.raster.dataBuffer as DataBufferInt).data
                    val ffmpegStride = frame.imageStride
                    for (y in 0 until height) {
                        val rowStart = y * ffmpegStride
                        for (x in 0 until width) {
                            val pixelStart = rowStart + x * 4
                            if (pixelStart + 3 < imageBuffer.limit()) {
                                val b = imageBuffer.get(pixelStart + 0).toInt() and 0xFF
                                val g = imageBuffer.get(pixelStart + 1).toInt() and 0xFF
                                val r = imageBuffer.get(pixelStart + 2).toInt() and 0xFF
                                val a = imageBuffer.get(pixelStart + 3).toInt() and 0xFF
                                imgData[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                            } else { println("UniversalFrameConverter (BGRA to BI): Buffer underflow y=$y,x=$x"); return null }
                        }
                    }
                }
                avutil.AV_PIX_FMT_RGBA -> {
                    bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    val imgData = (bufferedImage.raster.dataBuffer as DataBufferInt).data
                    val ffmpegStride = frame.imageStride
                    for (y in 0 until height) {
                        val rowStart = y * ffmpegStride
                        for (x in 0 until width) {
                            val pixelStart = rowStart + x * 4
                            if (pixelStart + 3 < imageBuffer.limit()) {
                                val r = imageBuffer.get(pixelStart + 0).toInt() and 0xFF
                                val g = imageBuffer.get(pixelStart + 1).toInt() and 0xFF
                                val b = imageBuffer.get(pixelStart + 2).toInt() and 0xFF
                                val a = imageBuffer.get(pixelStart + 3).toInt() and 0xFF
                                imgData[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                            } else { println("UniversalFrameConverter (RGBA to BI): Buffer underflow y=$y,x=$x"); return null }
                        }
                    }
                }
                avutil.AV_PIX_FMT_ARGB -> {
                    bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    val imgData = (bufferedImage.raster.dataBuffer as DataBufferInt).data
                    val ffmpegStride = frame.imageStride
                    for (y in 0 until height) {
                        val rowStart = y * ffmpegStride
                        for (x in 0 until width) {
                            val pixelStart = rowStart + x * 4
                            if (pixelStart + 3 < imageBuffer.limit()) {
                                val a = imageBuffer.get(pixelStart + 0).toInt() and 0xFF
                                val r = imageBuffer.get(pixelStart + 1).toInt() and 0xFF
                                val g = imageBuffer.get(pixelStart + 2).toInt() and 0xFF
                                val b = imageBuffer.get(pixelStart + 3).toInt() and 0xFF
                                imgData[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                            } else { println("UniversalFrameConverter (ARGB to BI): Buffer underflow y=$y,x=$x"); return null }
                        }
                    }
                }
                else -> {
                    println("UniversalFrameConverter: Format ${getPixelFormatName(actualFramePixelFormat)} for BufferedImage, trying fallback.")
                    return try { swingJava2DConverter.getBufferedImage(frame, 1.0) }
                    catch (e: Exception) { println("UniversalFrameConverter: Fallback Java2DFrameConverter failed for BI: ${e.message}"); null }
                }
            }
        } catch (e: Exception) {
            println("UniversalFrameConverter: Exception during BufferedImage conversion: ${e.message}"); return null
        }
        return bufferedImage
    }

    /**
     * Converts a Javacv Frame to a Compose Multiplatform ImageBitmap.
     * This method attempts to handle various common pixel formats directly for performance.
     *
     * @param frame The Frame to convert.
     * @param actualFramePixelFormat The actual pixel format of the frame from FFmpeg.
     * @return ImageBitmap or null if conversion fails.
     */
    fun convertToImageBitmap(frame: Frame?, actualFramePixelFormat: Int): ImageBitmap? {
        if (frame == null || frame.imageWidth <= 0 || frame.imageHeight <= 0 || frame.image == null || frame.image[0] == null) {
            println("UniversalFrameConverter: Invalid frame data for ImageBitmap conversion.")
            return null
        }

        val width = frame.imageWidth
        val height = frame.imageHeight
        val imageBuffer = frame.image[0] as? ByteBuffer ?: return null.also {
            println("UniversalFrameConverter: Frame image buffer is not a ByteBuffer for ImageBitmap.")
        }

        val skiaImage: org.jetbrains.skia.Image? = when (actualFramePixelFormat) {
            avutil.AV_PIX_FMT_BGRA -> {
                val stride = frame.imageStride.let { if (it > 0 && it >= width * 4) it else width * 4 }
                createSkiaImageFromByteBuffer(imageBuffer, width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL, stride)
            }
            avutil.AV_PIX_FMT_RGBA -> {
                val stride = frame.imageStride.let { if (it > 0 && it >= width * 4) it else width * 4 }
                createSkiaImageFromByteBuffer(imageBuffer, width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL, stride)
            }
            avutil.AV_PIX_FMT_BGR24 -> {
                val bgrStride = frame.imageStride.let { if (it > 0 && it >= width * 3) it else width * 3 }
                val bgraBytes = convertBgrToBgra(imageBuffer, width, height, bgrStride)
                if (bgraBytes != null) {
                    val imageInfo = ImageInfo(ColorInfo(ColorType.BGRA_8888, ColorAlphaType.OPAQUE, ColorSpace.sRGB), width, height)
                    org.jetbrains.skia.Image.makeRaster(imageInfo=imageInfo, bytes=bgraBytes, rowBytes=(width * 4))
                } else { println("UniversalFrameConverter (BGR24 to Skia): BGR to BGRA failed."); null }
            }
            avutil.AV_PIX_FMT_ARGB -> {
                println("UniversalFrameConverter (ARGB to Skia): Direct AV_PIX_FMT_ARGB to Skia ImageBitmap is not directly implemented without swizzling. Format: ${getPixelFormatName(actualFramePixelFormat)}")
                // For a complete solution, one might swizzle ARGB to RGBA here and use createSkiaImageFromByteBuffer
                // Example:
                // val rgbaBytes = swizzleArgbToRgba(imageBuffer, width, height, frame.imageStride.let { if (it > 0 && it >= width * 4) it else width * 4 })
                // if (rgbaBytes != null) {
                //    createSkiaImageFromByteBuffer(ByteBuffer.wrap(rgbaBytes), width, height, ColorType.RGBA_8888, ColorAlphaType.PREMUL, width * 4)
                // } else null
                null
            }
            else -> {
                println("UniversalFrameConverter: Format ${getPixelFormatName(actualFramePixelFormat)} not supported for ImageBitmap.")
                null
            }
        }

        return skiaImage?.let {
            try {
                it.toComposeImageBitmap()
            } catch (e: Exception) {
                println("UniversalFrameConverter: Skia to Compose ImageBitmap failed: ${e.message}"); null
            } finally {
                it.close()
            }
        }
    }

    // --- Helper methods for ImageBitmap conversion (from original FrameConverter.kt) ---
    private fun createSkiaImageFromByteBuffer(
        sourceBuffer: ByteBuffer,
        width: Int,
        height: Int,
        colorType: ColorType,
        alphaType: ColorAlphaType,
        sourceRowBytes: Int
    ): org.jetbrains.skia.Image? {
        sourceBuffer.rewind()
        val imageInfo = ImageInfo(ColorInfo(colorType, alphaType, ColorSpace.sRGB), width, height)
        val bytesPerPixel = colorType.bytesPerPixel
        val skiaExpectedRowBytes = width * bytesPerPixel
        val byteArrayForSkia: ByteArray

        if (sourceRowBytes == skiaExpectedRowBytes) {
            byteArrayForSkia = ByteArray(height * skiaExpectedRowBytes)
            if (sourceBuffer.remaining() < byteArrayForSkia.size) {
                println("UniversalFrameConverter (createSkiaImage): Buffer (compact) remaining ${sourceBuffer.remaining()} < required ${byteArrayForSkia.size}.")
                return null
            }
            sourceBuffer.get(byteArrayForSkia)
        } else {
            byteArrayForSkia = ByteArray(height * skiaExpectedRowBytes)
            for (y in 0 until height) {
                sourceBuffer.position(y * sourceRowBytes)
                if (sourceBuffer.remaining() < skiaExpectedRowBytes) {
                    println("UniversalFrameConverter (createSkiaImage): Buffer remaining ${sourceBuffer.remaining()} insufficient for row $y (needs $skiaExpectedRowBytes).")
                    return null
                }
                sourceBuffer.get(byteArrayForSkia, y * skiaExpectedRowBytes, skiaExpectedRowBytes)
            }
        }
        return try {
            org.jetbrains.skia.Image.makeRaster(imageInfo, byteArrayForSkia, skiaExpectedRowBytes)
        } catch (e: Exception) {
            println("UniversalFrameConverter (createSkiaImage): Skia Image.makeRaster failed: ${e.message}"); null
        }
    }

    private fun convertBgrToBgra(
        bgrSourceBuffer: ByteBuffer,
        width: Int,
        height: Int,
        bgrSourceStride: Int
    ): ByteArray? {
        val bgraTargetBytes = ByteArray(width * height * 4)
        val initialPosition = bgrSourceBuffer.position()
        try {
            bgrSourceBuffer.rewind()
            val minBytesNeeded = (height - 1) * bgrSourceStride + (width * 3)
            if (bgrSourceBuffer.limit() < minBytesNeeded) {
                println("UniversalFrameConverter (BgrToBgra): Source buffer too small. Limit: ${bgrSourceBuffer.limit()}, Min Expected: $minBytesNeeded")
                return null
            }
            for (y in 0 until height) {
                val sourceRowStart = y * bgrSourceStride
                val targetRowStart = y * (width * 4)
                if (sourceRowStart + (width * 3) > bgrSourceBuffer.limit()) {
                    println("UniversalFrameConverter (BgrToBgra): Read for row $y exceeds buffer limit.")
                    return null
                }
                bgrSourceBuffer.position(sourceRowStart)
                for (x in 0 until width) {
                    if (bgrSourceBuffer.remaining() < 3) {
                        println("UniversalFrameConverter (BgrToBgra): Buffer underflow y=$y, x=$x. Remaining: ${bgrSourceBuffer.remaining()}")
                        return null
                    }
                    val b = bgrSourceBuffer.get()
                    val g = bgrSourceBuffer.get()
                    val r = bgrSourceBuffer.get()
                    val offset = targetRowStart + x * 4
                    bgraTargetBytes[offset + 0] = b
                    bgraTargetBytes[offset + 1] = g
                    bgraTargetBytes[offset + 2] = r
                    bgraTargetBytes[offset + 3] = 0xFF.toByte()
                }
            }
        } catch (e: Exception) {
            println("UniversalFrameConverter (BgrToBgra): Error: ${e.message}"); return null
        } finally {
            bgrSourceBuffer.position(initialPosition)
        }
        return bgraTargetBytes
    }

    // Optional: Helper to swizzle ARGB to RGBA if needed for Skia path for AV_PIX_FMT_ARGB
    /*
    private fun swizzleArgbToRgba(argbBuffer: ByteBuffer, width: Int, height: Int, argbStride: Int): ByteArray? {
        val rgbaTargetBytes = ByteArray(width * height * 4)
        val initialPosition = argbBuffer.position()
        try {
            argbBuffer.rewind()
            val minBytesNeeded = (height - 1) * argbStride + (width * 4)
            if (argbBuffer.limit() < minBytesNeeded) {
                 println("UniversalFrameConverter (swizzleArgbToRgba): ARGB source buffer too small.")
                 return null
            }
            for (y in 0 until height) {
                val sourceRowStart = y * argbStride
                val targetRowStart = y * (width * 4)
                 if (sourceRowStart + (width * 4) > argbBuffer.limit()) {
                     println("UniversalFrameConverter (swizzleArgbToRgba): Read for row $y exceeds buffer limit.")
                     return null
                }
                argbBuffer.position(sourceRowStart)
                for (x in 0 until width) {
                    if (argbBuffer.remaining() < 4) {
                        println("UniversalFrameConverter (swizzleArgbToRgba): Buffer underflow y=$y, x=$x.")
                        return null
                    }
                    val a = argbBuffer.get()
                    val r = argbBuffer.get()
                    val g = argbBuffer.get()
                    val b = argbBuffer.get()
                    val offset = targetRowStart + x * 4
                    rgbaTargetBytes[offset + 0] = r
                    rgbaTargetBytes[offset + 1] = g
                    rgbaTargetBytes[offset + 2] = b
                    rgbaTargetBytes[offset + 3] = a
                }
            }
        } catch (e: Exception) {
            println("UniversalFrameConverter (swizzleArgbToRgba): Error: ${e.message}"); return null
        }
        finally { argbBuffer.position(initialPosition) }
        return rgbaTargetBytes
    }
    */
}

