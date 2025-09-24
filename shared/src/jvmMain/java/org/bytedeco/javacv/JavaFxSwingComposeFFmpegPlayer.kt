package org.bytedeco.javacv

import kotlinx.coroutines.*
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger
import javax.sound.sampled.*
import kotlin.math.abs
import kotlin.math.min
import kotlin.text.format

// Type aliases for cleaner callback definitions in Kotlin
typealias VideoFrameOutputCallback = (videoFrame: Frame, relativeTimestampMicros: Long) -> Unit
typealias AudioDataOutputCallback = (samples: ShortBuffer, lineToWriteTo: SourceDataLine, originalFrame: Frame) -> Unit
typealias PlayerEventCallback = (event: PlayerEvent) -> Unit

// Sealed interface for player events to provide more type-safe event handling
sealed interface PlayerEvent {
    data class VideoDimensionsDetected(val width: Int, val height: Int, val pixelFormat: Int, val frameRate: Double) : PlayerEvent
    data object PlaybackStarted : PlayerEvent
    data object EndOfMedia : PlayerEvent
    data class Error(val errorMessage: String, val exception: Exception) : PlayerEvent
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
    private var playbackTimer: PlaybackTimer? = null

    // Configuration properties
    private val maxReadAheadBufferMicros = 700 * 1000L
    private val videoTargetDelayToleranceMicros = 30 * 1000L
    private val videoMaxSleepMs = 100L
    private val mainLoopDelayCapMillisUnreliableTimer = 100L
    private val generalMaxSleepMillis = 500L
    private val minMeaningfulVideoDelayMs = 5L
    private val experimentalPollingIntervalsMs = listOf(600L, 800L, 1000L, 1200L, 1500L, 2000L, 2500L, 3000L, 3000L, 3000L) // Total approx 18.6s

    companion object {
        private val LOG = Logger.getLogger(JavaFxSwingComposeFFmpegPlayer::class.java.name)
        private const val DETAILED_AUDIO_LOGGING = true

        @JvmStatic
        @Volatile
        var S_loopIteration = 0L
    }

