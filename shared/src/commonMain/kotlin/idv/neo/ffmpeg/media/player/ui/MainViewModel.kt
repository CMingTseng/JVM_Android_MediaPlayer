package idv.neo.ffmpeg.media.player.ui

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.coroutineScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.isActive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.nio.ShortBuffer
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.coroutines.cancellation.CancellationException
import androidx.lifecycle.viewModelScope
import idv.neo.ffmpeg.media.player.core.convertToImageBitmap
import kotlinx.coroutines.NonCancellable.isActive
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import java.nio.ByteBuffer
import javax.sound.sampled.LineUnavailableException

class MainViewModel : ViewModel() {
    private val _countdownValue = MutableStateFlow(10)
    val countdownValue: StateFlow<Int> = _countdownValue.asStateFlow()
    private val _videoFrameBitmap = MutableStateFlow<ImageBitmap?>(null)
    val videoFrameBitmap: StateFlow<ImageBitmap?> = _videoFrameBitmap.asStateFlow()
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()
    private var countdownJob: Job? = null

    //<editor-fold desc="Playback Control & Resources">
    private var grabber: FFmpegFrameGrabber? = null
    private var soundLine: SourceDataLine? = null
    private var actualGrabberPixelFormat: Int = -1

    private var playbackJob: Job? = null
    private val audioDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val imageProcessingDispatcher = Dispatchers.IO.limitedParallelism(1)
    //</editor-fold>

    //<editor-fold desc="Playback Timer Internals">
    private var timerSystemStartTimeNanos: Long = -1L
    private var timerFirstFrameAbsoluteTimestampMicros: Long = -1L
    private var timerHasStarted: Boolean = false
    private var timerSoundLineEverRan: Boolean = false
    private var timerAudioClockActiveForLastCheck: Boolean = false
    //</editor-fold>

    //<editor-fold desc="Synchronization Parameters">
    private val MAX_READ_AHEAD_MICROS = 700 * 1000L
    private val VIDEO_DELAY_CAP_MILLIS_UNRELIABLE_TIMER = 1000L
    private val VIDEO_MAX_SLEEP_RELIABLE_MS = 1000L // Specific cap for video delay when audio clock is reliable
    private val MAIN_LOOP_DELAY_CAP_MILLIS_UNRELIABLE_TIMER = 300L
    private val GENERAL_MAX_SLEEP_MILLIS = 2000L // General cap for main loop, and was used for video previously
    private val MIN_MEANINGFUL_VIDEO_DELAY_MS = 10L // Minimum calculated delay to actually execute for video
    //</editor-fold>

    private var logFrameCount = 0L

    init {
        startCountdown()
    }

    private fun startCountdown() {
        countdownJob?.cancel() // Cancel any existing countdown
        countdownJob = viewModelScope.launch {
            for (i in 10 downTo 0) { // Countdown from 10 to 0
                _countdownValue.value = i
                if (i == 0) {
                    // Countdown finished, perform any action here if needed
                    // e.g., navigate to another screen, load content, etc.
                    // For now, it will just stay at 0
                    break // Exit loop once 0 is reached
                }
                delay(1000L) // Wait for 1 second
            }
        }
    }

    //<editor-fold desc="Playback Timer Logic">
    private fun startTimer(firstFrameAbsoluteTsMicros: Long) {
        if (timerHasStarted) return
        timerFirstFrameAbsoluteTimestampMicros = firstFrameAbsoluteTsMicros
        timerSystemStartTimeNanos = System.nanoTime()
        timerHasStarted = true
        timerSoundLineEverRan = false
        timerAudioClockActiveForLastCheck = false
        println("SVM: PlaybackTimer Started. FirstAbsTS: $firstFrameAbsoluteTsMicros us")
    }

