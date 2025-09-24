package idv.neo.ffmpeg.media.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

expect abstract class SharedViewModel() {
    val viewModelScope: CoroutineScope
    protected open fun onCleared() // Corresponds to ViewModel.onCleared()
}

/**
 * ViewModel for the main media player screen.
 * It inherits from SharedViewModel to get viewModelScope and onCleared behavior.
 * Constructor remains parameterless for simplicity in commonMain.
 * Platform-specific dependencies (like Context for Android) will be handled
 * by the actual constructor and the getViewModel factory.
 */
expect class MainViewModel(platformArgs: Any? = null) : SharedViewModel {
    val countdownValue: StateFlow<Int>
    val videoFrameBitmap: StateFlow<ImageBitmap?>
    val errorMessage: StateFlow<String?>

    fun startVideoStreaming(videoUrl: String)
    fun stopVideoStreaming()
}

/**
 * Composable function to get an instance of the MainViewModel.
 */
@Composable
expect fun getViewModel(): MainViewModel