    fun start(mediaPath: String) {
        if (playerJob?.isActive == true) {
            LOG.warning("Player: start() called, but already running for media: ${grabber?.format}")
            return
        }
        stopRequested = false

        playerJob = playerScope.launch {
            S_loopIteration = 0L
            LOG.info("Player-Coroutine (${Thread.currentThread().name}): Initializing for: $mediaPath")

            cleanupPlayerResources(releaseGrabber = true, closeSoundLine = true)

            var currentGrabberInstance: FFmpegFrameGrabber? = null
            var proceedAfterPolling = false


            try {
                LOG.info("Player: Creating FFmpegFrameGrabber for '$mediaPath'")
                currentGrabberInstance = FFmpegFrameGrabber(mediaPath)
                LOG.info("Player: Calling FFmpegFrameGrabber.start() for '$mediaPath'...")
                val timeBeforeGrabberStart = System.currentTimeMillis()
                currentGrabberInstance.start()
                val timeAfterGrabberStart = System.currentTimeMillis()
                LOG.info("Player: FFmpegFrameGrabber.start() returned for '$mediaPath' after ${timeAfterGrabberStart - timeBeforeGrabberStart} ms.")

                grabber = currentGrabberInstance
                val g = grabber!!

                var totalPollingTimeMs = 0L
                var pollingAttempts = 0
                var metadataObtainedDuringPolling = false
                var polledWidth = 0; var polledHeight = 0; var polledFormat: String? = null
                var polledFps = 0.0; var polledAudioChannels = 0; var polledSampleRate = 0
                var polledPixelFormat = -1
                var audioSetupAttemptedDuringPolling = false // New flag

                LOG.info("Player [Polling]: Starting metadata polling. Initial state from grabber - Format: '${g.format}', VidW: ${g.imageWidth}, VidH: ${g.imageHeight}, FPS: ${g.frameRate}, AudCh: ${g.audioChannels}, AudRate: ${g.sampleRate}, PixFmt: ${g.pixelFormat}")

                for (intervalMs in experimentalPollingIntervalsMs) {
                    if (!isActive) { LOG.info("Player [Polling]: Coroutine cancelled."); break }
                    pollingAttempts++
                    delay(intervalMs)
                    totalPollingTimeMs += intervalMs

                    // Re-fetch current grabber properties inside the loop
                    polledWidth = g.imageWidth; polledHeight = g.imageHeight; polledFormat = g.format
                    polledFps = g.frameRate; polledAudioChannels = g.audioChannels; polledSampleRate = g.sampleRate
                    polledPixelFormat = g.pixelFormat

                    if (pollingAttempts % 2 == 0 || intervalMs == experimentalPollingIntervalsMs.last()) {
                        LOG.info("Player [Polling]: After delay #$pollingAttempts (total ${totalPollingTimeMs}ms) - Format: '$polledFormat', VidW: $polledWidth, VidH: $polledHeight, FPS: $polledFps, AudCh: $polledAudioChannels, AudRate: $polledSampleRate, PixFmt: $polledPixelFormat")
                    }

                    // --- Attempt Audio Setup and Warm-up during Polling if params become valid ---
                    if (g.hasAudio() && polledAudioChannels > 0 && polledSampleRate > 0 && !audioSetupAttemptedDuringPolling) {
                        LOG.info("Player [Polling]: Valid audio params detected (Ch: $polledAudioChannels, Rate: $polledSampleRate). Attempting audio setup and warm-up...")
                        setupAudio(polledSampleRate, polledAudioChannels) // This now includes warm-up
                        audioSetupAttemptedDuringPolling = true // Mark as attempted
                        if (localSoundLine != null && localSoundLine!!.isActive) {
                            LOG.info("Player [Polling]: Audio line is ACTIVE after setup during polling.")
                        } else if (localSoundLine != null) {
                            LOG.warning("Player [Polling]: Audio line setup but NOT ACTIVE after setup during polling.")
                        } else {
                            LOG.warning("Player [Polling]: Audio setup FAILED during polling.")
                        }
                    }
                    // --- End of Audio Setup during Polling ---

                    val hasPolledValidVideo = g.hasVideo() && polledWidth > 0 && polledHeight > 0 && polledFps > 0.001
                    // Audio is considered "ready" for metadata purposes if setup was attempted or params are valid
                    val hasPolledValidAudioParams = g.hasAudio() && polledAudioChannels > 0 && polledSampleRate > 0
                    val isAudioConsideredReady = audioSetupAttemptedDuringPolling || hasPolledValidAudioParams

                    val hasPolledValidFormat = polledFormat != null && polledFormat.isNotEmpty()

                    if (hasPolledValidFormat && (hasPolledValidVideo || isAudioConsideredReady || (!g.hasVideo() && !g.hasAudio()))) {
                        LOG.info("Player [Polling]: SUCCESS! Validated metadata obtained (or audio setup attempted) after $pollingAttempts attempts and ${totalPollingTimeMs}ms.")
                        LOG.info("Player [Polling]: Successfully Polled Data - Format: '$polledFormat', VidW: $polledWidth, VidH: $polledHeight, FPS: $polledFps, AudCh: $polledAudioChannels, AudRate: $polledSampleRate, PixFmt: $polledPixelFormat, AudioSetupAttempted: $audioSetupAttemptedDuringPolling")
                        metadataObtainedDuringPolling = true
                        proceedAfterPolling = true
                        break
                    }
                }

                if (!metadataObtainedDuringPolling && isActive) {
                    LOG.warning("Player [Polling]: FAILED to obtain all critical metadata (or audio setup did not occur as expected) via polling for '$mediaPath' after $pollingAttempts attempts and $totalPollingTimeMs ms.")
                    LOG.warning("Player [Polling]: Will attempt to derive/finalize metadata from first few frames if possible.")
                    proceedAfterPolling = true
                } else if (!isActive) {
                    LOG.warning("Player: Coroutine cancelled after polling for '$mediaPath'.")
                    g.releaseQuietly(); grabber = null; return@launch
                }

                val initialWidth: Int
                val initialHeight: Int
                val initialPixelFormat: Int
                val initialFrameRate: Double
                val finalAudioChannels = if (metadataObtainedDuringPolling && polledAudioChannels > 0) polledAudioChannels else g.audioChannels
                val finalSampleRate = if (metadataObtainedDuringPolling && polledSampleRate > 0) polledSampleRate else g.sampleRate

                if (metadataObtainedDuringPolling) {
                    initialWidth = polledWidth; initialHeight = polledHeight; initialPixelFormat = polledPixelFormat
                    initialFrameRate = polledFps
                } else {
                    initialWidth = g.imageWidth; initialHeight = g.imageHeight; initialPixelFormat = g.pixelFormat
                    initialFrameRate = g.frameRate
                }

                LOG.info("Player: Effective initial params for setup (polling/grabber): VidW=$initialWidth, VidH=$initialHeight, FPS=$initialFrameRate, PixFmt=$initialPixelFormat, AudCh=$finalAudioChannels, AudRate=$finalSampleRate")

                if (!proceedAfterPolling && !isActive) { g.releaseQuietly(); grabber = null; return@launch }

                videoProcessingContext = Executors.newSingleThreadExecutor { r -> Thread(r, "Player-VideoProcessor") }.asCoroutineDispatcher()
                audioProcessingContext = Executors.newSingleThreadExecutor { r -> Thread(r, "Player-AudioProcessor") }.asCoroutineDispatcher()

                // Audio setup might have already been done during polling.
                // If not, it will be attempted from the first audio frame.
                val audioParamsFinalizedOnLoopStart = audioSetupAttemptedDuringPolling && localSoundLine != null

                if (!isActive) { g.releaseQuietly(); grabber = null; return@launch }

                playbackTimer = PlaybackTimer()
                val finalTimer = playbackTimer!!
                var firstValidTimestampFound = -1L
                var videoDimensionsReportedByEvent = false
                var reportedFrameRate = if (initialFrameRate > 0.001) initialFrameRate else 25.0

                if (g.hasVideo() && initialWidth > 0 && initialHeight > 0) {
                    LOG.info("Player: Reporting VideoDimensionsDetected (initial/polled): ${initialWidth}x${initialHeight}, FPS: $initialFrameRate, PixelFormat: $initialPixelFormat")
                    playerEventCallback(PlayerEvent.VideoDimensionsDetected(initialWidth, initialHeight, initialPixelFormat, initialFrameRate))
                    videoDimensionsReportedByEvent = true
                } else if (g.hasVideo()) {
                    LOG.warning("Player: Video stream present, but initial/polled dimensions are invalid (${initialWidth}x${initialHeight}). Will detect from first valid frame.")
                }

                if (isActive && !stopRequested) playerEventCallback(PlayerEvent.PlaybackStarted)
                LOG.info("Player: Starting main frame processing loop for '$mediaPath'. Audio finalized on start: $audioParamsFinalizedOnLoopStart")

                var audioParamsFinalizedInLoop = audioParamsFinalizedOnLoopStart

                while (isActive && !stopRequested) {
                    S_loopIteration++
                    var frame: Frame? = null
                    try {
                        frame = g.grab()
                    } catch (e: FrameGrabber.Exception) {
                        LOG.log(Level.WARNING, "Player: Error grabbing frame in main loop for '$mediaPath'.", e)
                        if (isActive) playerEventCallback(PlayerEvent.Error("Grab error", e))
                        break
                    }
                    if (frame == null) {
                        LOG.info("Player: End of stream reached in main loop for '$mediaPath'.")
                        if (isActive) playerEventCallback(PlayerEvent.EndOfMedia)
                        break
                    }

                    try {
                        // --- Finalize Audio Setup from First Valid Audio Frame if not done during polling ---
                        if (!audioParamsFinalizedInLoop && g.hasAudio() && frame.samples?.isNotEmpty() == true && frame.sampleRate > 0 && frame.audioChannels > 0) {
                            val srToUse = frame.sampleRate // Prefer frame's direct info if polling didn't yield
                            val chToUse = frame.audioChannels
                            LOG.info("Player [MainLoop]: Audio params not set during polling. Detected from frame: Rate $srToUse, Ch $chToUse. Finalizing audio setup.")
                            setupAudio(srToUse, chToUse) // This now includes warm-up
                            audioParamsFinalizedInLoop = true
                        }

                        if (!finalTimer.hasTimerStarted && frame.timestamp >= 0) {
                            finalTimer.start(frame.timestamp); firstValidTimestampFound = finalTimer.firstMediaFrameAbsoluteTimestampMicros
                            LOG.info("Player [MainLoop]: Timer started with TS: $firstValidTimestampFound")
                        } else if (firstValidTimestampFound == 0L && frame.timestamp > 0 && finalTimer.firstMediaFrameAbsoluteTimestampMicros == 0L) {
                            finalTimer.start(frame.timestamp); firstValidTimestampFound = frame.timestamp
                            LOG.info("Player [MainLoop]: Timer re-aligned from TS 0 to $firstValidTimestampFound")
                        }

                        if (frame.timestamp < firstValidTimestampFound && firstValidTimestampFound != 0L) {
                            LOG.warning("Player [MainLoop]: Frame TS ${frame.timestamp} < FirstValidTS $firstValidTimestampFound. Skipping.")
                            continue
                        }
                        val currentFrameRelativeTs = frame.timestamp - finalTimer.firstMediaFrameAbsoluteTimestampMicros

                        val isActualVideoFrame = frame.image != null && frame.imageWidth > 0 && frame.imageHeight > 0
                        val isActualAudioFrame = frame.samples?.isNotEmpty() == true

                        if (isActualVideoFrame && !videoDimensionsReportedByEvent && g.hasVideo()) {
                            val w = if (metadataObtainedDuringPolling && polledWidth > 0) polledWidth else frame.imageWidth
                            val h = if (metadataObtainedDuringPolling && polledHeight > 0) polledHeight else frame.imageHeight
                            val pxf = if (metadataObtainedDuringPolling && polledPixelFormat != -1) polledPixelFormat else g.pixelFormat
                            var fps = if (metadataObtainedDuringPolling && polledFps > 0.001) polledFps else g.frameRate
                            if (fps <= 0.001 && currentFrameRelativeTs > 0 && S_loopIteration > 10L && S_loopIteration % 10L == 0L) {
                                fps = (S_loopIteration.toDouble() * 1_000_000.0) / currentFrameRelativeTs.toDouble()
                                LOG.info("Player [MainLoop]: Estimating FPS from frame timestamps: $fps")
                            }

                            if (w > 0 && h > 0) {
                                LOG.info("Player [MainLoop]: Reporting/Confirming video dimensions: ${w}x${h}, PixFmt: $pxf, FPS: $fps")
                                playerEventCallback(PlayerEvent.VideoDimensionsDetected(w, h, pxf, fps))
                                videoDimensionsReportedByEvent = true
                                if (fps > 0.001) reportedFrameRate = fps
                            }
                        }
                        if (g.hasVideo() && !videoDimensionsReportedByEvent && S_loopIteration > 30L ) {
                            val gw = g.imageWidth; val gh = g.imageHeight; var gfps = g.frameRate; val gpxfmt = g.pixelFormat
                            if (gfps <= 0.001 && currentFrameRelativeTs > 0 && S_loopIteration > 1L) {
                                gfps = (S_loopIteration.toDouble() * 1_000_000.0) / currentFrameRelativeTs.toDouble()
                            }
                            if (gw > 0 && gh > 0) {
                                LOG.info("Player [MainLoop]: Fallback reporting video dimensions from grabber at iter $S_loopIteration: ${gw}x${gh}, FPS $gfps, PxFmt $gpxfmt")
                                playerEventCallback(PlayerEvent.VideoDimensionsDetected(gw, gh, gpxfmt, gfps)); videoDimensionsReportedByEvent = true; if(gfps > 0.001) reportedFrameRate = gfps
                            } else {
                                LOG.severe("Player [MainLoop]: Failed to get valid video dimensions for '$mediaPath' after ${S_loopIteration-1} frames. Grabber still reports ${gw}x${gh}. Terminating.")
                                if(isActive) playerEventCallback(PlayerEvent.Error("No valid video dimensions", IllegalStateException("Invalid video dimensions after retries")))
                                stopRequested = true; continue
                            }
                        }

                        if (S_loopIteration % 20L == 1L) {
                            val currentPlaybackTimeMicros = finalTimer.getCurrentRelativePlaybackTimeMicros(localSoundLine, reportedFrameRate, currentFrameRelativeTs, S_loopIteration == 1L && isActualVideoFrame)
                            LOG.info("Loop $S_loopIteration: AbsTS:${frame.timestamp}, RelTS:$currentFrameRelativeTs, Playback:$currentPlaybackTimeMicros, ReliableAudio:${finalTimer.isAudioClockReliableAndActive(localSoundLine)}, Vid:$isActualVideoFrame, Aud:$isActualAudioFrame, VidRpt:$videoDimensionsReportedByEvent, AudFin:$audioParamsFinalizedInLoop")
                        }

                        if (isActualVideoFrame && videoDimensionsReportedByEvent) {
                            val videoClone = frame.clone()
                            launch(videoProcessingContext!!) {
                                try {
                                    if (!isActive || stopRequested) return@launch
                                    var sleepMillis = 0L
                                    val currentSystemClockRelativeTimeMicros = finalTimer.getCurrentRelativePlaybackTimeMicros(localSoundLine, reportedFrameRate, currentFrameRelativeTs, false)
                                    val delayMicros = currentFrameRelativeTs - currentSystemClockRelativeTimeMicros
                                    if (delayMicros > videoTargetDelayToleranceMicros) {
                                        sleepMillis = delayMicros / 1000L; sleepMillis = min(sleepMillis, videoMaxSleepMs)
                                    } else if (delayMicros < -videoTargetDelayToleranceMicros && delayMicros < -200_000 && finalTimer.isAudioClockReliableAndActive(localSoundLine)) {
                                        LOG.warning("Vid Frame RelTS $currentFrameRelativeTs too late vs reliable audio $currentSystemClockRelativeTimeMicros. Skipping.")
                                        return@launch
                                    }
                                    if (sleepMillis >= minMeaningfulVideoDelayMs) delay(sleepMillis)
                                    if (isActive && !stopRequested) videoFrameOutputCallback(videoClone, currentFrameRelativeTs)
                                } catch (e: CancellationException) {}
                                catch (e: Exception) { LOG.log(Level.WARNING, "VidProc Error Loop $S_loopIteration", e) }
                                finally { videoClone.close() }
                            }
                        }
                        if (isActualAudioFrame && localSoundLine != null && audioParamsFinalizedInLoop) {
                            val audioClone = frame.clone()
                            launch(audioProcessingContext!!) {
                                try {
                                    if (isActive && !stopRequested) {
                                        if (audioDataOutputCallback != null) audioDataOutputCallback.invoke(audioClone.samples[0] as ShortBuffer, localSoundLine!!, audioClone)
                                        else playAudioFrameInternal(audioClone, localSoundLine!!)
                                    }
                                } finally { audioClone.close() }
                            }
                        }
                    } finally {
                        frame.close()
                    }

                    val isEffectivelyEmptyFrame = (frame.image == null || frame.imageWidth <= 0) && (frame.samples == null || frame.samples.isEmpty() || !frame.samples[0].hasRemaining())
                    if (isEffectivelyEmptyFrame && isActive && !stopRequested) { delay(5L) }
                    else if (finalTimer.hasTimerStarted) {
                        val relTsForSync = frame.timestamp - finalTimer.firstMediaFrameAbsoluteTimestampMicros
                        val frameReadAheadMicros = relTsForSync - finalTimer.getCurrentRelativePlaybackTimeMicros(localSoundLine, reportedFrameRate, relTsForSync, false)
                        if (frameReadAheadMicros > maxReadAheadBufferMicros) {
                            var mainLoopSleepMillis = (frameReadAheadMicros - maxReadAheadBufferMicros) / 1000L
                            mainLoopSleepMillis = min(mainLoopSleepMillis, mainLoopDelayCapMillisUnreliableTimer)
                            if (mainLoopSleepMillis >= minMeaningfulVideoDelayMs) delay(mainLoopSleepMillis)
                        }
                    }
                } // End While

            } catch (e: Exception) {
                if (e is CancellationException) {
                    LOG.info("Player: Playback coroutine cancelled for '$mediaPath'.")
                } else {
                    LOG.log(Level.SEVERE, "Player: Error in playback coroutine's main block for '$mediaPath'", e)
                    if(isActive) playerEventCallback(PlayerEvent.Error("Critical playback error: ${e.message}", e))
                }
            } finally {
                LOG.info("Player: Playback coroutine for '$mediaPath' finishing. Cleanup...")
                cleanupPlayerResources(releaseGrabber = true, closeSoundLine = true)
                LOG.info("Player: Playback coroutine for '$mediaPath' terminated.")
            }
        }
    }