    private fun isAudioClockReliable(): Boolean {
        val isActive = soundLine?.isOpen == true && soundLine?.isRunning == true
        if (isActive && !timerAudioClockActiveForLastCheck) {
            println("SVM: PlaybackTimer - Audio clock is NOW considered RELIABLE.")
        } else if (!isActive && timerAudioClockActiveForLastCheck) {
            println("SVM: PlaybackTimer - Audio clock was reliable, but NOT ANYMORE.")
        }
        timerAudioClockActiveForLastCheck = isActive
        return isActive
    }

    private fun getCurrentRelativePlaybackTimeMicros(): Long {
        if (!timerHasStarted) return 0L

        return if (isAudioClockReliable()) {
            if (!timerSoundLineEverRan) {
                timerSoundLineEverRan = true
                println("SVM: PlaybackTimer - SoundLine is RUNNING, using its microsecondPosition.")
            }
            soundLine!!.microsecondPosition
        } else {
            val systemDurationMicros = (System.nanoTime() - timerSystemStartTimeNanos) / 1000L
            if (logFrameCount % 100 == 0L && soundLine != null) {
                println("SVM: PlaybackTimer - Audio clock UNRELIABLE. Using System.nanoTime(). Elapsed: ${systemDurationMicros}us. SoundLine: isOpen=${soundLine?.isOpen}, isRunning=${soundLine?.isRunning}")
            }
            return systemDurationMicros
        }
    }
    //</editor-fold>

