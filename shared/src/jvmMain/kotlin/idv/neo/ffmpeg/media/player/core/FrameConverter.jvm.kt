package idv.neo.ffmpeg.media.player.core

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import idv.neo.ffmpeg.media.player.core.utils.getPixelFormatName
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.Frame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorInfo
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
actual fun convertToImageBitmap( imageWidth: Int, imageHeight: Int, imageStride: Int, imageDepth: Int, imageChannels: Int, imageBuffer: ByteBuffer,
    actualFramePixelFormat: Int,
    isUseJava2DFrameConverter: Boolean
): ImageBitmap? {
    val functionStartTime = System.nanoTime()
    var stepStartTime = System.nanoTime()
    var stepEndTime: Long

    val skiaImage: Image? = when (actualFramePixelFormat) {
        avutil.AV_PIX_FMT_BGRA -> {
            val calculatedStride = imageWidth * 4
            // Use frame.imageStride (bytes per row from FFmpeg) if valid, otherwise calculated.
            val stride = if (imageStride > 0) imageStride else calculatedStride

            stepEndTime = System.nanoTime()
            println("FrameToImageBitmapConverter (direct) [BGRA]: Initial prep took ${(stepEndTime - stepStartTime) / 1_000_000} ms. Stride: $stride")
            stepStartTime = System.nanoTime()

            val result = createSkiaImageFromByteBuffer(
                imageBuffer, imageWidth, imageHeight,
                ColorType.BGRA_8888, ColorAlphaType.PREMUL, // Assuming PREMUL for now
                stride
            )
            stepEndTime = System.nanoTime()
            println("FrameToImageBitmapConverter (direct) [BGRA]: createSkiaImageFromByteBuffer took ${(stepEndTime - stepStartTime) / 1_000_000} ms")
            result
        }
        avutil.AV_PIX_FMT_RGBA -> {
            val calculatedStride = imageWidth * 4
            val stride = if (imageStride > 0) imageStride else calculatedStride

            stepEndTime = System.nanoTime()
            println("FrameToImageBitmapConverter (direct) [RGBA]: Initial prep took ${(stepEndTime - stepStartTime) / 1_000_000} ms. Stride: $stride")
            stepStartTime = System.nanoTime()

            val result = createSkiaImageFromByteBuffer(
                imageBuffer, imageWidth, imageHeight,
                ColorType.RGBA_8888, ColorAlphaType.PREMUL, // Assuming PREMUL
                stride
            )
            stepEndTime = System.nanoTime()
            println("FrameToImageBitmapConverter (direct) [RGBA]: createSkiaImageFromByteBuffer took ${(stepEndTime - stepStartTime) / 1_000_000} ms")
            result
        }
        avutil.AV_PIX_FMT_BGR24 -> {
            val bgrBuffer = imageBuffer
            val calculatedBgrStride = imageWidth * 3
            // Use frame.imageStride (bytes per row for BGR from FFmpeg) if valid. This handles padding.
            val bgrActualStride = if (imageStride > 0) imageStride else calculatedBgrStride

            stepEndTime = System.nanoTime()
            println("FrameToImageBitmapConverter (direct) [BGR24]: Initial prep took ${(stepEndTime - stepStartTime) / 1_000_000} ms. Actual BGR Stride: $bgrActualStride")
            stepStartTime = System.nanoTime()

            // convertBgrToBgra needs the actual stride of the source BGR data.
            val bgraBytes = convertBgrToBgra(bgrBuffer, imageWidth, imageHeight, bgrActualStride)
            stepEndTime = System.nanoTime()
            println("FrameToImageBitmapConverter (direct) [BGR24]: convertBgrToBgra took ${(stepEndTime - stepStartTime) / 1_000_000} ms")

            if (bgraBytes == null) {
                println("FrameToImageBitmapConverter (direct) [BGR24]: BGR24 to BGRA conversion failed.")
                null
            } else {
                stepStartTime = System.nanoTime()
                // After conversion to BGRA, the stride for the new bgraBytes array is width * 4 (no padding within this array)
                val bgraMemoryStride = imageWidth * 4
                val imageInfo = ImageInfo(
                    colorInfo = ColorInfo(ColorType.BGRA_8888, ColorAlphaType.OPAQUE, ColorSpace.sRGB), // BGR to BGRA with opaque alpha
                    width = imageWidth,
                    height = imageHeight
                )
                val result = Image.makeRaster(imageInfo, bgraBytes, bgraMemoryStride)
                stepEndTime = System.nanoTime()
                println("FrameToImageBitmapConverter (direct) [BGR24]: Image.makeRaster from BGRA bytes took ${(stepEndTime - stepStartTime) / 1_000_000} ms")
                result
            }
        }
        else -> {
            println("FrameToImageBitmapConverter (direct): Format ${getPixelFormatName(actualFramePixelFormat)} is not supported by this direct converter. Returning null.")
            // No fallback to convertSafeViaJava2D here.
            null
        }
    }

    var finalResultBitmap: ImageBitmap?
    if (skiaImage != null) {
        stepStartTime = System.nanoTime()
        try {
            finalResultBitmap = skiaImage.toComposeImageBitmap()
        } catch (e: Exception) {
            println("FrameToImageBitmapConverter (direct): Failed to convert Skia Image to Compose ImageBitmap: ${e.message}")
            finalResultBitmap = null
        } finally {
            skiaImage.close() // IMPORTANT: Release Skia image resources
            stepEndTime = System.nanoTime()
            println("FrameToImageBitmapConverter (direct): toComposeImageBitmap & close took ${(stepEndTime - stepStartTime) / 1_000_000} ms")
        }
    } else {
        finalResultBitmap = null
    }

    val functionEndTime = System.nanoTime()
    val totalDuration = (functionEndTime - functionStartTime) / 1_000_000
    if (finalResultBitmap != null) {
        println("FrameToImageBitmapConverter (direct): Successfully converted format ${getPixelFormatName(actualFramePixelFormat)}. Total time: $totalDuration ms")
    } else {
        println("FrameToImageBitmapConverter (direct): Failed to convert format ${getPixelFormatName(actualFramePixelFormat)}. Total time: $totalDuration ms")
    }
    return finalResultBitmap
}