    private suspend fun setupAudio(sampleRate: Int, audioChannels: Int) {
        LOG.info("Player: setupAudio CALLED with - SampleRate: $sampleRate, Channels: $audioChannels")
        if (audioChannels <= 0 || sampleRate <= 0) {
            LOG.warning("Player: setupAudio received invalid params. Rate $sampleRate, Ch $audioChannels. No audio line will be created.")
            localSoundLine = null
            return
        }
        LOG.info("Player: Attempting to setup audio with Rate $sampleRate, Channels $audioChannels.")
        var audioFormat = AudioFormat(sampleRate.toFloat(), 16, audioChannels, true, false /*S16LE*/)
        var lineInfo = DataLine.Info(SourceDataLine::class.java, audioFormat)

        if (!AudioSystem.isLineSupported(lineInfo)) {
            LOG.warning("Player: S16LE not supported. Trying S16BE for Rate $sampleRate, Ch $audioChannels.")
            audioFormat = AudioFormat(sampleRate.toFloat(), 16, audioChannels, true, true /*S16BE*/)
            lineInfo = DataLine.Info(SourceDataLine::class.java, audioFormat)
        }

        if (AudioSystem.isLineSupported(lineInfo)) {
            try {
                val frameSize = audioFormat.frameSize
                val warmUpDurationMillis = 100
                var warmUpBufferSize = (frameSize * audioFormat.frameRate * (warmUpDurationMillis / 1000.0f)).toInt()
                warmUpBufferSize = (warmUpBufferSize / frameSize) * frameSize
                warmUpBufferSize = warmUpBufferSize.coerceAtLeast(frameSize * 128).coerceAtMost(16384)

                val desiredPlayerBufferSize = (frameSize * audioFormat.frameRate * 0.5f).toInt()
                val minPlayerBufferSize = frameSize * 256
                val actualPlayerBufferSize = desiredPlayerBufferSize.coerceAtLeast(minPlayerBufferSize).coerceAtMost(1024 * 1024)

                localSoundLine?.close()
                val soundLine = AudioSystem.getLine(lineInfo) as SourceDataLine
                soundLine.open(audioFormat, actualPlayerBufferSize)
                soundLine.start()

                LOG.info("Player: Audio line opened and started. Format: $audioFormat, ActualBuffer: ${soundLine.bufferSize}, isOpen: ${soundLine.isOpen}, isActive: ${soundLine.isActive}, isRunning: ${soundLine.isRunning}")

                // --- Audio Line Warm-up ---
                if (!soundLine.isActive && soundLine.isOpen && soundLine.isRunning) {
                    LOG.info("Player: Audio line not immediately active. Attempting to write ${warmUpBufferSize} bytes of silence to warm up...")
                    try {
                        val silence = ByteArray(warmUpBufferSize)
                        val written = soundLine.write(silence, 0, silence.size)
                        LOG.info("Player: Wrote $written bytes of silence for warm-up.")
                        delay(50) // Give it a moment
                        if (soundLine.isActive) {
                            LOG.info("Player: Audio line is NOW ACTIVE after warm-up write.")
                        } else {
                            LOG.warning("Player: Audio line STILL NOT ACTIVE after warm-up write. isActive: ${soundLine.isActive}, isRunning: ${soundLine.isRunning}")
                        }
                    } catch (e: Exception) {
                        LOG.log(Level.WARNING, "Player: Exception during audio line warm-up write.", e)
                    }
                } else if (soundLine.isActive) {
                    LOG.info("Player: Audio line was already active immediately after start().")
                } else {
                    LOG.warning("Player: Audio line state unexpected after start: isOpen=${soundLine.isOpen}, isActive=${soundLine.isActive}, isRunning=${soundLine.isRunning}")
                }
                localSoundLine = soundLine // Assign only if setup is mostly successful

            } catch (e: Exception) {
                LOG.log(Level.SEVERE, "Player: Error setting up audio line for $audioFormat.", e)
                if(playerScope.isActive) playerEventCallback(PlayerEvent.Error(e.message ?: "Audio setup error", e))
                localSoundLine = null
            }
        } else {
            LOG.warning("Player: Neither S16LE nor S16BE audio line supported for: Rate $sampleRate, Channels $audioChannels.")
            if(playerScope.isActive) playerEventCallback(PlayerEvent.Error("S16 PCM audio format not supported", IllegalStateException("Unsupported audio format for playback")))
            localSoundLine = null
        }
    }

