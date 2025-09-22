package idv.neo.ffmpeg.media.player.core.utils

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.Frame

fun getPixelFormatName(pixelFormat: Int): String {
    return when (pixelFormat) {
        avutil.AV_PIX_FMT_RGB24 -> "AV_PIX_FMT_RGB24"
        avutil.AV_PIX_FMT_BGR24 -> "AV_PIX_FMT_BGR24"
        avutil.AV_PIX_FMT_NONE -> "AV_PIX_FMT_NONE"
        avutil.AV_PIX_FMT_YUV420P -> "AV_PIX_FMT_YUV420P"
        avutil.AV_PIX_FMT_YUYV422 -> "AV_PIX_FMT_YUYV422"
        avutil.AV_PIX_FMT_YUV422P -> "AV_PIX_FMT_YUV422P"
        avutil.AV_PIX_FMT_YUV444P -> "AV_PIX_FMT_YUV444P"
        avutil.AV_PIX_FMT_YUV410P -> "AV_PIX_FMT_YUV410P"
        avutil.AV_PIX_FMT_YUV411P -> "AV_PIX_FMT_YUV411P"
        avutil.AV_PIX_FMT_GRAY8 -> "AV_PIX_FMT_GRAY8"
        avutil.AV_PIX_FMT_MONOWHITE -> "AV_PIX_FMT_MONOWHITE"
        avutil.AV_PIX_FMT_MONOBLACK -> "AV_PIX_FMT_MONOBLACK"
        avutil.AV_PIX_FMT_PAL8 -> "AV_PIX_FMT_PAL8"
        avutil.AV_PIX_FMT_YUVJ420P -> "AV_PIX_FMT_YUVJ420P"
        avutil.AV_PIX_FMT_YUVJ422P -> "AV_PIX_FMT_YUVJ422P"
        avutil.AV_PIX_FMT_YUVJ444P -> "AV_PIX_FMT_YUVJ444P"
        avutil.AV_PIX_FMT_UYVY422 -> "AV_PIX_FMT_UYVY422"
        avutil.AV_PIX_FMT_UYYVYY411 -> "AV_PIX_FMT_UYYVYY411"
        avutil.AV_PIX_FMT_BGR8 -> "AV_PIX_FMT_BGR8"
        avutil.AV_PIX_FMT_BGR4 -> "AV_PIX_FMT_BGR4"
        avutil.AV_PIX_FMT_BGR4_BYTE -> "AV_PIX_FMT_BGR4_BYTE"
        avutil.AV_PIX_FMT_RGB8 -> "AV_PIX_FMT_RGB8"
        avutil.AV_PIX_FMT_RGB4 -> "AV_PIX_FMT_RGB4"
        avutil.AV_PIX_FMT_RGB4_BYTE -> "AV_PIX_FMT_RGB4_BYTE"
        avutil.AV_PIX_FMT_NV12 -> "AV_PIX_FMT_NV12"
        avutil.AV_PIX_FMT_NV21 -> "AV_PIX_FMT_NV21"
        avutil.AV_PIX_FMT_ARGB -> "AV_PIX_FMT_ARGB"
        avutil.AV_PIX_FMT_RGBA -> "AV_PIX_FMT_RGBA"
        avutil.AV_PIX_FMT_ABGR -> "AV_PIX_FMT_ABGR"
        avutil.AV_PIX_FMT_BGRA -> "AV_PIX_FMT_BGRA"
        else -> "Unknown Pixel Format ($pixelFormat)"
    }
}

fun printDetailedFrameInfo(title: String, frame: Frame) {
    println("\n--- $title ---")
    println("  Timestamp (µs): ${frame.timestamp}")
    println("  Type: ${frame.type}, Stream Index: ${frame.streamIndex}")
    println("  Key Frame: ${frame.keyFrame}, Picture Type: ${frame.pictType}")

    if (frame.image != null && (frame.type == Frame.Type.VIDEO || frame.imageWidth > 0)) {
        println("  Image: ${frame.imageWidth}x${frame.imageHeight}, Depth: ${frame.imageDepth}, Channels: ${frame.imageChannels}, Stride: ${frame.imageStride}")
        if (frame.image.isNotEmpty()) {
            println("  Image Buffers (${frame.image.size}):")
            frame.image.forEachIndexed { i, buf ->
                if (buf != null) {
                    println("    Plane $i: ${buf.javaClass.simpleName}, Capacity=${buf.capacity()}, Limit=${buf.limit()}, Remaining=${buf.remaining()}, HasArray=${buf.hasArray()}")
                } else {
                    println("    Plane $i: null")
                }
            }
        } else {
            println("  Image Buffers: empty array")
        }
    } else if (frame.type == Frame.Type.VIDEO) {
        println("  Image: (image data is null or dimensions are zero)")
    }


    if (frame.samples != null && (frame.type == Frame.Type.AUDIO || frame.audioChannels > 0)) {
        println("  Audio: SampleRate=${frame.sampleRate}, Channels=${frame.audioChannels}")
        if (frame.samples.isNotEmpty()) {
            println("  Sample Buffers (${frame.samples.size}):")
            frame.samples.forEachIndexed { i, buf ->
                if (buf != null) {
                    println("    Sample Plane $i: ${buf.javaClass.simpleName}, Capacity=${buf.capacity()}, Limit=${buf.limit()}, Remaining=${buf.remaining()}, HasArray=${buf.hasArray()}")
                } else {
                    println("    Sample Plane $i: null")
                }
            }
        } else {
            println("  Sample Buffers: empty array")
        }
    } else if (frame.type == Frame.Type.AUDIO) {
        println("  Audio: (sample data is null or channels are zero)")
    }


    if (frame.data != null) {
        println("  Data Buffer: ${frame.data.javaClass.simpleName}, Capacity=${frame.data.capacity()}, Limit=${frame.data.limit()}, Remaining=${frame.data.remaining()}")
    }

    println("  Opaque: ${frame.opaque}") // Often a pointer to native structure
}