fun convertDirectToSkia(frame: Frame, actualFramePixelFormat: Int): ImageBitmap? {
    if (frame.imageWidth <= 0 || frame.imageHeight <= 0 || frame.image == null || frame.image[0] == null) {
        println("FrameToImageBitmapConverter (direct): Invalid frame data.")
        return null
    }

    val imageBuffer = frame.image[0] as ByteBuffer
    return convertToImageBitmap(frame.imageWidth,frame.imageHeight,frame.imageStride,frame.imageDepth,frame.imageChannels,imageBuffer,actualFramePixelFormat,false)

//    var stepStartTime = System.nanoTime()
//    var stepEndTime: Long
//
//    val skiaImage: Image? = when (actualFramePixelFormat) {
//        avutil.AV_PIX_FMT_BGRA -> {
//            val calculatedStride = width * 4
//            // Use frame.imageStride (bytes per row from FFmpeg) if valid, otherwise calculated.
//            val stride = if (frame.imageStride > 0) frame.imageStride else calculatedStride
//
//            stepEndTime = System.nanoTime()
//            println("FrameToImageBitmapConverter (direct) [BGRA]: Initial prep took ${(stepEndTime - stepStartTime) / 1_000_000} ms. Stride: $stride")
//            stepStartTime = System.nanoTime()
//
//            val result = createSkiaImageFromByteBuffer(
//                imageBuffer, width, height,
//                ColorType.BGRA_8888, ColorAlphaType.PREMUL, // Assuming PREMUL for now
//                stride
//            )
//            stepEndTime = System.nanoTime()
//            println("FrameToImageBitmapConverter (direct) [BGRA]: createSkiaImageFromByteBuffer took ${(stepEndTime - stepStartTime) / 1_000_000} ms")
//            result
//        }
//        avutil.AV_PIX_FMT_RGBA -> {
//            val calculatedStride = width * 4
//            val stride = if (frame.imageStride > 0) frame.imageStride else calculatedStride
//
//            stepEndTime = System.nanoTime()
//            println("FrameToImageBitmapConverter (direct) [RGBA]: Initial prep took ${(stepEndTime - stepStartTime) / 1_000_000} ms. Stride: $stride")
//            stepStartTime = System.nanoTime()
//
//            val result = createSkiaImageFromByteBuffer(
//                imageBuffer, width, height,
//                ColorType.RGBA_8888, ColorAlphaType.PREMUL, // Assuming PREMUL
//                stride
//            )
//            stepEndTime = System.nanoTime()
//            println("FrameToImageBitmapConverter (direct) [RGBA]: createSkiaImageFromByteBuffer took ${(stepEndTime - stepStartTime) / 1_000_000} ms")
//            result
//        }
//        avutil.AV_PIX_FMT_BGR24 -> {
//            val bgrBuffer = imageBuffer
//            val calculatedBgrStride = width * 3
//            // Use frame.imageStride (bytes per row for BGR from FFmpeg) if valid. This handles padding.
//            val bgrActualStride = if (frame.imageStride > 0) frame.imageStride else calculatedBgrStride
//
//            stepEndTime = System.nanoTime()
//            println("FrameToImageBitmapConverter (direct) [BGR24]: Initial prep took ${(stepEndTime - stepStartTime) / 1_000_000} ms. Actual BGR Stride: $bgrActualStride")
//            stepStartTime = System.nanoTime()
//
//            // convertBgrToBgra needs the actual stride of the source BGR data.
//            val bgraBytes = convertBgrToBgra(bgrBuffer, width, height, bgrActualStride)
//            stepEndTime = System.nanoTime()
//            println("FrameToImageBitmapConverter (direct) [BGR24]: convertBgrToBgra took ${(stepEndTime - stepStartTime) / 1_000_000} ms")
//
//            if (bgraBytes == null) {
//                println("FrameToImageBitmapConverter (direct) [BGR24]: BGR24 to BGRA conversion failed.")
//                null
//            } else {
//                stepStartTime = System.nanoTime()
//                // After conversion to BGRA, the stride for the new bgraBytes array is width * 4 (no padding within this array)
//                val bgraMemoryStride = width * 4
//                val imageInfo = ImageInfo(
//                    colorInfo = ColorInfo(ColorType.BGRA_8888, ColorAlphaType.OPAQUE, ColorSpace.sRGB), // BGR to BGRA with opaque alpha
//                    width = width,
//                    height = height
//                )
//                val result = Image.makeRaster(imageInfo, bgraBytes, bgraMemoryStride)
//                stepEndTime = System.nanoTime()
//                println("FrameToImageBitmapConverter (direct) [BGR24]: Image.makeRaster from BGRA bytes took ${(stepEndTime - stepStartTime) / 1_000_000} ms")
//                result
//            }
//        }
//        else -> {
//            println("FrameToImageBitmapConverter (direct): Format ${getPixelFormatName(actualFramePixelFormat)} is not supported by this direct converter. Returning null.")
//            // No fallback to convertSafeViaJava2D here.
//            null
//        }
//    }
//
//    var finalResultBitmap: ImageBitmap?
//    if (skiaImage != null) {
//        stepStartTime = System.nanoTime()
//        try {
//            finalResultBitmap = skiaImage.toComposeImageBitmap()
//        } catch (e: Exception) {
//            println("FrameToImageBitmapConverter (direct): Failed to convert Skia Image to Compose ImageBitmap: ${e.message}")
//            finalResultBitmap = null
//        } finally {
//            skiaImage.close() // IMPORTANT: Release Skia image resources
//            stepEndTime = System.nanoTime()
//            println("FrameToImageBitmapConverter (direct): toComposeImageBitmap & close took ${(stepEndTime - stepStartTime) / 1_000_000} ms")
//        }
//    } else {
//        finalResultBitmap = null
//    }
//
//    val functionEndTime = System.nanoTime()
//    val totalDuration = (functionEndTime - functionStartTime) / 1_000_000
//    if (finalResultBitmap != null) {
//        println("FrameToImageBitmapConverter (direct): Successfully converted format ${getPixelFormatName(actualFramePixelFormat)}. Total time: $totalDuration ms")
//    } else {
//        println("FrameToImageBitmapConverter (direct): Failed to convert format ${getPixelFormatName(actualFramePixelFormat)}. Total time: $totalDuration ms")
//    }
//    return finalResultBitmap
}

