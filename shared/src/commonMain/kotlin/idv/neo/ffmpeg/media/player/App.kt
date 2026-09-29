package idv.neo.ffmpeg.media.player

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import androidx.media3.common.Player

@Composable
@Preview
fun App(player: Player? = null) {
    MaterialTheme {
        val actualPlayer = player ?: rememberPlayer()
        MainScreen(player = actualPlayer)
    }
}
