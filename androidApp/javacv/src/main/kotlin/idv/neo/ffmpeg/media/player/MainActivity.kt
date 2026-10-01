package idv.neo.ffmpeg.media.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.media3.common.MediaItem
import idv.neo.ffmpeg.media.player.core.JavaCvPlayer
import idv.neo.ffmpeg.media.player.core.audio.AndroidAudioSink
import idv.neo.ffmpeg.media.player.core.video.android.AndroidSurfaceVideoSink

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            val context = LocalContext.current
            val player = remember(context) {
                val videoSink = AndroidSurfaceVideoSink()
                val audioSink = AndroidAudioSink()
                JavaCvPlayer.create(videoSink, audioSink,options = mapOf(
                    "vcodec" to "h264_mediacodec",
                    "rtsp_transport" to "tcp",     // 強制使用 TCP，避免 UDP 丟包花屏
                    "stimeout" to "5000000",        // 連線超時時間 (微秒: 5 秒)
                    "probesize" to "1000000",       // 減少探測標頭大小，加快首幀開播
                    "analyzeduration" to "1000000", // 減少分析時間
                    "timeout" to "10000000",        // 注意：HTTP 使用 "timeout" 而不是 "stimeout"
                    "reconnect" to "1",             // 斷線自動重連
                    "reconnect_streamed" to "1",
                    "reconnect_delay_max" to "5",
                    "user_agent" to "JavaCvPlayer/1.0",
                    "fflags" to "nobuffer"          // 不緩衝，達到即時低延遲
                )).apply {
                    setMediaItem(MediaItem.fromUri("https://github.com/rambod-rahmani/ffmpeg-video-player/raw/refs/heads/master/Iron_Man-Trailer_HD.mp4"))
                    prepare()
                    play()
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
