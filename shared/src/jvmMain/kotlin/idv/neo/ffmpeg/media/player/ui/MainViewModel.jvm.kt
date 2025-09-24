package idv.neo.ffmpeg.media.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import idv.neo.ffmpeg.media.player.core.JavaFxSwingComposeFFmpegPlayer
import idv.neo.ffmpeg.media.player.core.PlayerEvent
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay // Use kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bytedeco.javacv.Frame

import idv.neo.ffmpeg.media.player.core.UniversalFrameConverter
import java.util.logging.Level
import java.util.logging.Logger


actual abstract class SharedViewModel actual constructor() {
    actual val viewModelScope: CoroutineScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob() + CoroutineName("JvmSharedViewModelScope"))

    protected actual open fun onCleared() {
        Log.i(TAG_JVM, "JvmSharedViewModel: onCleared - cancelling viewModelScope.")
        viewModelScope.cancel()
    }
}
private const val TAG_JVM = "JvmMainViewModel"

actual class MainViewModel actual constructor(
    @Suppress("UNUSED_PARAMETER") platformArgs: Any? // Matches expect
) : SharedViewModel() {
    private val _countdownValue = MutableStateFlow(10)
    actual val countdownValue: StateFlow<Int> = _countdownValue.asStateFlow()

    private val _videoFrameBitmap = MutableStateFlow<ImageBitmap?>(null)
    actual val videoFrameBitmap: StateFlow<ImageBitmap?> = _videoFrameBitmap.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    actual val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var countdownJob: Job? = null
    private var player: JavaFxSwingComposeFFmpegPlayer? = null
    private var currentActualPixelFormat: Int = -1


    init {
        Log.i(TAG_JVM, "JvmMainViewModel instance created.")
        startCountdown()
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (i in 10 downTo 0) {
                _countdownValue.value = i
                if (i == 0) break
                delay(1000L)
            }
        }
    }

    actual fun startVideoStreaming(videoUrl: String) {
        Log.i(TAG_JVM, "startVideoStreaming called with URL: $videoUrl")
        // ... (JVM player logic using viewModelScope)
        player?.close()
        _videoFrameBitmap.value = null
        _errorMessage.value = null
        currentActualPixelFormat = -1

        val videoCallback: (Frame, Long) -> Unit = { frame, _ ->
            if (currentActualPixelFormat != -1) {
                val bitmap = UniversalFrameConverter.convertToImageBitmap(frame, currentActualPixelFormat)
                viewModelScope.launch {
                    _videoFrameBitmap.value = bitmap
                }
            } else {
                Log.w(TAG_JVM, "Pixel format not yet known.")
            }
        }

        val eventCallback: (PlayerEvent) -> Unit = { event ->
            viewModelScope.launch {
                when (event) {
                    is PlayerEvent.VideoDimensionsDetected -> {
                        Log.i(TAG_JVM, "PlayerEvent: VideoDimensionsDetected - ${event.width}x${event.height}, Format: ${event.pixelFormat}")
                        currentActualPixelFormat = event.pixelFormat
                    }
                    is PlayerEvent.PlaybackStarted -> {
                        Log.i(TAG_JVM, "PlayerEvent: PlaybackStarted")
                        _errorMessage.value = null
                    }
                    is PlayerEvent.EndOfMedia -> {
                        Log.i(TAG_JVM, "PlayerEvent: EndOfMedia")
                        _videoFrameBitmap.value = null
                    }
                    is PlayerEvent.Error -> {
                        Log.e(TAG_JVM, "PlayerEvent: Error - ${event.errorMessage}", event.exception)
                        _errorMessage.value = "Player Error: ${event.errorMessage}"
                        _videoFrameBitmap.value = null
                    }
                }
            }
        }
        try {
            player = JavaFxSwingComposeFFmpegPlayer(videoCallback, eventCallback, null)
            player?.start(videoUrl)
        } catch (e: Exception) {
            Log.e(TAG_JVM, "Failed to create or start player", e)
            _errorMessage.value = "Failed to initialize player: ${e.message}"
        }
    }

    actual fun stopVideoStreaming() {
        Log.i(TAG_JVM, "stopVideoStreaming called.")
        player?.stop()
    }

    /**
     * This is the JVM's implementation of SharedViewModel.onCleared().
     * It will call super.onCleared() which cancels the JvmSharedViewModelScope.
     * Then, it performs MainViewModel-specific cleanup.
     */
    override fun onCleared() {
        Log.i(TAG_JVM, "JvmMainViewModel: onCleared called. Cleaning up player.")
        super.onCleared() // Cancels JvmSharedViewModelScope
        countdownJob?.cancel()
        player?.close()
        player = null
        Log.i(TAG_JVM, "JvmMainViewModel: Player resources released.")
    }
}
// Simple Log object for JVM if not already available
object Log {
    private val logger = Logger.getLogger("JVM_MEDIA_PLAYER")
    fun i(tag: String, message: String) = logger.info("[$tag] $message")
    fun w(tag: String, message: String) = logger.warning("[$tag] $message")
    fun e(tag: String, message: String, throwable: Throwable? = null) = logger.log(Level.SEVERE, "[$tag] $message", throwable)
}

@Composable
actual fun getViewModel(): MainViewModel = remember { MainViewModel(null) }