/**
 * Helper to create a Skia Image from a ByteBuffer containing directly compatible pixel data (BGRA or RGBA).
 * This function copies data from the ByteBuffer to a ByteArray for Image.makeRaster.
 *
 * @param sourceBuffer The ByteBuffer containing pixel data.
 * @param width Image width.
 * @param height Image height.
 * @param colorType Skia ColorType (e.g., BGRA_8888, RGBA_8888).
 * @param alphaType Skia ColorAlphaType.
 * @param sourceRowBytes The stride (bytes per row) of the sourceBuffer data. This handles padding.
 */
private fun createSkiaImageFromByteBuffer(
    sourceBuffer: ByteBuffer,
    width: Int,
    height: Int,
    colorType: ColorType,
    alphaType: ColorAlphaType,
    sourceRowBytes: Int // Stride of the source data in bytes
): Image? {
    val functionStartTime = System.nanoTime()
    val imageInfo = ImageInfo(
        colorInfo = ColorInfo(colorType, alphaType, ColorSpace.sRGB),
        width = width,
        height = height
    )

    // The data for Skia's makeRaster needs to be contiguous in the byte array.
    // If sourceRowBytes has padding, we need to copy row by row to a new compact byte array.
    // If sourceRowBytes is already width * bytesPerPixel (no padding), a single bulk get is possible.

    val bytesPerPixel = colorType.bytesPerPixel
    val skiaExpectedRowBytes = width * bytesPerPixel // Skia expects this stride for the compact byte array
    val byteArrayForSkia = ByteArray(height * skiaExpectedRowBytes) // Compact array for Skia

    var copyStartTime = System.nanoTime()
    sourceBuffer.rewind() // Ensure reading from the beginning of the source buffer

    try {
        if (sourceRowBytes == skiaExpectedRowBytes) {
            // Source is already compact, can do a bulk get if buffer is large enough
            if (sourceBuffer.remaining() < byteArrayForSkia.size) {
                println("FrameToImageBitmapConverter (createSkiaImage): Source buffer (compact) remaining (${sourceBuffer.remaining()}) < required size (${byteArrayForSkia.size}).")
                return null
            }
            sourceBuffer.get(byteArrayForSkia, 0, byteArrayForSkia.size)
        } else {
            // Source has padding, copy row by row, removing padding
            for (y in 0 until height) {
                sourceBuffer.position(y * sourceRowBytes) // Go to start of current row in source (with padding)
                // Check if enough data in source for one row without padding
                if (sourceBuffer.remaining() < skiaExpectedRowBytes) {
                    println("FrameToImageBitmapConverter (createSkiaImage): Source buffer remaining (${sourceBuffer.remaining()}) insufficient for row $y (needs $skiaExpectedRowBytes).")
                    return null
                }
                sourceBuffer.get(byteArrayForSkia, y * skiaExpectedRowBytes, skiaExpectedRowBytes) // Copy one compact row to target
            }
        }
    } catch (e: Exception) {
        println("FrameToImageBitmapConverter (createSkiaImage): Error copying buffer to byte array: ${e.message}")
        e.printStackTrace()
        return null
    }
    var copyEndTime = System.nanoTime()
    println("FrameToImageBitmapConverter (createSkiaImage): Data copy (ByteBuffer to compact ByteArray) took ${(copyEndTime - copyStartTime) / 1_000_000} ms")

    var makeRasterStartTime = System.nanoTime()
    val skiaImg = try {
        Image.makeRaster(imageInfo, byteArrayForSkia, skiaExpectedRowBytes)
    } catch (e: Exception) {
        println("FrameToImageBitmapConverter (createSkiaImage): Skia Image.makeRaster failed: ${e.message}")
        e.printStackTrace()
        null
    }
    var makeRasterEndTime = System.nanoTime()
    println("FrameToImageBitmapConverter (createSkiaImage): Image.makeRaster took ${(makeRasterEndTime - makeRasterStartTime) / 1_000_000} ms")

    val functionEndTime = System.nanoTime()
    println("FrameToImageBitmapConverter (createSkiaImage): Total createSkiaImageFromByteBuffer took ${(functionEndTime - functionStartTime) / 1_000_000} ms")
    return skiaImg
}