    private fun playAudioFrameInternal(audioFrame: Frame, soundLine: SourceDataLine) {
        if (!soundLine.isOpen || !playerScope.isActive || stopRequested) return
        val samplesBuffer = audioFrame.samples?.getOrNull(0)
        if (samplesBuffer == null || !samplesBuffer.hasRemaining()) {
            if (DETAILED_AUDIO_LOGGING) LOG.fine("Player [AudioInternal]: No audio samples or buffer empty in frame TS ${audioFrame.timestamp}.")
            return
        }
        val bytesToWrite: ByteArray? = when (samplesBuffer) {
            is ShortBuffer -> {
                val remainingShorts = samplesBuffer.remaining()
                val tempBytes = ByteArray(remainingShorts * 2)
                val byteBufferOrder = if (soundLine.format.isBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                val bb = ByteBuffer.allocate(tempBytes.size).order(byteBufferOrder)
                bb.asShortBuffer().put(samplesBuffer.slice())
                bb.rewind(); bb.get(tempBytes)
                tempBytes
            }
            is FloatBuffer -> {
                val remainingFloats = samplesBuffer.remaining()
                val tempShorts = ShortArray(remainingFloats)
                val slicedSamples = samplesBuffer.slice()
                for (i in tempShorts.indices) tempShorts[i] = (slicedSamples.get() * 32767.0f).coerceIn(-32768.0f, 32767.0f).toInt().toShort()
                val tempBytes = ByteArray(tempShorts.size * 2)
                val byteBufferOrder = if (soundLine.format.isBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
                ByteBuffer.wrap(tempBytes).order(byteBufferOrder).asShortBuffer().put(tempShorts)
                tempBytes
            }
            else -> { LOG.warning("[AudioInternal] Unhandled audio type: ${samplesBuffer.javaClass.name}. TS: ${audioFrame.timestamp}"); null }
        }
        if (bytesToWrite != null && bytesToWrite.isNotEmpty()) {
            try {
                // The warm-up should ideally make the line active.
                // If it's still not active here, it's a more persistent issue.
                if (!soundLine.isActive && soundLine.isOpen) {
                    LOG.warning("[AudioInternal] SoundLine open but still not active before write. TS: ${audioFrame.timestamp}. Last attempt to start.")
                    soundLine.start() // One last try
                    if (!soundLine.isActive) {
                        LOG.severe("[AudioInternal] SoundLine FAILED to activate despite warm-up and re-attempts. TS: ${audioFrame.timestamp}. No audio data will be written for this frame.");
                        return
                    }
                }
                if (soundLine.isActive) {
                    soundLine.write(bytesToWrite, 0, bytesToWrite.size)
                } else {
                    LOG.warning("[AudioInternal] SoundLine not active, cannot write audio. TS: ${audioFrame.timestamp}")
                }
            } catch (e: Exception) { LOG.log(Level.WARNING, "[AudioInternal] Exception writing audio. TS: ${audioFrame.timestamp}", e) }
        }
    }

    fun stop() {
        LOG.info("Player: stop() called.")
        stopRequested = true
        val jobToCancel = playerJob
        playerJob = null
        runBlocking(Dispatchers.Default) {
            try {
                jobToCancel?.cancelAndJoin()
                LOG.info("Player: Playback job cancelled/joined in stop().")
            } catch (e: Exception) { LOG.log(Level.WARNING, "Player: Exc joining job in stop()", e) }
        }
        cleanupPlayerResources(releaseGrabber = true, closeSoundLine = true)
        LOG.info("Player: stop() finished.")
    }

    fun release() {
        LOG.info("Player: release() called.")
        if (playerJob?.isActive == true || stopRequested) stop()
        else cleanupPlayerResources(releaseGrabber = true, closeSoundLine = true)
        try {
            if (playerScope.isActive) playerScope.cancel("Player released")
        } catch (e: Exception) { LOG.log(Level.WARNING, "Player: Exc cancelling scope", e) }
        grabber=null; localSoundLine=null; playbackTimer=null
        LOG.info("Player: release() finished.")
    }

    override fun close() { release() }

    private fun cleanupPlayerResources(releaseGrabber: Boolean, closeSoundLine: Boolean) {
        LOG.info("Player: cleanupPlayerResources. Grab: $releaseGrabber, Sound: $closeSoundLine. GrabberFmt: ${grabber?.format}")
        audioProcessingContext?.closeFinally(); audioProcessingContext = null
        videoProcessingContext?.closeFinally(); videoProcessingContext = null
        if (closeSoundLine) {
            localSoundLine?.let {
                try { if (it.isOpen) { it.flush(); it.drain(); it.stop(); it.close(); } }
                catch (e: Exception) { LOG.log(Level.WARNING, "Exc closing soundline", e) }
            }
            localSoundLine = null
        }
        if (releaseGrabber) { grabber?.releaseQuietly(); grabber = null }
        playbackTimer = null
        LOG.info("Player: cleanupPlayerResources finished.")
    }

    private fun FFmpegFrameGrabber?.releaseQuietly() {
        this?.let {
            try {
                LOG.info("Releasing grabber: ${try {it.format} catch(e:Throwable){"N/A"}}")
                it.stop(); it.release()
            } catch (e: Exception) { LOG.log(Level.WARNING, "Exc releasing grabber", e) }
        }
    }

    private fun ExecutorCoroutineDispatcher.closeFinally() {
        try { this.close() } catch (e: Exception) { LOG.log(Level.WARNING, "Exc closing dispatcher", e) }
    }

    private class PlaybackTimer {
        private var systemStartTimeNanos: Long = 0L
        val firstMediaFrameAbsoluteTimestampMicros: Long
            get() = _firstMediaFrameAbsoluteTimestampMicros
        private var _firstMediaFrameAbsoluteTimestampMicros: Long = -1L

        var hasTimerStarted: Boolean = false; private set
        private var lastKnownVideoFrameRelativeTsMicros: Long = -1L
        private var timeAtLastKnownVideoFrameNanos: Long = 0L

        fun start(firstFrameTsMicros: Long) {
            if (hasTimerStarted && firstFrameTsMicros == _firstMediaFrameAbsoluteTimestampMicros && _firstMediaFrameAbsoluteTimestampMicros != 0L) return
            if (hasTimerStarted && _firstMediaFrameAbsoluteTimestampMicros != 0L && firstFrameTsMicros == 0L) {
                LOG.warning("PlaybackTimer: Attempted re-start with TS 0 after non-zero TS. Ignoring.")
                return
            }
            this._firstMediaFrameAbsoluteTimestampMicros = firstFrameTsMicros
            this.systemStartTimeNanos = System.nanoTime()
            this.hasTimerStarted = true
            this.lastKnownVideoFrameRelativeTsMicros = -1L
            LOG.info("PlaybackTimer: Started/Re-aligned. FirstAbsTS: $firstFrameTsMicros us, SystemAnchor: $systemStartTimeNanos ns")
        }

        fun getCurrentRelativePlaybackTimeMicros(
            soundLine: SourceDataLine?,
            videoFrameRate: Double,
            currentVideoFrameRelativeTsIfVideo: Long,
            isVideoFrameJustProcessed: Boolean
        ): Long {
            if (!hasTimerStarted) return 0L
            if (isAudioClockReliableAndActive(soundLine)) return soundLine!!.microsecondPosition
            else {
                if (videoFrameRate > 0.001) {
                    if (isVideoFrameJustProcessed && currentVideoFrameRelativeTsIfVideo >= 0) {
                        lastKnownVideoFrameRelativeTsMicros = currentVideoFrameRelativeTsIfVideo
                        timeAtLastKnownVideoFrameNanos = System.nanoTime()
                        return currentVideoFrameRelativeTsIfVideo
                    } else if (lastKnownVideoFrameRelativeTsMicros != -1L) {
                        val nanosSinceLastVideo = System.nanoTime() - timeAtLastKnownVideoFrameNanos
                        return lastKnownVideoFrameRelativeTsMicros + (nanosSinceLastVideo / 1000L)
                    }
                }
                val elapsedMicros = (System.nanoTime() - systemStartTimeNanos) / 1000L
                return elapsedMicros
            }
        }
        fun isAudioClockReliableAndActive(soundLine: SourceDataLine?): Boolean = soundLine != null && soundLine.isOpen && soundLine.isRunning
    }
}

