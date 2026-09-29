package idv.neo.ffmpeg.media.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.media3.common.Player

@Composable
fun App(player: Player) {
    MaterialTheme {
        MainScreen(  player)
    }
}
