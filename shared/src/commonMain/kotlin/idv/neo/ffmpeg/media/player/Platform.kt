package idv.neo.ffmpeg.media.player

interface Platform {
    val name: String
}
expect fun getPlatform(): Platform