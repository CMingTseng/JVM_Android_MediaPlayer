// File: /shared/src/androidMain/kotlin/idv/neo/ffmpeg/media/player/ui/MainViewModel.android.kt
package idv.neo.ffmpeg.media.player.ui

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy

import android.view.SurfaceHolder // Keep this if used, though may not be directly needed for basic logging
import android.view.SurfaceView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope as androidViewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
// import kotlinx.coroutines.suspendCancellableCoroutine // Not strictly needed for logging-only version
// import kotlin.coroutines.resume // Not strictly needed for logging-only version

actual abstract class SharedViewModel actual constructor() : ViewModel() {
    actual  val viewModelScope: CoroutineScope get() = androidViewModelScope
    protected actual override fun onCleared() {
        super.onCleared()
        Log.i(TAG_ANDROID, "Android SharedViewModel: onCleared.")
    }
}

private const val TAG_ANDROID = "AndroidMainViewModel"

//FIXME fail  Jetpack Media3 (ExoPlayer)  can not get ImageBitmap
actual class MainViewModel actual constructor(
    platformArgs: Any?
) : SharedViewModel() {

    private val applicationContext: Context = run {
        val appCtx = when (platformArgs) {
            is Context -> platformArgs.applicationContext
            is Application -> platformArgs
            else -> null
        }
        appCtx ?: throw IllegalArgumentException(
            "Android MainViewModel requires a Context or Application " +
                    "passed via platformArgs. Received: ${platformArgs?.javaClass?.name ?: "null"}"
        )
    }

    private val _countdownValue = MutableStateFlow(10)
    actual val countdownValue: StateFlow<Int> = _countdownValue.asStateFlow()

    private val _videoFrameBitmap = MutableStateFlow<ImageBitmap?>(null)
    actual val videoFrameBitmap: StateFlow<ImageBitmap?> = _videoFrameBitmap.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    actual val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var countdownJob: Job? = null
    private var exoPlayer: ExoPlayer? = null
    private var frameCaptureJob: Job? = null
    private var surfaceViewForFrameCapture: SurfaceView? = null
    private var lastRenderedBitmap: Bitmap? = null // Still useful for managing the current bitmap

    companion object {
        private const val FRAME_CAPTURE_INTERVAL_MS = 66L
        // Simpler retry for logging version
        private const val PIXEL_COPY_MAX_RETRIES_LOGGING = 3
    }

    init {

        Log.i(TAG_ANDROID, "[Init] Android MainViewModel Initializing with context: $applicationContext")
        viewModelScope.launch {
            delay(100)
            Log.d(TAG_ANDROID, "[Init] Delayed: _countdownValue is ${_countdownValue.value} before startCountdown")
            startCountdown()
        }
        viewModelScope.launch {
            delay(100) // Also delay player initialization slightly
            Log.d(TAG_ANDROID, "[Init] Delayed: _videoFrameBitmap is ${_videoFrameBitmap.value} before initializePlayer")
            initializePlayer()
        }
        Log.i(TAG_ANDROID, "[Init] MainViewModel init block finished.")
    }

    private fun startCountdown() {
        Log.i(TAG_ANDROID, "[Countdown] startCountdown called. _countdownValue: ${_countdownValue.value}")
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            Log.d(TAG_ANDROID, "[Countdown] Coroutine started.")
            for (i in 10 downTo 0) {
                if (!isActive) {
                    Log.d(TAG_ANDROID, "[Countdown] Coroutine cancelled, exiting loop.")
                    break
                }
                _countdownValue.value = i
                Log.d(TAG_ANDROID, "[Countdown] Value set to: $i")
                if (i == 0) break
                delay(1000L)
            }
            Log.d(TAG_ANDROID, "[Countdown] Coroutine finished.")
        }
    }

    @OptIn(UnstableApi::class)
    private fun initializePlayer() {
        Log.i(TAG_ANDROID, "[PlayerInit] initializePlayer called.")
        if (exoPlayer == null) {
            Log.d(TAG_ANDROID, "[PlayerInit] Creating ExoPlayer instance.")
            exoPlayer = ExoPlayer.Builder(this.applicationContext)
                .setLooper(Looper.getMainLooper()) // Important for UI thread interactions
                .build()
            setupPlayerListener() // Setup listener AFTER player is created
            Log.i(TAG_ANDROID, "[PlayerInit] ExoPlayer instance created: ${exoPlayer.hashCode()}")
        } else {
            Log.d(TAG_ANDROID, "[PlayerInit] ExoPlayer already initialized: ${exoPlayer.hashCode()}")
        }
    }


    private fun setupPlayerListener() {
        val playerInstance = exoPlayer ?: run {
            Log.e(TAG_ANDROID, "[PlayerListener] ExoPlayer is null, cannot setup listener.")
            return
        }
        Log.i(TAG_ANDROID, "[PlayerListener] Setting up listener for ExoPlayer: ${playerInstance.hashCode()}")
        playerInstance.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                val isPlaying = playerInstance.isPlaying
                val currentVideoSize = playerInstance.videoSize // Get current size for logging
                val stateString = getPlayerStateString(playbackState)
                Log.i(TAG_ANDROID, "[PlayerListener] onPlaybackStateChanged - State: $stateString, isPlaying: $isPlaying, VideoSize: ${currentVideoSize.width}x${currentVideoSize.height}")

                when (playbackState) {
                    Player.STATE_IDLE -> {
                        _errorMessage.value = "Player Idle."
                        stopFrameCapture()
                    }
                    Player.STATE_BUFFERING -> {
                        _errorMessage.value = "Buffering..."
                        stopFrameCapture()
                    }
                    Player.STATE_READY -> {
                        _errorMessage.value = null
                        // **If ready, playing, AND we already have a valid video size, attempt to start.**
                        // **This handles cases where onVideoSizeChanged might have fired *before* isPlaying became true.**
                        if (isPlaying && currentVideoSize.width > 0 && currentVideoSize.height > 0) {
                            Log.d(TAG_ANDROID, "[PlayerListener] STATE_READY, isPlaying=true, and video size is ALREADY VALID (${currentVideoSize.width}x${currentVideoSize.height}). Attempting startFrameCapture().")
                            startFrameCapture()
                        } else if (isPlaying) {
                            Log.d(TAG_ANDROID, "[PlayerListener] STATE_READY, isPlaying=true, but video size is still 0x0 or UNKNOWN. Waiting for onVideoSizeChanged.")
                        } else {
                            Log.d(TAG_ANDROID, "[PlayerListener] STATE_READY, but not playing. Frame capture will wait for isPlaying=true and onVideoSizeChanged.")
                        }
                    }
                    Player.STATE_ENDED -> {
                        _errorMessage.value = "Playback ended."
                        _videoFrameBitmap.value = null
                        stopFrameCapture()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val currentVideoSize = playerInstance.videoSize // Get current size
                val stateString = getPlayerStateString(playerInstance.playbackState)
                Log.i(TAG_ANDROID, "[PlayerListener] onIsPlayingChanged - isPlaying: $isPlaying. Current PlaybackState: $stateString, VideoSize: ${currentVideoSize.width}x${currentVideoSize.height}")

                if (isPlaying) {
                    _errorMessage.value = null
                    // **If playing, READY, AND video size is valid, attempt to start.**
                    if (playerInstance.playbackState == Player.STATE_READY &&
                        currentVideoSize.width > 0 && currentVideoSize.height > 0) {
                        Log.d(TAG_ANDROID, "[PlayerListener] onIsPlayingChanged(true) & STATE_READY & video size VALID (${currentVideoSize.width}x${currentVideoSize.height}). Attempting startFrameCapture().")
                        startFrameCapture()
                    } else if (playerInstance.playbackState == Player.STATE_READY) {
                        Log.d(TAG_ANDROID, "[PlayerListener] onIsPlayingChanged(true) & STATE_READY, but video size is still 0x0 or UNKNOWN. Waiting for onVideoSizeChanged.")
                    } else {
                        Log.d(TAG_ANDROID, "[PlayerListener] onIsPlayingChanged(true) but not STATE_READY (State: $stateString). Frame capture will wait for READY and onVideoSizeChanged.")
                    }
                } else {
                    Log.d(TAG_ANDROID, "[PlayerListener] onIsPlayingChanged(false). Stopping frame capture.")
                    stopFrameCapture()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG_ANDROID, "[PlayerListener] onPlayerError: ${error.errorCodeName} - ${error.message}", error)
                _errorMessage.value = "Player Error: ${error.errorCodeName} (${error.errorCode})"
                _videoFrameBitmap.value = null
                stopFrameCapture()
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                // **THIS IS THE PRIMARY TRIGGER FOR FRAME CAPTURE ONCE SIZE IS KNOWN**
                Log.i(TAG_ANDROID, "[PlayerListener] >>> onVideoSizeChanged - Width: ${videoSize.width}, Height: ${videoSize.height}, PixelRatio: ${videoSize.pixelWidthHeightRatio} <<<")
                if (videoSize.width > 0 && videoSize.height > 0) {
                    // Video size is now valid.
                    if (playerInstance.isPlaying && playerInstance.playbackState == Player.STATE_READY) {
                        Log.i(TAG_ANDROID, "[PlayerListener] onVideoSizeChanged to valid (${videoSize.width}x${videoSize.height}) AND player isPlaying & READY. Attempting to (re)start frame capture.")
                        stopFrameCapture() // Stop any previous attempt
                        startFrameCapture()
                    } else {
                        Log.d(TAG_ANDROID, "[PlayerListener] onVideoSizeChanged to valid, but player not playing or not ready. isPlaying: ${playerInstance.isPlaying}, state: ${getPlayerStateString(playerInstance.playbackState)}. Frame capture will start if/when player starts/becomes ready.")
                    }
                } else {
                    Log.w(TAG_ANDROID, "[PlayerListener] onVideoSizeChanged to invalid or UNKNOWN (${videoSize.width}x${videoSize.height}). Stopping frame capture.")
                    stopFrameCapture()
                }
            }
        })
        Log.i(TAG_ANDROID, "[PlayerListener] Listener setup complete.")
    }

    @SuppressLint("NewApi")
    private fun startFrameCapture() {
        Log.i(TAG_ANDROID, "[FrameCapture] Attempting to start. Current frameCaptureJob active: ${frameCaptureJob?.isActive}")
        if (frameCaptureJob?.isActive == true) {
            Log.d(TAG_ANDROID, "[FrameCapture] Job already active. Exiting.")
            return
        }

        val currentPlayer = exoPlayer ?: run {
            Log.w(TAG_ANDROID, "[FrameCapture] ExoPlayer is null. Cannot start.")
            return
        }

        // We must have a valid video size here.
        val currentVideoSize = currentPlayer.videoSize
        Log.i(TAG_ANDROID, "[FrameCapture] Current videoSize for capture: ${currentVideoSize.width}x${currentVideoSize.height}")

        if (currentVideoSize == VideoSize.UNKNOWN || currentVideoSize.width <= 0 || currentVideoSize.height <= 0) {
            Log.w(TAG_ANDROID, "[FrameCapture] Invalid video size (${currentVideoSize.width}x${currentVideoSize.height}) at the moment of starting capture. This shouldn't happen if triggered by onVideoSizeChanged or after size is known.")
            // _errorMessage.value = "Cannot capture: Video size invalid." // Avoid flooding error message
            return // Do not proceed if size is invalid
        }

        // Only proceed if playing and ready (though ready might be implied if size is known and we are here)
        if (!currentPlayer.isPlaying) {
            Log.w(TAG_ANDROID, "[FrameCapture] Player is not playing. Cannot start capture. State: ${getPlayerStateString(currentPlayer.playbackState)}")
            return
        }
        if (currentPlayer.playbackState != Player.STATE_READY) {
            Log.w(TAG_ANDROID, "[FrameCapture] Player not in READY state (is ${getPlayerStateString(currentPlayer.playbackState)}). Cannot start capture yet.")
            return
        }


        val videoWidth = currentVideoSize.width
        val videoHeight = currentVideoSize.height

        // --- SurfaceView and PixelCopy Loop (Simplified from your previous working version for countdown) ---
        if (surfaceViewForFrameCapture == null) {
            Log.d(TAG_ANDROID, "[FrameCapture] Creating new SurfaceView.")
            surfaceViewForFrameCapture = SurfaceView(applicationContext)
        }
        val localSurfaceView = surfaceViewForFrameCapture!!

        Log.d(TAG_ANDROID, "[FrameCapture] Setting video surface view for ExoPlayer: ${currentPlayer.hashCode()} to SurfaceView: ${localSurfaceView.hashCode()}")
        currentPlayer.setVideoSurfaceView(localSurfaceView) // ExoPlayer handles thread internally

        frameCaptureJob?.cancel() // Cancel previous job
        frameCaptureJob = viewModelScope.launch(Dispatchers.Default + CoroutineName("PixelCopyLoop-Logging")) {
            Log.i(TAG_ANDROID, "[PixelCopyLoop] Started for ${videoWidth}x$videoHeight. Surface valid check: ${localSurfaceView.holder.surface?.isValid}. Player playing: ${currentPlayer.isPlaying}")
            var pixelCopyRetries = 0

            var surfaceReady = false
            for(attempt in 1..10) { // Wait up to 1 sec for surface
                if (localSurfaceView.holder.surface != null && localSurfaceView.holder.surface.isValid) {
                    Log.d(TAG_ANDROID, "[PixelCopyLoop] Surface became valid after ${attempt * 100}ms (approx).")
                    surfaceReady = true
                    break
                }
                if (!isActive) { Log.d(TAG_ANDROID, "[PixelCopyLoop] Coroutine cancelled during surface wait."); break }
                Log.d(TAG_ANDROID, "[PixelCopyLoop] Waiting for surface to be valid (attempt $attempt)...")
                delay(100)
            }

            if (!surfaceReady) {
                Log.e(TAG_ANDROID, "[PixelCopyLoop] Surface did not become valid. Exiting PixelCopyLoop.")
                viewModelScope.launch(Dispatchers.Main.immediate) { _errorMessage.value = "Surface for capture not ready."}
                // stopFrameCapture() // This might be too aggressive, let the calling logic handle it
                return@launch
            }

            while (isActive && currentPlayer.isPlaying && currentPlayer.playbackState == Player.STATE_READY) {
                // Defensive check inside loop, though surfaceReady was true
                if (localSurfaceView.holder.surface == null || !localSurfaceView.holder.surface.isValid) {
                    Log.w(TAG_ANDROID, "[PixelCopyLoop] Surface became invalid during loop. Retrying or exiting.")
                    delay(200)
                    if (localSurfaceView.holder.surface == null || !localSurfaceView.holder.surface.isValid) {
                        Log.e(TAG_ANDROID, "[PixelCopyLoop] Surface still invalid. Exiting loop.")
                        break
                    }
                }
                // Re-check actual video dimensions in case they changed without listener call
                // (though onVideoSizeChanged should handle this better)
                val loopVideoWidth = currentPlayer.videoSize.width
                val loopVideoHeight = currentPlayer.videoSize.height

                if (loopVideoWidth <= 0 || loopVideoHeight <= 0 || loopVideoWidth != videoWidth || loopVideoHeight != videoHeight) {
                    Log.w(TAG_ANDROID, "[PixelCopyLoop] Video dimensions changed or became invalid during loop. Initial: ${videoWidth}x$videoHeight, Current: ${loopVideoWidth}x$loopVideoHeight. Restarting capture for safety.")
                    viewModelScope.launch(Dispatchers.Main.immediate) {
                        stopFrameCapture()
                        if(currentPlayer.isPlaying && currentPlayer.videoSize.width > 0) startFrameCapture()
                    }
                    break // Exit current loop, let it restart
                }

                val bitmapToCopy: Bitmap = try {
                    Bitmap.createBitmap(videoWidth, videoHeight, Bitmap.Config.ARGB_8888)
                } catch (e: Exception) {
                    Log.e(TAG_ANDROID, "[PixelCopyLoop] Failed to create Bitmap: ${e.message}. Stopping loop.", e)
                    break
                }

                Log.v(TAG_ANDROID, "[PixelCopyLoop] Attempting PixelCopy (${pixelCopyRetries + 1}).")

                val listener = PixelCopy.OnPixelCopyFinishedListener { result ->
                    if (!isActive) {
                        Log.w(TAG_ANDROID, "[PixelCopyCb] Coroutine no longer active. Recycling bitmap. Result: $result")
                        if (!bitmapToCopy.isRecycled) bitmapToCopy.recycle()
                        return@OnPixelCopyFinishedListener
                    }
                    if (result == PixelCopy.SUCCESS) {
                        Log.d(TAG_ANDROID, "[PixelCopyCb] SUCCESS.")
                        pixelCopyRetries = 0
                        viewModelScope.launch(Dispatchers.Main.immediate) {
                            if (isActive && !bitmapToCopy.isRecycled) {
                                Log.i(TAG_ANDROID, "[PixelCopyCb] Updating _videoFrameBitmap. Hash: ${bitmapToCopy.hashCode()}")
                                lastRenderedBitmap?.recycle()
                                lastRenderedBitmap = bitmapToCopy
                                _videoFrameBitmap.value = bitmapToCopy.asImageBitmap()
                            } else {
                                if (!bitmapToCopy.isRecycled) bitmapToCopy.recycle()
                            }
                        }
                    } else {
                        Log.w(TAG_ANDROID, "[PixelCopyCb] FAILED with result: $result. Retries left: ${PIXEL_COPY_MAX_RETRIES_LOGGING - pixelCopyRetries}")
                        if (!bitmapToCopy.isRecycled) bitmapToCopy.recycle()
                        pixelCopyRetries++
                    }
                }
                try {
                    val handler = localSurfaceView.handler ?: Handler(Looper.getMainLooper())
                    PixelCopy.request(localSurfaceView, bitmapToCopy, listener, handler)
                } catch (e: Exception) {
                    Log.e(TAG_ANDROID, "[PixelCopyLoop] PixelCopy.request direct exception: ${e.message}", e)
                    if (!bitmapToCopy.isRecycled) bitmapToCopy.recycle()
                    pixelCopyRetries++
                }

                if (pixelCopyRetries > PIXEL_COPY_MAX_RETRIES_LOGGING) {
                    Log.e(TAG_ANDROID, "[PixelCopyLoop] Max PixelCopy retries reached. Stopping capture.")
                    viewModelScope.launch(Dispatchers.Main.immediate) { _errorMessage.value = "Frame capture failed (PixelCopy)." }
                    break
                }
                delay(FRAME_CAPTURE_INTERVAL_MS)
            }
            Log.i(TAG_ANDROID, "[PixelCopyLoop] Loop finished. isActive: $isActive, isPlaying: ${currentPlayer.isPlaying}, state: ${getPlayerStateString(currentPlayer.playbackState)}")
            if (isActive && _videoFrameBitmap.value != null && !currentPlayer.isPlaying) {
                _videoFrameBitmap.value = null // Clear frame if player stopped but job active
            }
            lastRenderedBitmap?.recycle()
            lastRenderedBitmap = null
        }
        Log.i(TAG_ANDROID, "[FrameCapture] Frame capture job launched: ${frameCaptureJob?.hashCode()}")
    }


    private fun stopFrameCapture() {
        Log.i(TAG_ANDROID, "[FrameCaptureControl] stopFrameCapture called. Current job: ${frameCaptureJob?.hashCode()}")
        frameCaptureJob?.cancel()
        frameCaptureJob = null

        surfaceViewForFrameCapture?.let { sfv ->
            Log.d(TAG_ANDROID, "[FrameCaptureControl] Clearing video surface from ExoPlayer: ${exoPlayer?.hashCode()}")
            exoPlayer?.clearVideoSurfaceView(sfv) // Clear surface from player
        }
        // Don't nullify surfaceViewForFrameCapture here, might be reused. It's cleared in onCleared.

        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (_videoFrameBitmap.value != null) {
                Log.d(TAG_ANDROID, "[FrameCaptureControl] Clearing _videoFrameBitmap in stopFrameCapture.")
                _videoFrameBitmap.value = null
            }
        }
        lastRenderedBitmap?.recycle() // Clean up any held bitmap
        lastRenderedBitmap = null
        Log.d(TAG_ANDROID, "[FrameCaptureControl] Frame capture stopped and resources potentially cleaned.")
    }

    actual fun startVideoStreaming(videoUrl: String) {
        Log.i(TAG_ANDROID, "[StreamingControl] startVideoStreaming with URL: $videoUrl")
        viewModelScope.launch {
            _videoFrameBitmap.value = null
            _errorMessage.value = null
            stopFrameCapture() // Full stop before starting new

            if (exoPlayer == null) {
                Log.w(TAG_ANDROID, "[StreamingControl] ExoPlayer was null. Re-initializing.")
                initializePlayer() // This ensures listener is also setup
                if (exoPlayer == null) {
                    Log.e(TAG_ANDROID, "[StreamingControl] ExoPlayer still null. Cannot start.")
                    _errorMessage.value = "Player initialization failed."
                    return@launch
                }
            }
            Log.d(TAG_ANDROID, "[StreamingControl] ExoPlayer instance for streaming: ${exoPlayer.hashCode()}")
            val mediaItem = MediaItem.fromUri(videoUrl)
            exoPlayer?.run {
                setMediaItem(mediaItem)
                prepare()
                playWhenReady = true
                Log.i(TAG_ANDROID, "[StreamingControl] ExoPlayer: MediaItem set, prepared, playWhenReady=true.")
            }
        }
    }


    private fun getPlayerStateString(playbackState: Int): String {
        return when (playbackState) {
            Player.STATE_IDLE -> "STATE_IDLE"
            Player.STATE_BUFFERING -> "STATE_BUFFERING"
            Player.STATE_READY -> "STATE_READY"
            Player.STATE_ENDED -> "STATE_ENDED"
            else -> "STATE_UNKNOWN ($playbackState)"
        }
    }

    actual fun stopVideoStreaming() {
        Log.i(TAG_ANDROID, "[StreamingControl] stopVideoStreaming called.")
        viewModelScope.launch {
            exoPlayer?.stop()
            exoPlayer?.clearMediaItems()
            stopFrameCapture()
            _errorMessage.value = null // Clear any transient errors
        }
        Log.i(TAG_ANDROID, "[StreamingControl] stopVideoStreaming processing finished.")
    }

    override fun onCleared() {
        Log.i(TAG_ANDROID, "[ViewModelLifecycle] onCleared called.")
        countdownJob?.cancel()
        stopVideoStreaming() // Handles player release and frame capture stop

        exoPlayer?.release() // Explicit release just in case
        exoPlayer = null
        surfaceViewForFrameCapture = null
        lastRenderedBitmap?.recycle()
        lastRenderedBitmap = null
        Log.i(TAG_ANDROID, "[ViewModelLifecycle] All resources released.")
        super.onCleared()
    }
}

class MainViewModelFactory(private val applicationContextToPass: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainViewModel(applicationContextToPass) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

@Composable
actual fun getViewModel(): MainViewModel {
    val context = LocalContext.current.applicationContext
    return viewModel(factory = MainViewModelFactory(context))
}
