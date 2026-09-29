@file:JvmName("SharedPlatformJvm")

package idv.neo.ffmpeg.media.player

class JVMPlatform: Platform {
    override val name: String = "Java ${System.getProperty("java.version")}"
}

actual fun getPlatform(): Platform = JVMPlatform()
