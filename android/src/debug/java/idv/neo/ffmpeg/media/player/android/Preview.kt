package idv.neo.ffmpeg.media.player.android

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import idv.neo.ffmpeg.media.player.theme.AppTheme
import idv.neo.ffmpeg.media.player.component.GreetingView

@Preview
@Composable
fun DefaultPreview() {
    AppTheme {
        GreetingView("Hello, Android!")
    }
}
