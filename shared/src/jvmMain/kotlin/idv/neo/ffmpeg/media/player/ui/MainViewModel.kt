package idv.neo.ffmpeg.media.player.ui

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import idv.neo.ffmpeg.media.player.core.UniversalFrameConverter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.bytedeco.javacv.Frame
import idv.neo.ffmpeg.media.player.core.JavaFxSwingComposeFFmpegPlayer
import idv.neo.ffmpeg.media.player.core.PlayerEvent
import java.util.logging.Level
import java.util.logging.Logger

class MainViewModel : ViewModel() {
    private val _countdownValue = MutableStateFlow(10)
    val countdownValue: StateFlow<Int> = _countdownValue.asStateFlow()

    private val _videoFrameBitmap = MutableStateFlow<ImageBitmap?>(null)
    val videoFrameBitmap: StateFlow<ImageBitmap?> = _videoFrameBitmap.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var countdownJob: Job? = null
    private var player: JavaFxSwingComposeFFmpegPlayer? = null
    private var currentActualPixelFormat: Int = -1 // Store pixel format from PlayerEvent

    companion object {
        private val LOG = Logger.getLogger(MainViewModel::class.java.name)
    }

    init {
        startCountdown()
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            for (i in 10 downTo 0) {
                _countdownValue.value = i
                if (i == 0) break
                kotlinx.coroutines.delay(1000L)
            }
        }
    }

    fun startVideoStreaming(videoUrl: String) {
        LOG.info("MainViewModel: startVideoStreaming called with URL: $videoUrl")
        player?.close()
        _videoFrameBitmap.value = null
        _errorMessage.value = null
        currentActualPixelFormat = -1

        val videoCallback: (Frame, Long) -> Unit = { frame, _ ->
            if (currentActualPixelFormat != -1) {
                val bitmap = UniversalFrameConverter.convertToImageBitmap(frame, currentActualPixelFormat)
                viewModelScope.launch(Dispatchers.Main.immediate) {
                    _videoFrameBitmap.value = bitmap
                }
            } else {
                LOG.warning("Pixel format not yet known, cannot convert frame to ImageBitmap.")
            }
        }

        val eventCallback: (PlayerEvent) -> Unit = { event ->
            viewModelScope.launch(Dispatchers.Main.immediate) {
                when (event) {
                    is PlayerEvent.VideoDimensionsDetected -> {
                        LOG.info("PlayerEvent: VideoDimensionsDetected - ${event.width}x${event.height}, Format: ${event.pixelFormat}, FPS: ${event.frameRate}")
                        currentActualPixelFormat = event.pixelFormat
                        // You could use width/height here to adjust UI if needed
                    }
                    is PlayerEvent.PlaybackStarted -> {
                        LOG.info("PlayerEvent: PlaybackStarted")
                        _errorMessage.value = null // Clear previous errors
                    }
                    is PlayerEvent.EndOfMedia -> {
                        LOG.info("PlayerEvent: EndOfMedia")
                        _videoFrameBitmap.value = null // Clear last frame
                        // Handle UI changes, e.g., show replay button
                    }
                    is PlayerEvent.Error -> {
                        LOG.log(Level.SEVERE, "PlayerEvent: Error - ${event.errorMessage}", event.exception)
                        _errorMessage.value = "Player Error: ${event.errorMessage}"
                        _videoFrameBitmap.value = null
                    }
                }
            }
        }

        try {
            player = JavaFxSwingComposeFFmpegPlayer(videoCallback, eventCallback, null)
            LOG.info("MainViewModel: JavaFxSwingComposeFFmpegPlayer instance created. Calling start().")
            player?.start(videoUrl) // This should launch the player's internal coroutine
            LOG.info("MainViewModel: player.start() called.")
        } catch (e: Exception) {
            LOG.log(Level.SEVERE, "MainViewModel: Failed to create or start player", e)
            _errorMessage.value = "Failed to initialize player: ${e.message}"
        }
    }

    fun stopVideoStreaming() {
        LOG.info("MainViewModel: stopVideoStreaming called.")
        player?.stop() // Or player?.close() if you want full cleanup
        // UI updates for stop are typically handled via EndOfMedia or Error events
        // or you can manually reset states here if needed.
        // _videoFrameBitmap.value = null; // Optionally clear immediately
    }

    override fun onCleared() {
        LOG.info("MainViewModel: onCleared called. Cleaning up player.")
        super.onCleared()
        countdownJob?.cancel()
        player?.close() // Ensure player resources are released
        player = null
        LOG.info("MainViewModel: Player resources released.")
    }
}