/**
 * Converts BGR (3 bytes/pixel) data from a ByteBuffer to a new BGRA (4 bytes/pixel) ByteArray.
 * Alpha is set to opaque (0xFF).
 *
 * @param bgrSourceBuffer The ByteBuffer containing BGR pixel data.
 * @param width Image width.
 * @param height Image height.
 * @param bgrSourceStride The stride (bytes per row) of the source BGR data. This handles padding.
 * @return A new ByteArray containing BGRA data, or null on failure.
 */
private fun convertBgrToBgra(
    bgrSourceBuffer: ByteBuffer,
    width: Int,
    height: Int,
    bgrSourceStride: Int // Actual stride of the source BGR data in bytes (handles padding)
): ByteArray? {
    val functionStartTime = System.nanoTime()
    // The target BGRA byte array will be compact (stride = width * 4)
    val bgraTargetBytes = ByteArray(width * height * 4)
    val initialPosition = bgrSourceBuffer.position()

    try {
        bgrSourceBuffer.rewind() // Start reading from the beginning of the source buffer

        // Check if source buffer has enough data considering its stride
        val minBytesNeededInSource = (height - 1) * bgrSourceStride + (width * 3) // Last pixel of last relevant row
        if (bgrSourceBuffer.remaining() < minBytesNeededInSource) {
            println("FrameToImageBitmapConverter (BgrToBgra): BGR source buffer too small. Remaining: ${bgrSourceBuffer.remaining()}, Min Expected: $minBytesNeededInSource (h=$height, w=$width, stride=$bgrSourceStride)")
            bgrSourceBuffer.position(initialPosition)
            return null
        }

        for (y in 0 until height) {
            val sourceRowStartOffset = y * bgrSourceStride // Start of current row in source BGR buffer
            val targetRowStartOffset = y * (width * 4)   // Start of current row in target BGRA array (compact)

            bgrSourceBuffer.position(sourceRowStartOffset) // Position buffer at the start of the source row

            for (x in 0 until width) {
                // We've already set the position for the row. Direct gets are relative to current position.
                // For safety, one could re-calculate absolute offset for each get:
                // val b = bgrSourceBuffer.get(sourceRowStartOffset + x * 3 + 0)
                // But sequential gets after positioning at row start should work.
                // Ensure enough bytes for 3 pixels before reading them
                if (bgrSourceBuffer.position() > bgrSourceBuffer.limit() - 3) { // Check relative to current position
                    println("FrameToImageBitmapConverter (BgrToBgra): Buffer underflow at y=$y, x=$x (reading BGR). Source pos: ${bgrSourceBuffer.position()}, limit: ${bgrSourceBuffer.limit()}")
                    bgrSourceBuffer.position(initialPosition)
                    return null
                }
                val b = bgrSourceBuffer.get()
                val g = bgrSourceBuffer.get()
                val r = bgrSourceBuffer.get()

                val bgraPixelOffsetInTarget = targetRowStartOffset + x * 4
                bgraTargetBytes[bgraPixelOffsetInTarget + 0] = b
                bgraTargetBytes[bgraPixelOffsetInTarget + 1] = g
                bgraTargetBytes[bgraPixelOffsetInTarget + 2] = r
                bgraTargetBytes[bgraPixelOffsetInTarget + 3] = 0xFF.toByte() // Opaque Alpha
            }
        }
    } catch (e: Exception) {
        println("FrameToImageBitmapConverter (BgrToBgra): Error during BGR to BGRA conversion: ${e.message}")
        e.printStackTrace()
        bgrSourceBuffer.position(initialPosition) // Restore on error
        return null
    } finally {
        bgrSourceBuffer.position(initialPosition) // Restore original position
    }
    val functionEndTime = System.nanoTime()
    println("FrameToImageBitmapConverter (BgrToBgra): Total convertBgrToBgra (to new ByteArray) took ${(functionEndTime - functionStartTime) / 1_000_000} ms")
    return bgraTargetBytes
}