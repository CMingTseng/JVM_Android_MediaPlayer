package idv.neo.ffmpeg.media.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val player = remember(context) {
                ExoPlayer.Builder(context).build().apply {
                    setMediaItem(MediaItem.fromUri("https://github.com/rambod-rahmani/ffmpeg-video-player/raw/refs/heads/master/Iron_Man-Trailer_HD.mp4"))
                    prepare()
                    playWhenReady = true
                }
            }
            DisposableEffect(player) {
                onDispose {
                    player.release()
                }
            }
            App(player)
        }
    }
}
