package idv.neo.ffmpeg.media.player.desktop

import idv.neo.ffmpeg.media.player.core.utils.getPixelFormatName
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.Frame
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.awt.image.DataBufferInt
import java.nio.ByteBuffer
import java.nio.ByteOrder

object FrameConverter {

    fun converDirectToBufferedImage(frame: Frame, actualFramePixelFormat: Int): BufferedImage? {
        val functionStartTimeNs = System.nanoTime() // Record start time

        if (frame.imageWidth <= 0 || frame.imageHeight <= 0 || frame.image == null || frame.image[0] == null) {
            println("FrameToBufferedImageConverter (direct): Invalid frame data.")
            return null
        }

        val width = frame.imageWidth
        val height = frame.imageHeight
        val imageBuffer = frame.image[0] as ByteBuffer
        imageBuffer.rewind()

        var bufferedImage: BufferedImage? = null

        try {
            when (actualFramePixelFormat) {
                avutil.AV_PIX_FMT_BGR24 -> {
                    // BufferedImage.TYPE_3BYTE_BGR expects 3 bytes per pixel, BGR order.
                    // frame.imageStride is linesize[0], which is bytes per row for packed formats.
                    bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
                    val dataBuffer = bufferedImage.raster.dataBuffer as DataBufferByte
                    val imgData = dataBuffer.data
                    val ffmpegStride = frame.imageStride // This is already bytes per row
                    val bufferedImageStride = width * 3  // Expected stride for TYPE_3BYTE_BGR

                    if (ffmpegStride == bufferedImageStride) {
                        if (imageBuffer.remaining() >= imgData.size) {
                            imageBuffer.get(imgData, 0, imgData.size)
                        } else {
                            println("FrameToBufferedImageConverter (BGR24): Buffer underflow for bulk copy. FFmpeg remaining: ${imageBuffer.remaining()}, ImgData size: ${imgData.size}, Width: $width, Height: $height, FFMPEG Stride: $ffmpegStride")
                            return null
                        }
                    } else {
                        // Strides differ, copy row by row
                        // This handles cases where FFmpeg might add padding to rows.
                        for (y in 0 until height) {
                            imageBuffer.position(y * ffmpegStride) // Go to the start of the y-th row in FFmpeg buffer
                            if (imageBuffer.remaining() >= bufferedImageStride) { // Check if enough data for one BI row
                                imageBuffer.get(imgData, y * bufferedImageStride, bufferedImageStride) // Copy one BI row
                            } else {
                                println("FrameToBufferedImageConverter (BGR24): Buffer underflow for row $y. FFmpeg remaining: ${imageBuffer.remaining()}, Required for row: $bufferedImageStride, FFMPEG Stride: $ffmpegStride")
                                return null
                            }
                        }
                    }
                }

                avutil.AV_PIX_FMT_BGRA -> {
                    // BufferedImage.TYPE_INT_ARGB (A,R,G,B in int memory)
                    // FFmpeg BGRA byte buffer is B,G,R,A
                    // int = (A << 24) | (R << 16) | (G << 8) | B
                    bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    val dataBuffer = bufferedImage.raster.dataBuffer as DataBufferInt
                    val imgData = dataBuffer.data // int array
                    val ffmpegStride = frame.imageStride // Bytes per row in FFmpeg buffer
                    val pixelsPerRow = width

                    imageBuffer.order(ByteOrder.LITTLE_ENDIAN) // For consistent multi-byte reads if we were doing them

                    for (y in 0 until height) {
                        val rowStartInByteBuffer = y * ffmpegStride
                        for (x in 0 until pixelsPerRow) {
                            val pixelStartInByteBuffer = rowStartInByteBuffer + x * 4 // 4 bytes per BGRA pixel
                            if (pixelStartInByteBuffer + 3 < imageBuffer.limit()) {
                                val b = imageBuffer.get(pixelStartInByteBuffer + 0).toInt() and 0xFF
                                val g = imageBuffer.get(pixelStartInByteBuffer + 1).toInt() and 0xFF
                                val r = imageBuffer.get(pixelStartInByteBuffer + 2).toInt() and 0xFF
                                val a = imageBuffer.get(pixelStartInByteBuffer + 3).toInt() and 0xFF
                                imgData[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                            } else {
                                println("FrameToBufferedImageConverter (BGRA): Buffer underflow at y=$y, x=$x. Start: $pixelStartInByteBuffer, Limit: ${imageBuffer.limit()}, FFMPEG Stride: $ffmpegStride")
                                return null
                            }
                        }
                    }
                }

                avutil.AV_PIX_FMT_RGBA -> {
                    // BufferedImage.TYPE_INT_ARGB (A,R,G,B in int memory)
                    // FFmpeg RGBA byte buffer is R,G,B,A
                    // int = (A << 24) | (R << 16) | (G << 8) | B
                    bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    val dataBuffer = bufferedImage.raster.dataBuffer as DataBufferInt
                    val imgData = dataBuffer.data
                    val ffmpegStride = frame.imageStride
                    val pixelsPerRow = width

                    imageBuffer.order(ByteOrder.LITTLE_ENDIAN)

                    for (y in 0 until height) {
                        val rowStartInByteBuffer = y * ffmpegStride
                        for (x in 0 until pixelsPerRow) {
                            val pixelStartInByteBuffer = rowStartInByteBuffer + x * 4 // 4 bytes per RGBA pixel
                            if (pixelStartInByteBuffer + 3 < imageBuffer.limit()) {
                                val r = imageBuffer.get(pixelStartInByteBuffer + 0).toInt() and 0xFF
                                val g = imageBuffer.get(pixelStartInByteBuffer + 1).toInt() and 0xFF
                                val b = imageBuffer.get(pixelStartInByteBuffer + 2).toInt() and 0xFF
                                val a = imageBuffer.get(pixelStartInByteBuffer + 3).toInt() and 0xFF
                                imgData[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                            } else {
                                println("FrameToBufferedImageConverter (RGBA): Buffer underflow at y=$y, x=$x. Start: $pixelStartInByteBuffer, Limit: ${imageBuffer.limit()}, FFMPEG Stride: $ffmpegStride")
                                return null
                            }
                        }
                    }
                }

                // You could add a case for AV_PIX_FMT_ARGB to TYPE_INT_ARGB
                // FFmpeg ARGB (A,R,G,B) to Java's TYPE_INT_ARGB (A,R,G,B)
                // This would be a more direct mapping if FFmpeg provides it.
                avutil.AV_PIX_FMT_ARGB -> {
                    bufferedImage = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
                    val dataBuffer = bufferedImage.raster.dataBuffer as DataBufferInt
                    val imgData = dataBuffer.data // int array
                    val ffmpegStride = frame.imageStride
                    val pixelsPerRow = width

                    // Ensure buffer is in the order FFmpeg provides (usually native order, which is often little-endian for x86/ARM)
                    // For reading individual bytes, order doesn't matter, but for reading an int directly it would.
                    // imageBuffer.order(ByteOrder.nativeOrder()); // or ByteOrder.LITTLE_ENDIAN / BIG_ENDIAN if known

                    val intBuffer = imageBuffer.asIntBuffer() // More direct if byte order and alignment match

                    // Check if a direct IntBuffer copy is feasible (less likely due to potential stride differences or direct buffer issues)
                    // This is an OPTIMIZATION, the byte-by-byte swizzle below is safer.
                    if (ffmpegStride == width * 4 && imageBuffer.isDirect && imageBuffer.order() == ByteOrder.nativeOrder()) {
                        // Potentially faster if everything aligns perfectly and it's a direct buffer
                        // However, TYPE_INT_ARGB pixels in Java might have a specific component order (A,R,G,B)
                        // that might not match the direct int reading from FFmpeg's ARGB.
                        // So, byte-wise construction is safer.
                        // For simplicity and safety, stick to byte-wise swizzling.
                    }

                    // Byte-wise construction:
                    // FFmpeg ARGB byte buffer: A, R, G, B
                    // Java int (TYPE_INT_ARGB): (A << 24) | (R << 16) | (G << 8) | B
                    for (y in 0 until height) {
                        val rowStartInByteBuffer = y * ffmpegStride
                        for (x in 0 until pixelsPerRow) {
                            val pixelStartInByteBuffer = rowStartInByteBuffer + x * 4
                            if (pixelStartInByteBuffer + 3 < imageBuffer.limit()) {
                                // Assuming FFmpeg's ARGB is literally A then R then G then B in memory
                                val a = imageBuffer.get(pixelStartInByteBuffer + 0).toInt() and 0xFF
                                val r = imageBuffer.get(pixelStartInByteBuffer + 1).toInt() and 0xFF
                                val g = imageBuffer.get(pixelStartInByteBuffer + 2).toInt() and 0xFF
                                val b = imageBuffer.get(pixelStartInByteBuffer + 3).toInt() and 0xFF
                                imgData[y * width + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                            } else {
                                println("FrameToBufferedImageConverter (ARGB): Buffer underflow at y=$y, x=$x.")
                                return null
                            }
                        }
                    }
                }


                else -> {
                    println("FrameToBufferedImageConverter (direct): Format ${getPixelFormatName(actualFramePixelFormat)} not directly supported by this optimized converter.")
                    return null
                }
            }
        } catch (e: Exception) {
            println("FrameToBufferedImageConverter (direct): Exception during conversion for format ${getPixelFormatName(actualFramePixelFormat)}: ${e.message}")
            e.printStackTrace()
            return null
        }
        val functionEndTimeNs = System.nanoTime()
        val durationMs = (functionEndTimeNs - functionStartTimeNs) / 1_000_000
        if (bufferedImage != null) {
            // Only print if conversion was attempted and potentially successful
            println("FrameToBufferedImageConverter (direct): Conversion for ${getPixelFormatName(actualFramePixelFormat)} took $durationMs ms. Result not null: ${bufferedImage != null}")
        } else if (actualFramePixelFormat != avutil.AV_PIX_FMT_NONE) { // Avoid logging for NONE if no processing done
            println("FrameToBufferedImageConverter (direct): Conversion attempt for ${getPixelFormatName(actualFramePixelFormat)} took $durationMs ms. Result IS NULL.")
        }
        return bufferedImage
    }
}