    fun startVideoStreaming(videoUrl: String) {
        if (playbackJob?.isActive == true) {
            println("SVM: Playback already in progress. Stopping previous one.")
            playbackJob?.cancel("New stream request")
        }

        _videoFrameBitmap.value = null

        _errorMessage.value = null
        timerHasStarted = false
        logFrameCount = 0

        playbackJob = viewModelScope.launch(Dispatchers.IO + CoroutineName("MainPlaybackLoop")) {
            try {
                initializeResources(videoUrl)
                if (grabber == null || !isActive) {

                    println("SVM: Grabber initialization failed or job cancelled.")
                    return@launch
                }

                if (soundLine != null) {
                    println("SVM: --- Starting Audio Warm-up ---")
                    val success = performAudioWarmup()
                    println("SVM: --- Audio Warm-up Finished. Success: $success ---")
                    if (!success && isActive) {
                        println("SVM: Warning - Audio warm-up might not have made clock reliable immediately.")
                    }
                }

                if (!isActive) {

                    return@launch
                }


                println("SVM: Starting main frame processing loop.")

                while (isActive) {
                    logFrameCount++
                    val frame = grabber?.grab()
                    if (frame == null) {
                        println("SVM: End of stream or grabber error (null frame).")
                        break
                    }

                    if (!timerHasStarted) {
                        startTimer(frame.timestamp)
                    }

                    val currentFrameAbsoluteTs = frame.timestamp
                    val currentFrameRelativeTs = currentFrameAbsoluteTs - timerFirstFrameAbsoluteTimestampMicros
                    val currentRelativePlaybackTime = getCurrentRelativePlaybackTimeMicros()

                    if (logFrameCount <= 10 || logFrameCount % 50 == 0L) {
                        println("SVM: [Loop $logFrameCount] AbsTS:${currentFrameAbsoluteTs}, RelTS:${currentFrameRelativeTs}, PlaybackTime:${currentRelativePlaybackTime}, AudioReliable:${isAudioClockReliable()}, Img:${frame.image != null}, Aud:${frame.samples != null}")
                    }

                    // --- VIDEO PROCESSING ---
                    if (frame.image != null && frame.imageWidth > 0 && frame.imageHeight > 0) {
                        val imageFrameForProcessing = frame.clone()
                        val imageFrameRelativeTs = currentFrameRelativeTs

                        launch(imageProcessingDispatcher + CoroutineName("ImageProcessing-${imageFrameRelativeTs}")) {
                            if (!isActive) {
                                println("SVM: [ImageProc $imageFrameRelativeTs] Coroutine no longer active at start.")
                                return@launch
                            }

                            var videoDelayMillis = 0L
                            val playbackTimeAtRenderDecision = getCurrentRelativePlaybackTimeMicros()
                            val delayNeededMicros = imageFrameRelativeTs - playbackTimeAtRenderDecision

                            if (delayNeededMicros > 0) { // Frame is ahead of current playback time
                                videoDelayMillis = delayNeededMicros / 1000

                                // Only apply actual sleep if the calculated delay is meaningful
                                if (videoDelayMillis < MIN_MEANINGFUL_VIDEO_DELAY_MS) {
                                    videoDelayMillis = 0L // Ignore very small positive delays
                                } else {
                                    val audioClockGood = isAudioClockReliable()
                                    if (!audioClockGood && videoDelayMillis > VIDEO_DELAY_CAP_MILLIS_UNRELIABLE_TIMER) {
                                        // println("SVM: [ImageProc $imageFrameRelativeTs] Timer UNRELIABLE. Video sleep capped to $VIDEO_DELAY_CAP_MILLIS_UNRELIABLE_TIMER ms.")
                                        videoDelayMillis = VIDEO_DELAY_CAP_MILLIS_UNRELIABLE_TIMER
                                    } else if (audioClockGood && videoDelayMillis > VIDEO_MAX_SLEEP_RELIABLE_MS) {
                                        // println("SVM: [ImageProc $imageFrameRelativeTs] Timer RELIABLE. Video sleep $videoDelayMillis ms capped to $VIDEO_MAX_SLEEP_RELIABLE_MS ms.")
                                        videoDelayMillis = VIDEO_MAX_SLEEP_RELIABLE_MS
                                    }
                                }
                            } else { // Frame is on time or late
                                videoDelayMillis = 0L
                            }

                            if (videoDelayMillis > 0) {
                                // println("SVM: [ImageProc $imageFrameRelativeTs] Delaying video for $videoDelayMillis ms.")
                                try {
                                    delay(videoDelayMillis)
                                } catch (e: CancellationException) {
                                    println("SVM: [ImageProc $imageFrameRelativeTs] Delay cancelled.")
                                    throw e
                                }
                            }

                            if (!isActive) {
                                println("SVM: [ImageProc $imageFrameRelativeTs] Coroutine no longer active after delay.")
                                return@launch
                            }
                            if (frame.imageWidth <= 0 || frame.imageHeight <= 0 || frame.image == null || frame.image[0] == null) {

                            }else{
                                val width = frame.imageWidth
                                val height = frame.imageHeight
                                val imageStride = frame.imageStride
                                val imageDepth = frame.imageDepth
                                val imageChannels = frame.imageChannels
                                val imageBuffer = frame.image[0] as ByteBuffer
                                val bitmap =  convertToImageBitmap(width,height,imageStride,imageDepth,imageChannels,imageBuffer, actualGrabberPixelFormat)
                                if (bitmap != null) {
                                    if (isActive) {
                                        withContext(Dispatchers.Main) {
                                            _videoFrameBitmap.value = bitmap

                                        }
                                    }
                                } else {
                                    println("SVM: [ImageProc $imageFrameRelativeTs] Skia conversion failed. No bitmap to display.")
                                }
                            }
                        }
                    }
                    // --- AUDIO PROCESSING ---
                    else if (frame.samples != null && soundLine != null) {
                        val audioFrame = frame.clone()
                        launch(audioDispatcher) {
                            if (!isActive) return@launch
                            playAudioFrame(audioFrame)
                        }
                    }

                    // --- MAIN LOOP SYNCHRONIZATION ---
                    var mainLoopSleepMillis = 0L
                    val frameIsAheadByMicros = currentFrameRelativeTs - currentRelativePlaybackTime

                    if (frameIsAheadByMicros > MAX_READ_AHEAD_MICROS) {
                        mainLoopSleepMillis = (frameIsAheadByMicros - MAX_READ_AHEAD_MICROS) / 1000
                        val audioClockGood = isAudioClockReliable()

                        if (!audioClockGood && mainLoopSleepMillis > MAIN_LOOP_DELAY_CAP_MILLIS_UNRELIABLE_TIMER) {
                            mainLoopSleepMillis = MAIN_LOOP_DELAY_CAP_MILLIS_UNRELIABLE_TIMER
                        } else if (audioClockGood && mainLoopSleepMillis > GENERAL_MAX_SLEEP_MILLIS) {
                            mainLoopSleepMillis = GENERAL_MAX_SLEEP_MILLIS
                        }
                    }

                    if (mainLoopSleepMillis > 5) {
                        delay(mainLoopSleepMillis)
                    }
                    yield()
                } // end while(isActive)

            } catch (e: CancellationException) {
                println("SVM: Playback job cancelled: ${e.message}")
            } catch (e: Exception) {
                println("SVM: Exception in playback loop: ${e.message}")
                e.printStackTrace()
                _errorMessage.value = "Playback error: ${e.localizedMessage}"
            } finally {
                println("SVM: Playback loop finished. Cleaning up resources.")

                cleanupResources()
            }
        }
    }

