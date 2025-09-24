package org.bytedeco.javacv

import kotlinx.coroutines.*
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger
import javax.sound.sampled.*
import kotlin.math.min

// Type aliases and PlayerEvent remain the same
typealias VideoFrameOutputCallback = (videoFrame: Frame, relativeTimestampMicros: Long) -> Unit
typealias AudioDataOutputCallback = (samples: ShortBuffer, lineToWriteTo: SourceDataLine, originalFrame: Frame) -> Unit
typealias PlayerEventCallback = (event: PlayerEvent) -> Unit

sealed interface PlayerEvent {
    data class VideoDimensionsDetected(val width: Int, val height: Int, val pixelFormat: Int, val frameRate: Double) : PlayerEvent
    data object PlaybackStarted : PlayerEvent
    data object EndOfMedia : PlayerEvent
    data class Error(val errorMessage: String, val exception: Exception?) : PlayerEvent
}

class JavaFxSwingComposeFFmpegPlayer @JvmOverloads constructor(
    private val videoFrameOutputCallback: VideoFrameOutputCallback,
    private val playerEventCallback: PlayerEventCallback,
    private val audioDataOutputCallback: AudioDataOutputCallback? = null
) : Closeable {

    private val playerScope = CoroutineScope(Dispatchers.Default + SupervisorJob() + CoroutineName("FFmpegPlayerScope"))
    private var playerJob: Job? = null

    @Volatile
    private var stopRequested = false

    private var videoProcessingContext: ExecutorCoroutineDispatcher? = null
    private var audioProcessingContext: ExecutorCoroutineDispatcher? = null

    private var grabber: FFmpegFrameGrabber? = null
    private var localSoundLine: SourceDataLine? = null

    @Volatile private var firstValidFrameTimestampMicros: Long = -1L
    @Volatile private var systemTimeAnchorNanos: Long = -1L
    @Volatile private var isMediaClockInitialized: Boolean = false

    // Configuration from your _old.kt
    private val maxReadAheadBufferMicros = 700 * 1000L
    private val videoMaxSleepMsIfEarly = 100L // Renamed from videoMaxSleepMs
    private val videoCatchUpDropThresholdMicros = 200 * 1000L // From your _old.kt
    private val minMeaningfulSleepMs = 5L      // Renamed from minMeaningfulVideoDelayMs

    // Polling intervals from your _old.kt (experimentalPollingIntervalsMs)
    private val pollingIntervalsMs = listOf(600L, 800L, 1000L, 1200L, 1500L, 2000L, 2500L, 3000L, 3000L, 3000L) // Total approx 18.6s

    companion object {
        private val LOG = Logger.getLogger(JavaFxSwingComposeFFmpegPlayer::class.java.name)
        private const val DETAILED_AUDIO_LOGGING = true
        @JvmStatic @Volatile var S_loopIteration = 0L
        private const val GENERAL_MAX_SLEEP_MILLIS = 2000L
    }

    // MediaClock methods (remain the same)
    private fun initializeMediaClock(firstFrameTimestamp: Long) {
        if (!isMediaClockInitialized || (this.firstValidFrameTimestampMicros == 0L && firstFrameTimestamp > 0L) ) {
            this.firstValidFrameTimestampMicros = firstFrameTimestamp
            this.systemTimeAnchorNanos = System.nanoTime()
            this.isMediaClockInitialized = true
            LOG.info("MediaClock: Initialized/Updated. FirstFrameTS: $firstValidFrameTimestampMicros us, SystemAnchor: $systemTimeAnchorNanos ns")
        }
    }

    private fun getMediaClockPositionMicros(): Long {
        if (!isMediaClockInitialized) return 0L
        localSoundLine?.let { line ->
            if (line.isOpen && line.isRunning) {
                val audioTs = line.microsecondPosition
                if (audioTs >= 0L) {
                    if (S_loopIteration > 0L && S_loopIteration % 100L == 1L && DETAILED_AUDIO_LOGGING) LOG.info("MediaClock: Using AudioTrack position: $audioTs µs")
                    return audioTs
                } else if (S_loopIteration > 0L && S_loopIteration % 100L == 1L && DETAILED_AUDIO_LOGGING) LOG.warning("MediaClock: AudioTrack position is negative or unreliable ($audioTs µs). Falling back.")
            }
        }
        val elapsedNanos = System.nanoTime() - systemTimeAnchorNanos
        val estimatedMicros = elapsedNanos / 1000L
        if (S_loopIteration > 0L && S_loopIteration % 100L == 1L && DETAILED_AUDIO_LOGGING) LOG.info("MediaClock: Using System time fallback. Estimated: $estimatedMicros µs (Anchor: $firstValidFrameTimestampMicros µs)")
        return estimatedMicros
    }

    fun start(mediaPath: String) {
        if (playerJob?.isActive == true) {
            LOG.warning("Player: start() called, but already running for media: ${grabber?.format}")
            return
        }
        stopRequested = false
        isMediaClockInitialized = false
        firstValidFrameTimestampMicros = -1L

        playerJob = playerScope.launch {
            S_loopIteration = 0L
            LOG.info("Player-Coroutine (${currentCoroutineContext()[CoroutineName]?.name}): Initializing for: $mediaPath")
            cleanupPlayerResources(releaseGrabber = true, closeSoundLine = true)

            var currentGrabberInstance: FFmpegFrameGrabber? = null
            try {
                LOG.info("Player: Creating FFmpegFrameGrabber for '$mediaPath'")
                currentGrabberInstance = FFmpegFrameGrabber(mediaPath)
                LOG.info("Player: Calling FFmpegFrameGrabber.start() for '$mediaPath'...")
                val timeBeforeGrabberStart = System.currentTimeMillis()
                currentGrabberInstance.start()
                val timeAfterGrabberStart = System.currentTimeMillis()
                LOG.info("Player: FFmpegFrameGrabber.start() returned for '$mediaPath' after ${timeAfterGrabberStart - timeBeforeGrabberStart} ms.")

                grabber = currentGrabberInstance
                val g = grabber ?: throw FrameGrabber.Exception("Grabber became null after start assignment")

                LOG.info("Player [After start() call]: Grabber's initial state (may be incomplete) - Format: '${g.format}', VidW: ${g.imageWidth}, AudCh: ${g.audioChannels}")

                var totalPollingTimeMs = 0L
                var pollingAttempts = 0
                var metadataObtainedDuringPolling = false
                var audioSetupAttemptedDuringPolling = false

                LOG.info("Player [Polling]: Starting metadata polling loop. Max attempts: ${pollingIntervalsMs.size}")

                // --- Polling Loop (Strictly following _old.kt logic) ---
                for (intervalMs in pollingIntervalsMs) {
                    if (!isActive || stopRequested) { LOG.info("Player [Polling]: Coroutine cancelled or stop requested."); break }

                    delay(intervalMs)
                    pollingAttempts++
                    totalPollingTimeMs += intervalMs

                    // **CRUCIAL: Re-fetch all properties directly from 'g' (the grabber instance) in each iteration**
                    val currentPolledWidth = g.imageWidth
                    val currentPolledHeight = g.imageHeight
                    val currentPolledFormat = g.format
                    val currentPolledFps = g.frameRate
                    val currentPolledAudioChannels = g.audioChannels
                    val currentPolledSampleRate = g.sampleRate
                    val currentPolledPixelFormat = g.pixelFormat

                    if (pollingAttempts % 2 == 0 || intervalMs == pollingIntervalsMs.last() || pollingAttempts == 1) {
                        LOG.info("Player [Polling]: Attempt #$pollingAttempts (total ${totalPollingTimeMs}ms, interval ${intervalMs}ms) - Format: '$currentPolledFormat', VidW: $currentPolledWidth, VidH: $currentPolledHeight, FPS: $currentPolledFps, AudCh: $currentPolledAudioChannels, AudRate: $currentPolledSampleRate, PixFmt: $currentPolledPixelFormat, HasVideo:${g.hasVideo()}, HasAudio:${g.hasAudio()}")
                    }

                    // Audio Setup during Polling (from _old.kt)
                    if (g.hasAudio() && currentPolledAudioChannels > 0 && currentPolledSampleRate > 0 && !audioSetupAttemptedDuringPolling) {
                        LOG.info("Player [Polling]: Valid audio params detected (Ch: $currentPolledAudioChannels, Rate: $currentPolledSampleRate). Attempting audio setup...")
                        try {
                            setupAudio(currentPolledSampleRate, currentPolledAudioChannels)
                            audioSetupAttemptedDuringPolling = true
                            if (localSoundLine?.isActive == true) LOG.info("Player [Polling]: Audio line is ACTIVE after setup.")
                            else LOG.warning("Player [Polling]: Audio line setup but NOT ACTIVE.")
                        } catch (e: Exception) {
                            LOG.log(Level.WARNING, "Player [Polling]: Failed to setup audio.", e)
                            playerEventCallback(PlayerEvent.Error("Polling: Audio setup failed: ${e.message}", e))
                        }
                    }

                    // Success condition from _old.kt (directly using properties from 'g')
                    val hasPolledValidVideo = (g.hasVideo() && g.imageWidth > 0 && g.imageHeight > 0 && (g.frameRate > 0.001 || !g.hasVideo())) || (!g.hasVideo() && g.imageWidth == 0 && g.imageHeight == 0)
                    val hasPolledValidAudioParams = (g.hasAudio() && g.audioChannels > 0 && g.sampleRate > 0) || !g.hasAudio()
                    val isAudioConsideredReady = audioSetupAttemptedDuringPolling || hasPolledValidAudioParams
                    val hasPolledValidFormat = !g.format.isNullOrEmpty() || (!g.hasVideo() && !g.hasAudio())

                    if (hasPolledValidFormat && hasPolledValidVideo && isAudioConsideredReady) {
                        LOG.info("Player [Polling]: SUCCESS! Metadata validated after $pollingAttempts attempts (${totalPollingTimeMs}ms).")
                        LOG.info("Player [Polling Success State]: Format: '${g.format}', VidW: ${g.imageWidth}, VidH: ${g.imageHeight}, FPS: ${g.frameRate}, AudCh: ${g.audioChannels}, AudRate: ${g.sampleRate}, PixFmt: ${g.pixelFormat}")
                        metadataObtainedDuringPolling = true
                        break
                    }
                }
                // --- End Polling Loop ---
                LOG.info("Player [Polling]: Finished. Total attempts: $pollingAttempts, Total time: ${totalPollingTimeMs}ms. Metadata Obtained: $metadataObtainedDuringPolling")
                LOG.info("Player [Polling FINAL GRABBER STATE (from 'g')]: Format: '${g.format}', VidW: ${g.imageWidth}, VidH: ${g.imageHeight}, FPS: ${g.frameRate}, AudCh: ${g.audioChannels}, AudRate: ${g.sampleRate}, PixFmt: ${g.pixelFormat}, HasVideo:${g.hasVideo()}, HasAudio:${g.hasAudio()}")

                if (!metadataObtainedDuringPolling && isActive && !stopRequested) {
                    LOG.warning("Player [Polling]: FAILED to obtain all critical metadata after ${totalPollingTimeMs}ms.")
                    // Failure condition from _old.kt: if polling fails, and the grabber still has no video or audio, then it's an error.
                    if (!(g.imageWidth > 0 && g.hasVideo()) && !(g.audioChannels > 0 && g.hasAudio())) {
                        val errorMsg = "Polling failed AND grabber has NO valid video OR audio dimensions/channels after polling. Final state from grabber: Format: '${g.format}', VidW: ${g.imageWidth}, AudCh: ${g.audioChannels}"
                        LOG.severe("Player [Critical]: $errorMsg")
                        throw FrameGrabber.Exception(errorMsg)
                    }
                    LOG.info("Player [Proceeding]: Proceeding with potentially incomplete metadata from grabber as per _old.kt logic.")
                } else if (!isActive || stopRequested) {
                    LOG.warning("Player: Coroutine cancelled or stop requested after polling for '$mediaPath'.")
                    g.releaseQuietly(); grabber = null; return@launch
                }

                // Use the state of 'g' (the grabber) as the source of truth after polling
                val finalWidth = g.imageWidth
                val finalHeight = g.imageHeight
                val finalPixelFormat = g.pixelFormat
                val finalFrameRate = g.frameRate
                val finalAudioChannels = g.audioChannels
                val finalSampleRate = g.sampleRate

                LOG.info("Player [Finalizing Dimensions from 'g']: VidW: $finalWidth, VidH: $finalHeight, AudCh: $finalAudioChannels, AudRate: $finalSampleRate, FPS: $finalFrameRate, PixFmt: $finalPixelFormat")

                if (g.hasVideo() && finalWidth <= 0) {
                    val errorMsg = "Invalid video width ($finalWidth) for a stream that reports having video (final check)."
                    LOG.severe("Player: Error - $errorMsg")
                    playerEventCallback(PlayerEvent.Error(errorMsg, null))
                    g.releaseQuietly(); grabber = null; return@launch
                }
                if (g.hasVideo()){
                    playerEventCallback(PlayerEvent.VideoDimensionsDetected(finalWidth, finalHeight, finalPixelFormat, finalFrameRate))
                }

                if (g.hasAudio() && localSoundLine == null && finalAudioChannels > 0 && finalSampleRate > 0) {
                    LOG.info("Player: Audio not yet set up, attempting now with final params (Ch:$finalAudioChannels, Rate:$finalSampleRate)...")
                    try {
                        setupAudio(finalSampleRate, finalAudioChannels)
                    } catch (e: Exception) {
                        LOG.log(Level.WARNING, "Player: Failed to setup audio (post-polling).", e)
                        playerEventCallback(PlayerEvent.Error("Audio setup failed (post-polling): ${e.message}", e))
                    }
                }

                videoProcessingContext = Executors.newSingleThreadExecutor { r -> Thread(r, "Player-VideoProcessor").apply { isDaemon = true } }.asCoroutineDispatcher()
                audioProcessingContext = Executors.newSingleThreadExecutor { r -> Thread(r, "Player-AudioProcessor").apply { isDaemon = true } }.asCoroutineDispatcher()

                playerEventCallback(PlayerEvent.PlaybackStarted)
                LOG.info("Player: Starting main frame processing loop with MediaClock.")

                var reportedFrameRateForFpsCalc = if (finalFrameRate > 0.001) finalFrameRate else 25.0 // Default for FPS calc if needed

                // Main Playback Loop (adapting _old.kt's frame processing into MediaClock sync)
                while (isActive && !stopRequested) {
                    S_loopIteration++
                    val frame = try { g.grab() } catch (e: FrameGrabber.Exception) {
                        LOG.log(Level.WARNING, "Player: Error grabbing frame.", e)
                        playerEventCallback(PlayerEvent.Error("Error grabbing frame: ${e.message}", e))
                        break
                    }
                    if (frame == null) {
                        LOG.info("Player: End of stream (grabber returned NULL).")
                        playerEventCallback(PlayerEvent.EndOfMedia); break
                    }

                    if (!isMediaClockInitialized && frame.timestamp >= 0L) {
                        initializeMediaClock(frame.timestamp)
                    } else if (isMediaClockInitialized && firstValidFrameTimestampMicros == 0L && frame.timestamp > 0L) {
                        initializeMediaClock(frame.timestamp)
                    }
                    if (!isMediaClockInitialized) {
                        LOG.warning("Player [MainLoop]: MediaClock not initialized. Skipping frame TS: ${frame.timestamp}")
                        frame.close(); delay(10); continue
                    }

                    val currentFrameAbsoluteTs = frame.timestamp
                    val currentFrameRelativeTs = currentFrameAbsoluteTs - firstValidFrameTimestampMicros
                    val currentMediaClockTimeMicros = getMediaClockPositionMicros()

                    val hasImage = frame.image != null && frame.imageHeight > 0 && frame.imageWidth > 0
                    val hasAudio = frame.samples != null && frame.samples[0] != null && localSoundLine != null


                    // From _old.kt: Dynamic FPS calculation if needed (and if no VideoDimensionsDetected was sent from polling)
                    if (hasImage && finalFrameRate <= 0.001 && S_loopIteration > 10L && S_loopIteration % 10L == 0L && currentFrameRelativeTs > 0L) {
                        val estimatedFps = (S_loopIteration.toDouble() * 1_000_000.0) / currentFrameRelativeTs.toDouble()
                        if (estimatedFps > 0) {
                            LOG.info("Player [MainLoop]: Estimating FPS from frame timestamps: $estimatedFps")
                            reportedFrameRateForFpsCalc = estimatedFps
                            // Optionally send a new VideoDimensionsDetected if FPS changed significantly
                            // playerEventCallback(PlayerEvent.VideoDimensionsDetected(finalWidth, finalHeight, finalPixelFormat, estimatedFps))
                        }
                    }


                    if (S_loopIteration % 100L == 1L) {
                        LOG.info("Player [MainLoop $S_loopIteration]: FrameAbsTS=${currentFrameAbsoluteTs}µs, FrameRelTS=${currentFrameRelativeTs}µs, MediaClock=${currentMediaClockTimeMicros}µs, Img=$hasImage, Aud=$hasAudio, SoundLineRunning=${localSoundLine?.isRunning}")
                    }

                    if (hasAudio) {
                        val audioFrameToPlay = frame.clone()
                        launch(audioProcessingContext!!) { // Use a separate context for audio
                            try { if (!stopRequested) playAudioSample(audioFrameToPlay, localSoundLine!!) }
                            catch (e: Exception) { LOG.log(Level.WARNING, "Player: Error playing audio sample.", e) }
                            finally { audioFrameToPlay.close() }
                        }
                    }

                    if (hasImage) {
                        val videoFrameToRender = frame.clone()
                        launch(videoProcessingContext!!) { // Use a separate context for video
                            try {
                                if (!stopRequested) {
                                    val currentVideoMediaClockTimeMicros = getMediaClockPositionMicros()
                                    val videoTimestampToRender = currentFrameRelativeTs
                                    var delayNeededMicros = videoTimestampToRender - currentVideoMediaClockTimeMicros

                                    if (delayNeededMicros.compareTo(minMeaningfulSleepMs * 1000L) > 0) {
                                        val sleepMs = min(delayNeededMicros / 1000L, videoMaxSleepMsIfEarly)
                                        if (S_loopIteration % 50L == 1L && DETAILED_AUDIO_LOGGING) LOG.info("Player [VideoSync]: Video frame (RelTS $videoTimestampToRender µs) is early by ${delayNeededMicros / 1000L} ms. Sleeping for $sleepMs ms. MediaClock: $currentVideoMediaClockTimeMicros µs")
                                        if (sleepMs > 0L) delay(sleepMs)
                                    } else if (delayNeededMicros.compareTo(-videoCatchUpDropThresholdMicros) < 0 && reportedFrameRateForFpsCalc > 0.0) {
                                        if (S_loopIteration % 50L == 1L && DETAILED_AUDIO_LOGGING) LOG.warning("Player [VideoSync]: Video frame (RelTS $videoTimestampToRender µs) is LATE by ${-delayNeededMicros / 1000L} ms. Rendering. MediaClock: $currentVideoMediaClockTimeMicros µs")
                                        // Not dropping frames yet, just rendering immediately
                                    }
                                    videoFrameOutputCallback(videoFrameToRender, currentFrameRelativeTs)
                                }
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { LOG.log(Level.WARNING, "Player: Error processing/rendering video frame.", e) }
                            finally { videoFrameToRender.close() }
                        }
                    }
                    if (!hasImage && !hasAudio) { if (S_loopIteration % 100L == 1L) LOG.info("Player [MainLoop]: Frame has no video or audio content. TS: ${frame.timestamp}") }
                    frame.close()

                    val mediaClockNowForBackpressure = getMediaClockPositionMicros()
                    val readAheadMicros = currentFrameRelativeTs - mediaClockNowForBackpressure
                    if (readAheadMicros.compareTo(maxReadAheadBufferMicros) > 0) {
                        val sleepDurationMicros = readAheadMicros - maxReadAheadBufferMicros
                        val sleepMs = sleepDurationMicros / 1000L
                        if (sleepMs >= minMeaningfulSleepMs) {
                            if (S_loopIteration % 100L == 1L && DETAILED_AUDIO_LOGGING) LOG.info("Player [MainLoop]: Backpressure. Read ahead ${readAheadMicros / 1000L} ms > max ${maxReadAheadBufferMicros / 1000L} ms. Sleeping for $sleepMs ms.")
                            delay(sleepMs)
                        }
                    } else if (!hasImage && !hasAudio) { delay(1L) }
                }
            } catch (e: FrameGrabber.Exception) {
                LOG.log(Level.SEVERE, "Player: FFmpegFrameGrabber Exception", e)
                playerEventCallback(PlayerEvent.Error("FFmpeg Grabber error: ${e.message}", e))
            } catch (e: LineUnavailableException) {
                LOG.log(Level.SEVERE, "Player: Audio Line Unavailable", e)
                playerEventCallback(PlayerEvent.Error("Audio Line unavailable: ${e.message}", e))
            } catch (e: CancellationException) {
                LOG.info("Player: Coroutine cancelled.")
                // If cancellation is from stop(), it's expected. Otherwise, rethrow if needed.
                if (e.message != "Player stop requested by API") throw e
            } catch (e: Exception) {
                LOG.log(Level.SEVERE, "Player: Unexpected critical error", e)
                playerEventCallback(PlayerEvent.Error("Unexpected error: ${e.message}", e))
            } finally {
                LOG.info("Player-Coroutine: Finishing. stopRequested=$stopRequested, isActive=$isActive")
                if (isActive && !stopRequested && grabber != null) {
                    playerEventCallback(PlayerEvent.EndOfMedia)
                }
                cleanupPlayerResources(releaseGrabber = true, closeSoundLine = true)
                LOG.info("Player-Coroutine: Fully terminated.")
            }
        }
    }

    // stop() method - ensure it's present and cancels the job
    fun stop() {
        LOG.info("Player: stop() method called.")
        stopRequested = true
        playerJob?.cancel(CancellationException("Player stop requested by API")) // Explicitly cancel the job
    }

    // close() method - ensure it waits for the job to complete
    override fun close() {
        LOG.info("Player: close() called. Setting stopRequested and cancelling/joining job.")
        stopRequested = true
        val jobToWait = playerJob // Capture current job

        if (jobToWait != null && jobToWait.isActive) {
            runBlocking { // Use runBlocking to wait from a non-suspending context.
                try {
                    LOG.info("Player: Attempting to cancel and join playerJob in close().")
                    withTimeout(GENERAL_MAX_SLEEP_MILLIS + 2000L) {
                        jobToWait.cancelAndJoin()
                    }
                    LOG.info("Player: Player job cancelled and joined successfully in close().")
                } catch (e: TimeoutCancellationException) {
                    LOG.warning("Player: Timeout waiting for player job in close(). Forcing resource cleanup.")
                } catch (e: CancellationException) {
                    LOG.info("Player: Player job was already cancelled or completed when close() was called.")
                } catch (e: Exception) {
                    LOG.log(Level.WARNING, "Player: Exception during player job cancel/join in close().", e)
                }
            }
        } else {
            LOG.info("Player: Player job in close() was null or not active.")
        }
        cleanupPlayerResources(releaseGrabber = true, closeSoundLine = true, forceShutdownExecutors = true)
        LOG.info("Player: close() finished.")
    }

    // setupAudio, playAudioSample, cleanupPlayerResources, shutdownExecutor methods remain largely the same as your last good version.
    // Ensure playAudioSample correctly handles endianness based on line.format.isBigEndian.

    @Throws(LineUnavailableException::class, SecurityException::class)
    private fun setupAudio(sampleRate: Int, channels: Int) {
        if (localSoundLine?.isOpen == true) {
            LOG.info("Player: Audio line already open. Closing to reconfigure.")
            localSoundLine?.drain(); localSoundLine?.stop(); localSoundLine?.close()
        }
        val audioFormatLE = AudioFormat(sampleRate.toFloat(), 16, channels, true, false) // S16LE
        val audioFormatBE = AudioFormat(sampleRate.toFloat(), 16, channels, true, true)  // S16BE
        var lineToUse: SourceDataLine? = null
        var chosenFormat: AudioFormat? = null

        if (AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, audioFormatLE))) {
            lineToUse = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, audioFormatLE)) as SourceDataLine
            chosenFormat = audioFormatLE
            LOG.info("Player: S16LE audio format selected: $chosenFormat")
        } else if (AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, audioFormatBE))) {
            lineToUse = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, audioFormatBE)) as SourceDataLine
            chosenFormat = audioFormatBE
            LOG.info("Player: S16BE audio format selected: $chosenFormat")
        } else {
            throw LineUnavailableException("Neither S16LE nor S16BE audio format supported for $sampleRate Hz, $channels channels.")
        }

        localSoundLine = lineToUse.apply {
            // Use the chosenFormat for buffer size calculation before open, as line.format is only valid after open.
            var bytesPerFrame = chosenFormat!!.frameSize // chosenFormat is guaranteed non-null here
            if (bytesPerFrame == AudioSystem.NOT_SPECIFIED) bytesPerFrame = (chosenFormat.sampleSizeInBits / 8) * chosenFormat.channels
            val bufferDurationMillis = 500
            val desiredBufferSize = Math.max(8192, (bytesPerFrame * chosenFormat.frameRate * (bufferDurationMillis / 1000.0f)).toInt())

            LOG.info("Player: Opening audio line with format: $chosenFormat, desired buffer size: $desiredBufferSize bytes.")
            open(chosenFormat, desiredBufferSize) // Open with the specifically chosen format
            start()
            LOG.info("Player: Audio line opened and started. Actual Line Format: ${this.format}, Buffer size: ${this.bufferSize}. isOpen: $isOpen, isRunning: $isRunning, isActive: ${this.isActive()}")
        }
    }

    private fun playAudioSample(audioFrame: Frame, line: SourceDataLine) {
        audioDataOutputCallback?.let { callback ->
            val samples = audioFrame.samples?.get(0) as? ShortBuffer
            if (samples != null) callback(samples, line, audioFrame)
            else if (DETAILED_AUDIO_LOGGING && S_loopIteration % 100L == 1L) LOG.warning("Player [playAudioSample]: Audio frame has no samples for callback.")
            return
        }
        val samplesBuffer = audioFrame.samples?.get(0) as? ShortBuffer
        if (samplesBuffer == null || !line.isOpen) {
            if (DETAILED_AUDIO_LOGGING && S_loopIteration % 100L == 1L) LOG.warning("[DefaultAudioPlay] Samples buffer null or line not open. LineOpen: ${line.isOpen}")
            return
        }
        val numSamples = samplesBuffer.remaining()
        if (numSamples == 0) return

        val byteBuffer = ByteBuffer.allocate(numSamples * 2)
        // Set byte order based on the AudioFormat of the SourceDataLine
        byteBuffer.order(if (line.format.isBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)

        for (i in 0 until numSamples) byteBuffer.putShort(samplesBuffer.get(samplesBuffer.position() + i))
        val audioData = byteBuffer.array()
        val bytesWritten = line.write(audioData, 0, audioData.size)

        if (bytesWritten < audioData.size && DETAILED_AUDIO_LOGGING) LOG.warning("Player [DefaultAudioPlay]: Partial write. Wrote $bytesWritten of ${audioData.size}.")
        if (S_loopIteration % 200L == 1L && DETAILED_AUDIO_LOGGING && bytesWritten > 0) LOG.info("Player [DefaultAudioPlay]: Wrote $bytesWritten bytes. Line available: ${line.available()}")
    }

    private fun cleanupPlayerResources(releaseGrabber: Boolean, closeSoundLine: Boolean, forceShutdownExecutors: Boolean = false) {
        LOG.info("Player: Cleanup. ReleaseGrabber=$releaseGrabber, CloseSoundLine=$closeSoundLine, ForceShutdownExec=$forceShutdownExecutors")
        if (releaseGrabber) {
            grabber?.let { g -> try { g.stop(); g.release(); LOG.info("Player: Grabber released.") } catch (e: Exception) { LOG.log(Level.WARNING, "Grabber release error.", e) } }
            grabber = null
        }
        if (closeSoundLine) {
            localSoundLine?.let { line -> if (line.isOpen) try { line.drain(); line.stop(); line.close(); LOG.info("Player: SoundLine closed.") } catch (e: Exception) { LOG.log(Level.WARNING, "SoundLine close error.", e) } }
            localSoundLine = null
        }
        (audioProcessingContext as? CloseableCoroutineDispatcher)?.close().also { audioProcessingContext = null }
        (videoProcessingContext as? CloseableCoroutineDispatcher)?.close().also { videoProcessingContext = null }
        isMediaClockInitialized = false
        LOG.info("Player: Resource cleanup finished.")
    }
}

// Helper extensions (ensure they are defined or remove if not used consistently)
private fun FFmpegFrameGrabber.hasVideo(): Boolean = this.imageWidth > 0 && this.imageHeight > 0 && this.videoCodec != 0
private fun FFmpegFrameGrabber.hasAudio(): Boolean = this.audioChannels > 0 && this.sampleRate > 0 && this.audioCodec != 0
private fun FFmpegFrameGrabber.releaseQuietly() { try { this.release() } catch (e: Exception) { /* ignore */ } }
// Make sure this is correct for your SourceDataLine usage
private val SourceDataLine.isActive: Boolean get() = this.isOpen && this.isRunning && this.isActive() // Check Java's isActive()
