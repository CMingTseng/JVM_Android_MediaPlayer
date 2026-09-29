package idv.neo.ffmpeg.media.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import idv.neo.ffmpeg.media.player.ui.compose.VideoPlayerCanvas

/**
 * 跨平台的示範畫面， 使用 Jetbrains Compose 建立的 :ui-compose 與 :ui-compose-material3 媒體組件。
 */
@OptIn(UnstableApi::class)
@Composable
fun MainScreen(
    player: Player
) {
    val testUrls = listOf(
        "https://github.com/rambod-rahmani/ffmpeg-video-player/raw/refs/heads/master/Iron_Man-Trailer_HD.mp4",
    )
    var videoUrl by remember { mutableStateOf(testUrls[0]) }
    var showUrlList by remember { mutableStateOf(false) }
    
    var playbackState by remember { mutableStateOf(player.playbackState) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 監聽播放器狀態
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                playbackState = state
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                errorMessage = error.message
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // 頂部網址欄 (Top URL Bar)
        Column(modifier = Modifier.fillMaxWidth().background(Color.DarkGray.copy(alpha = 0.5f))) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = videoUrl,
                    onValueChange = { videoUrl = it },
                    modifier = Modifier.weight(1f),
                    textStyle = TextStyle(color = Color.White, fontSize = 12.sp),
                    label = { Text("Stream URL", color = Color.Gray) }
                )
                Button(
                    onClick = { showUrlList = !showUrlList },
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Text("URLs", fontSize = 10.sp)
                }
                Button(
                    onClick = { 
                        errorMessage = null
                        player.setMediaItem(MediaItem.fromUri(videoUrl))
                        player.prepare()
                        player.play()
                    },
                    modifier = Modifier.padding(start = 4.dp)
                ) {
                    Text("Load")
                }
            }

            if (showUrlList) {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                    testUrls.forEach { url ->
                        TextButton(
                            onClick = {
                                videoUrl = url
                                showUrlList = false
                                errorMessage = null
                                player.setMediaItem(MediaItem.fromUri(url))
                                player.prepare()
                                player.play()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(url, color = Color.Cyan, fontSize = 10.sp, maxLines = 1)
                        }
                    }
                }
            }
        }

        // 主播放區域 (Main Player Area)
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            // 使用跨平台的 VideoPlayerCanvas
            VideoPlayerCanvas(
                player = player,
                modifier = Modifier.fillMaxSize()
            )

            // 緩衝中提示 (Buffering Overlay)
            if (playbackState == Player.STATE_BUFFERING) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = Color.Red
                )
            }

            // 錯誤訊息 (Error Overlay)
            errorMessage?.let { msg ->
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Text(
                        text = msg,
                        color = Color.Red,
                        modifier = Modifier.padding(16.dp),
                        style = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    )
                }
            }

            // 控制器 (PlayerControlView Overlay)
            PlayerControlView(
                player = player,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
            )
        }
    }
}