    private suspend fun performAudioWarmup(): Boolean {
        val maxWarmupFrames = 30
        var success = false
        var lastWarmupPlaybackTime = -1L
        var stableTimeChecks = 0
        val requiredStableChecks = 3

        for (i in 0 until maxWarmupFrames) {
            if (!isActive) break
            val warmFrame = grabber?.grabFrame(true, true, false, false) ?: break
            if (!timerHasStarted) startTimer(warmFrame.timestamp)

            if (warmFrame.samples != null) playAudioFrame(warmFrame)

            delay(25)

            val currentWarmupTime = getCurrentRelativePlaybackTimeMicros()
            if (isAudioClockReliable()) {
                if (currentWarmupTime > 0 && currentWarmupTime > lastWarmupPlaybackTime) {
                    stableTimeChecks++
                } else if (currentWarmupTime == lastWarmupPlaybackTime && currentWarmupTime > 0) {
                    stableTimeChecks++
                } else {
                    stableTimeChecks = 0
                }
                if (stableTimeChecks >= requiredStableChecks) {
                    success = true
                    break
                }
            } else {
                stableTimeChecks = 0
            }
            lastWarmupPlaybackTime = currentWarmupTime
            if (i % 5 == 0) println("SVM: [Warmup Iter ${i + 1}] PlaybackTime:$currentWarmupTime, Reliable:${isAudioClockReliable()}, StableChecks:$stableTimeChecks")
        }
        return success
    }

    private fun initializeResources(videoUrl: String) {
        try {
            grabber = FFmpegFrameGrabber(videoUrl).apply {
                // setOption("stimeout", (5 * 1000 * 1000).toString())
            }
            println("SVM: Attempting to start grabber for: $videoUrl")
            grabber!!.start()
            actualGrabberPixelFormat = grabber!!.pixelFormat
            //FIXME
//            println("SVM: Grabber started. Format:${getPixelFormatName(actualGrabberPixelFormat)}, Size:${grabber!!.imageWidth}x${grabber!!.imageHeight}, FPS:${grabber!!.frameRate}")
            println("SVM: Audio: Ch:${grabber!!.audioChannels}, Rate:${grabber!!.sampleRate}, Codec:${grabber!!.audioCodecName ?: "N/A"}")

            if (grabber!!.audioChannels > 0) {
                val sampleRate = grabber!!.sampleRate.toFloat()
                val audioFormat = AudioFormat(sampleRate, 16, grabber!!.audioChannels, true, false) // PCM S16LE
                val info = DataLine.Info(SourceDataLine::class.java, audioFormat)

                if (AudioSystem.isLineSupported(info)) {
                    soundLine = (AudioSystem.getLine(info) as SourceDataLine).apply {
                        val bufferMillis = 750
                        val frameSize = audioFormat.frameSize
                        val bufferSize = (frameSize * sampleRate * (bufferMillis / 1000.0f)).toInt()
                        open(audioFormat, bufferSize)
                        start()
                    }
                    println("SVM: Audio line opened (S16LE). Buffer:${soundLine!!.bufferSize} bytes. Format: $audioFormat")
                } else {
                    println("SVM: S16LE audio format not supported. Trying S16BE.")
                    val audioFormatBE = AudioFormat(sampleRate, 16, grabber!!.audioChannels, true, true) // PCM S16BE
                    val infoBE = DataLine.Info(SourceDataLine::class.java, audioFormatBE)
                    if (AudioSystem.isLineSupported(infoBE)) {
                        soundLine = (AudioSystem.getLine(infoBE) as SourceDataLine).apply {
                            val bufferMillis = 750
                            val frameSize = audioFormatBE.frameSize
                            val bufferSize = (frameSize * sampleRate * (bufferMillis / 1000.0f)).toInt()
                            open(audioFormatBE, bufferSize)
                            start()
                        }
                        println("SVM: Audio line opened (S16BE). Buffer:${soundLine!!.bufferSize} bytes. Format: $audioFormatBE")
                    } else {
                        println("SVM: S16BE audio format also not supported. No audio output.")
                        soundLine = null
                    }
                }
            } else {
                println("SVM: No audio channels in the stream.")
                soundLine = null
            }
        } catch (e: FFmpegFrameGrabber.Exception) {
            val errorMsg = "SVM: FFmpeg Grabber error during init: ${e.localizedMessage}"
            println(errorMsg)
            _errorMessage.value = errorMsg
            grabber = null
            throw e
        } catch (e: LineUnavailableException) {
            val errorMsg = "SVM: Audio line unavailable: ${e.localizedMessage}"
            println(errorMsg)
            _errorMessage.value = errorMsg
            soundLine = null
        } catch (e: Exception) {
            val errorMsg = "SVM: Generic error during init: ${e.localizedMessage}"
            println(errorMsg)
            _errorMessage.value = errorMsg
            grabber = null
            soundLine = null
            throw e
        }
    }

    private fun playAudioFrame(frame: Frame) {
        val audioBuffer = frame.samples?.firstOrNull() as? ShortBuffer ?: return
        if (audioBuffer.remaining() == 0 || soundLine == null || !soundLine!!.isOpen) return

        val bytesToPlay = ByteArray(audioBuffer.remaining() * 2)
        for (i in 0 until audioBuffer.remaining()) {
            val shortVal = audioBuffer.get(i)
            bytesToPlay[i * 2] = (shortVal.toInt() and 0xFF).toByte()
            bytesToPlay[i * 2 + 1] = (shortVal.toInt() shr 8 and 0xFF).toByte()
        }

        try {
            soundLine?.write(bytesToPlay, 0, bytesToPlay.size)
        } catch (e: Exception) {
            println("SVM: [AudioPlayer] Error writing to soundLine: ${e.message}")
        }
    }

    private fun cleanupResources() {
        println("SVM: cleanupResources called.")
        try {
            grabber?.stop()
            grabber?.release()
        } catch (e: Exception) {
            println("SVM: Error stopping/releasing grabber: ${e.message}")
        }
        grabber = null

        soundLine?.let {
            try {
                if (it.isOpen) {
                    it.drain()
                    it.stop()
                    it.close()
                }
            } catch (e: Exception) {
                println("SVM: Error cleaning up soundLine: ${e.message}")
            }
        }
        soundLine = null
        actualGrabberPixelFormat = -1

        timerHasStarted = false
        timerFirstFrameAbsoluteTimestampMicros = -1L
        timerSystemStartTimeNanos = -1L
        timerSoundLineEverRan = false
        timerAudioClockActiveForLastCheck = false
        println("SVM: Resources cleaned up.")
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
        playbackJob?.cancel("ViewModel cleared")
    }
}