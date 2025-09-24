package idv.neo.ffmpeg.media.player.core

import kotlinx.coroutines.*
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.bytedeco.javacv.FrameGrabber
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger
import javax.sound.sampled.*
import kotlin.math.min
import kotlin.random.Random // For random polling intervals

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

    // MediaClock
    @Volatile private var firstValidFrameTimestampMicros: Long = -1L
    @Volatile private var systemTimeAnchorNanos: Long = -1L
    @Volatile private var isMediaClockInitialized: Boolean = false

    // Player state flags - to ensure metadata is reported only once
    @Volatile private var videoDimensionsFinalized = false
    @Volatile private var audioParamsFinalized = false


    // Configs
    private val maxReadAheadBufferMicros = 700 * 1000L
    private val videoMaxSleepMsIfEarly = 100L
    private val videoCatchUpDropThresholdMicros = 200 * 1000L
    private val minMeaningfulSleepMs = 5L

    // Polling Config (using random intervals)
    private val numberOfPollingAttempts = 12
    private val minPollingIntervalMs = 800L
    private val maxPollingIntervalMs = 3000L


    companion object {
        private val LOG = Logger.getLogger(JavaFxSwingComposeFFmpegPlayer::class.java.name)
        private const val DETAILED_AUDIO_LOGGING = true
        @JvmStatic @Volatile var S_loopIteration = 0L
        private const val GENERAL_MAX_SLEEP_MILLIS = 3000L
    }

    // --- MediaClock methods ---
    private fun initializeMediaClock(firstFrameTimestamp: Long) {
        if (!isMediaClockInitialized || (this.firstValidFrameTimestampMicros <= 0L && firstFrameTimestamp >= 0L) ) {
            this.firstValidFrameTimestampMicros = firstFrameTimestamp
            this.systemTimeAnchorNanos = System.nanoTime()
            this.isMediaClockInitialized = true
            LOG.info("MediaClock: Initialized/Updated. FirstFrameTS: $firstValidFrameTimestampMicros us, SystemAnchor: $systemTimeAnchorNanos ns")
        }
    }

    private fun getMediaClockPositionMicros(): Long {
        if (!isMediaClockInitialized) {
            return 0L
        }
        localSoundLine?.let { line ->
            if (line.isOpen && line.isRunning && line.isActive()) {
                val audioTs = line.microsecondPosition
                if (audioTs >= 0L) {
                    if (S_loopIteration > 0L && S_loopIteration % 200L == 1L && DETAILED_AUDIO_LOGGING) LOG.info("MediaClock: Using AudioTrack position: $audioTs µs")
                    return audioTs
                } else if (S_loopIteration > 0L && S_loopIteration % 200L == 1L && DETAILED_AUDIO_LOGGING) LOG.warning("MediaClock: AudioTrack position negative ($audioTs µs). Falling back.")
            }
        }
        val elapsedNanos = System.nanoTime() - systemTimeAnchorNanos
        val estimatedMicros = elapsedNanos / 1000L
        if (S_loopIteration > 0L && S_loopIteration % 200L == 1L && DETAILED_AUDIO_LOGGING) LOG.info("MediaClock: Using System time fallback. Estimated: $estimatedMicros µs")
        return estimatedMicros
    }
    // --- End MediaClock methods ---

    fun start(mediaPath: String) {
        if (playerJob?.isActive == true) {
            LOG.warning("Player: start() called, but already running for media: ${grabber?.format}")
            return
        }
        stopRequested = false
        isMediaClockInitialized = false
        firstValidFrameTimestampMicros = -1L
        videoDimensionsFinalized = false
        audioParamsFinalized = false

        playerJob = playerScope.launch {
            S_loopIteration = 0L
            LOG.info("Player-Coroutine (${currentCoroutineContext()[CoroutineName]?.name}): Initializing for: $mediaPath")
            cleanupPlayerResources(releaseGrabber = true, closeSoundLine = true) // Initial cleanup

            var currentGrabberInstance: FFmpegFrameGrabber? = null
            try {
                LOG.info("Player: Creating FFmpegFrameGrabber for '$mediaPath'")
                currentGrabberInstance = FFmpegFrameGrabber(mediaPath)
                LOG.info("Player: Calling FFmpegFrameGrabber.start() for '$mediaPath'...")
                currentGrabberInstance.pixelFormat = avutil.AV_PIX_FMT_BGR24
                val timeBeforeGrabberStart = System.currentTimeMillis()
                currentGrabberInstance.start()
                val timeAfterGrabberStart = System.currentTimeMillis()
                LOG.info("Player: FFmpegFrameGrabber.start() returned for '$mediaPath' after ${timeAfterGrabberStart - timeBeforeGrabberStart} ms.")

                grabber = currentGrabberInstance
                val g = grabber ?: throw FrameGrabber.Exception("Grabber became null after start assignment")

                LOG.info("Player [After start() call]: Grabber's initial state - Format: '${g.format}', VidW: ${g.imageWidth}, AudCh: ${g.audioChannels} (May be incomplete)")

                var totalPollingTimeMs = 0L
                LOG.info("Player [Polling]: Starting Polling phase. Total attempts: $numberOfPollingAttempts")

                for (attempt in 1..numberOfPollingAttempts) {
                    if (!isActive || stopRequested) { LOG.info("Player [Polling]: Cancelled or stop requested."); break }
                    val randomIntervalMs = Random.nextLong(minPollingIntervalMs, maxPollingIntervalMs + 1)
                    delay(randomIntervalMs)
                    totalPollingTimeMs += randomIntervalMs
                    if (attempt % 3 == 0 || attempt == numberOfPollingAttempts) {
                        LOG.info("Player [Polling]: Attempt #$attempt/$numberOfPollingAttempts (interval ${randomIntervalMs}ms, total ${totalPollingTimeMs}ms). Grabber state - Fmt:'${g.format}', VidW:${g.imageWidth}, AudCh:${g.audioChannels}")
                    }
                }
                LOG.info("Player [Polling]: Finished. Total time: ${totalPollingTimeMs}ms. Proceeding to main loop.")

                if (!isActive || stopRequested) {
                    LOG.warning("Player: Cancelled or stop requested after polling.")
                    g.releaseQuietly(); grabber = null; return@launch
                }

                videoProcessingContext = Executors.newSingleThreadExecutor { r -> Thread(r, "Player-VideoProcessor").apply { isDaemon = true } }.asCoroutineDispatcher()
                audioProcessingContext = Executors.newSingleThreadExecutor { r -> Thread(r, "Player-AudioProcessor").apply { isDaemon = true } }.asCoroutineDispatcher()

                playerEventCallback(PlayerEvent.PlaybackStarted)
                LOG.info("Player: Starting main frame processing loop. Metadata will be finalized from initial frames.")

                var effectiveFrameRate = 0.0

                while (isActive && !stopRequested) {
                    S_loopIteration++
                    val frame = try { g.grab() } catch (e: FrameGrabber.Exception) {
                        LOG.log(Level.WARNING, "Player: Error grabbing frame.", e); playerEventCallback(PlayerEvent.Error("Error grabbing frame: ${e.message}", e)); break
                    }
                    if (frame == null) {
                        LOG.info("Player: End of stream."); playerEventCallback(PlayerEvent.EndOfMedia); break
                    }

                    if (g.hasVideo() && frame.image != null && frame.imageWidth > 0 && frame.imageHeight > 0 && !videoDimensionsFinalized) {
                        val width = frame.imageWidth
                        val height = frame.imageHeight
// **關鍵點：在 grab() 成功返回有效的圖像幀之後，從 grabber (g) 獲取 pixelFormat**
                        val currentGrabberPixelFormat = g.pixelFormat

                        // 主要依賴 currentGrabberPixelFormat
                        val pixFmtToSend = if (currentGrabberPixelFormat != -1) {
                            currentGrabberPixelFormat
                        } else {
                            // 如果在此時，即使 grab() 已經返回了有效的視訊幀，grabber.pixelFormat 仍然是 -1，
                            // 這通常表示 FFmpeg 未能確定流的像素格式，或者 grabber 的狀態未按預期更新。
                            // 這是一個比較嚴重的情況。
                            LOG.severe("Player [MainLoop]: CRITICAL - Could not determine a valid pixel format from grabber even after grabbing a video frame. Using -1.")
                            -1
                        }

                        effectiveFrameRate = if (g.frameRate > 0.001) g.frameRate else 25.0 // 優先使用 grabber 的幀率

                        playerEventCallback(PlayerEvent.VideoDimensionsDetected(width, height, pixFmtToSend, effectiveFrameRate))
                        videoDimensionsFinalized = true
                        LOG.info("Player [MainLoop]: Video dimensions finalized: ${width}x${height}, PixFmt: $pixFmtToSend, FPS: $effectiveFrameRate (GrabberFR: ${g.frameRate})")

                        if (!isMediaClockInitialized && frame.timestamp >= 0L) {
                            initializeMediaClock(frame.timestamp)
                            LOG.info("Player [MainLoop]: MediaClock initialized from 1st video frame TS: ${frame.timestamp}")
                        }
                    }

                    if (g.hasAudio() && frame.samples != null && !audioParamsFinalized) {
                        var audRateToUse = 0
                        var audChToUse = 0
                        var sourceOfParams = "unknown"

                        if (g.sampleRate > 0 && g.audioChannels > 0) {
                            audRateToUse = g.sampleRate
                            audChToUse = g.audioChannels
                            sourceOfParams = "grabber"
                            LOG.info("Player [MainLoop]: Audio params attempting from grabber: Rate $audRateToUse, Channels $audChToUse")
                        } else if (frame.sampleRate > 0 && frame.audioChannels > 0) {
                            audRateToUse = frame.sampleRate
                            audChToUse = frame.audioChannels
                            sourceOfParams = "frame (fallback)"
                            LOG.info("Player [MainLoop]: Audio params attempting from Frame as fallback: Rate $audRateToUse, Channels $audChToUse")
                        }

                        if (audChToUse > 0 && audRateToUse > 0) {
                            try {
                                // **修正點: 確保以 (sampleRate, channels) 的順序調用**
                                setupAudio(audRateToUse, audChToUse)
                                audioParamsFinalized = true
                                LOG.info("Player [MainLoop]: Audio setup finalized using params from $sourceOfParams.")
                                if (!isMediaClockInitialized && frame.timestamp >= 0L) {
                                    initializeMediaClock(frame.timestamp)
                                    LOG.info("Player [MainLoop]: MediaClock initialized from first audio frame TS: ${frame.timestamp}")
                                }
                            } catch (e: Exception) {
                                LOG.log(Level.WARNING, "Player [MainLoop]: Audio setup failed (source: $sourceOfParams).", e)
                                playerEventCallback(PlayerEvent.Error("Audio setup failed (source: $sourceOfParams): ${e.message}", e))
                                // Consider not setting audioParamsFinalized = true here if you want to retry with next frame's params
                            }
                        } else {
                            LOG.warning("Player [MainLoop]: No valid audio parameters obtained from grabber or frame for setup.")
                        }
                    }

                    if (!isMediaClockInitialized && frame.timestamp >= 0L) { initializeMediaClock(frame.timestamp) }
                    else if (isMediaClockInitialized && firstValidFrameTimestampMicros <= 0L && frame.timestamp > 0L) {
                        LOG.info("Player [MainLoop]: Re-aligning MediaClock from $firstValidFrameTimestampMicros to ${frame.timestamp}"); initializeMediaClock(frame.timestamp)
                    }

                    if (!isMediaClockInitialized) {
                        LOG.warning("Player [MainLoop]: MediaClock not initialized (TS: ${frame.timestamp}). Skipping frame."); frame.close(); delay(10); continue
                    }

                    val currentFrameAbsoluteTs = frame.timestamp
                    val currentFrameRelativeTs = currentFrameAbsoluteTs - firstValidFrameTimestampMicros
                    val hasImageAndReady = g.hasVideo() && frame.image != null && videoDimensionsFinalized
                    val hasAudioAndReady = g.hasAudio() && frame.samples != null && audioParamsFinalized && localSoundLine != null

                    if (S_loopIteration % 100L == 1L) {
                        LOG.info("Player [MainLoop $S_loopIteration]: AbsTS=${currentFrameAbsoluteTs}, RelTS=${currentFrameRelativeTs}, MediaClock=${getMediaClockPositionMicros()}, ImgRdy=$hasImageAndReady, AudRdy=$hasAudioAndReady")
                    }

                    if (hasAudioAndReady) {
                        val audioFrameToPlay = frame.clone()
                        launch(audioProcessingContext!!) { try { if (!stopRequested) playAudioSample(audioFrameToPlay, localSoundLine!!) } catch (e: Exception) { LOG.log(Level.WARNING, "Audio play error.", e) } finally { audioFrameToPlay.close() } }
                    }

                    if (hasImageAndReady) {
                        val videoFrameToRender = frame.clone()
                        launch(videoProcessingContext!!) { try { if (!stopRequested) {
                            val clockTime = getMediaClockPositionMicros(); val delayNeeded = currentFrameRelativeTs - clockTime
                            if (delayNeeded.compareTo(minMeaningfulSleepMs * 1000L) > 0) {
                                val sleepMs = min(delayNeeded / 1000L, videoMaxSleepMsIfEarly)
                                if (S_loopIteration % 50L == 1L && DETAILED_AUDIO_LOGGING) LOG.info("Video early: $sleepMs ms")
                                if (sleepMs > 0L) delay(sleepMs)
                            } else if (delayNeeded.compareTo(-videoCatchUpDropThresholdMicros) < 0 && effectiveFrameRate > 0.0) {
                                if (S_loopIteration % 50L == 1L && DETAILED_AUDIO_LOGGING) LOG.warning("Video LATE: ${-delayNeeded/1000L} ms")
                            }
                            videoFrameOutputCallback(videoFrameToRender, currentFrameRelativeTs)
                        } } catch (e: CancellationException) { throw e } catch (e: Exception) { LOG.log(Level.WARNING, "Video process error.", e) } finally { videoFrameToRender.close() } }
                    }

                    if ((g.hasVideo() && !videoDimensionsFinalized) || (g.hasAudio() && !audioParamsFinalized)) {
                        if (S_loopIteration > (numberOfPollingAttempts + 300)) { // Give more time for metadata from frames
                            LOG.severe("Stuck finalizing metadata from frames. Aborting."); playerEventCallback(PlayerEvent.Error("Failed to finalize metadata from frames.", null)); break
                        }
                    }
                    frame.close()
                    val clockTime = getMediaClockPositionMicros(); val readAhead = currentFrameRelativeTs - clockTime
                    if (readAhead.compareTo(maxReadAheadBufferMicros) > 0) {
                        val sleepMs = (readAhead - maxReadAheadBufferMicros) / 1000L
                        if (sleepMs >= minMeaningfulSleepMs) { if (S_loopIteration % 100L == 1L) LOG.info("Backpressure sleep: $sleepMs ms"); delay(sleepMs) }
                    } else if (!hasImageAndReady && !hasAudioAndReady && (videoDimensionsFinalized || audioParamsFinalized)) { delay(1L) }
                }
            } catch (e: FrameGrabber.Exception) { LOG.log(Level.SEVERE, "Grabber error", e); playerEventCallback(PlayerEvent.Error("Grabber error: ${e.message}", e)) }
            catch (e: LineUnavailableException) { LOG.log(Level.SEVERE, "Audio line error", e); playerEventCallback(PlayerEvent.Error("Audio line error: ${e.message}", e)) }
            catch (e: CancellationException) { LOG.info("Coroutine cancelled: ${e.message}"); if (e.message != "Player stop requested by API" && e.message != "Job cancelled for close") throw e }
            catch (e: Exception) { LOG.log(Level.SEVERE, "Unexpected error", e); playerEventCallback(PlayerEvent.Error("Unexpected error: ${e.message}", e)) }
            finally {
                LOG.info("Coroutine finishing: stopReq=$stopRequested, active=$isActive, jobCancelled=${playerJob?.isCancelled}")
                if (isActive && !stopRequested && playerJob?.isCancelled == false) playerEventCallback(PlayerEvent.EndOfMedia)
                cleanupPlayerResources(releaseGrabber = true, closeSoundLine = true, forceShutdownExecutors = playerJob?.isCancelled == true)
                LOG.info("Coroutine terminated.")
            }
        }
    }

    fun stop() {
        LOG.info("Player.stop called.")
        stopRequested = true
        playerJob?.cancel(CancellationException("Player stop requested by API"))
    }

    override fun close() {
        LOG.info("Player.close called.")
        stopRequested = true
        val jobToWait = playerJob

        if (jobToWait != null) {
            if (jobToWait.isActive) {
                LOG.info("Player.close: Cancelling active player job.")
                jobToWait.cancel(CancellationException("Job cancelled for close"))
                // It's generally better to let the job's finally block handle cleanup.
                // However, to ensure close() is somewhat synchronous for external callers,
                // we attempt to join, but with a timeout.
                // No need to check currentCoroutineContext if runBlocking is used here.
                runBlocking {
                    try {
                        withTimeout(GENERAL_MAX_SLEEP_MILLIS / 2) { // Shorter timeout for join after cancel
                            LOG.info("Player.close: Attempting to join cancelled job...")
                            jobToWait.join()
                            LOG.info("Player job joined successfully in close.")
                        }
                    } catch (e: TimeoutCancellationException) {
                        LOG.warning("Player.close: Timeout waiting for player job to join. Cleanup might be forced. Job state: Active=${jobToWait.isActive}, Cancelled=${jobToWait.isCancelled}")
                    } catch (e: CancellationException) {
                        LOG.info("Player.close: Job was already cancelled or join was interrupted.")
                    } catch (e: Exception) {
                        LOG.log(Level.WARNING, "Player.close: Exception during player job join.", e)
                    }
                }
            } else {
                LOG.info("Player.close: Player job was not active (already completed or cancelled).")
            }
        } else {
            LOG.info("Player.close: No player job to wait for (was null).")
        }
        // Ensure cleanup is called, especially if the job was null or didn't join cleanly.
        cleanupPlayerResources(releaseGrabber = true, closeSoundLine = true, forceShutdownExecutors = true)
        LOG.info("Player.close finished.")
    }

    @Throws(LineUnavailableException::class, SecurityException::class)
    private fun setupAudio(actualSampleRate: Int, actualChannels: Int) { // Renamed parameters for clarity
        if (localSoundLine?.isOpen == true) {
            LOG.info("Player: Audio line re-setup. Closing existing line.")
            localSoundLine?.drain(); localSoundLine?.stop(); localSoundLine?.close()
        }
        // Correctly use actualSampleRate and actualChannels
        val audioFormatLE = AudioFormat(actualSampleRate.toFloat(), 16, actualChannels, true, false)
        val audioFormatBE = AudioFormat(actualSampleRate.toFloat(), 16, actualChannels, true, true)
        var lineToUse: SourceDataLine? = null
        var chosenFormat: AudioFormat? = null

        LOG.info("Player [setupAudio]: Attempting with Rate $actualSampleRate, Channels $actualChannels") // Add this log

        if (AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, audioFormatLE))) {
            lineToUse = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, audioFormatLE)) as SourceDataLine
            chosenFormat = audioFormatLE
        } else if (AudioSystem.isLineSupported(DataLine.Info(SourceDataLine::class.java, audioFormatBE))) {
            lineToUse = AudioSystem.getLine(DataLine.Info(SourceDataLine::class.java, audioFormatBE)) as SourceDataLine
            chosenFormat = audioFormatBE
            LOG.info("Player [setupAudio]: Using S16BE audio format as S16LE not supported.")
        } else {
            // This is where the error "S16LE/BE not supported: X Hz, Y ch." originates if parameters are wrong
            throw LineUnavailableException("Neither S16LE nor S16BE supported for $actualSampleRate Hz, $actualChannels ch.")
        }

        localSoundLine = lineToUse.apply {
            val bufFormat = chosenFormat!!
            var bytesPerFrame = bufFormat.frameSize
            if (bytesPerFrame == AudioSystem.NOT_SPECIFIED) bytesPerFrame = (bufFormat.sampleSizeInBits / 8) * bufFormat.channels
            val bufferDurationMillis = 750
            val desiredBufferSize = Math.max(16384, (bytesPerFrame * bufFormat.frameRate * (bufferDurationMillis / 1000.0f)).toInt())

            LOG.info("Player [setupAudio]: Opening audio line. Requested Format: $bufFormat, Desired Buffer: $desiredBufferSize bytes.")
            open(bufFormat, desiredBufferSize)
            start()
            if (!this.isRunning || !this.isActive()) {
                LOG.warning("Player [setupAudio]: Audio line opened but state is: isRunning=${this.isRunning}, isActive=${this.isActive()}. May need data to become fully active.")
            } else {
                LOG.info("Player [setupAudio]: Audio line opened and started. Actual Format: ${this.format}, Buffer: ${this.bufferSize}. Running: ${this.isRunning}, Active: ${this.isActive()}")
            }
        }
    }

    private fun playAudioSample(audioFrame: Frame, line: SourceDataLine) {
        audioDataOutputCallback?.let { cb -> val s = audioFrame.samples?.get(0) as? ShortBuffer; if (s != null) cb(s, line, audioFrame) else if (DETAILED_AUDIO_LOGGING && S_loopIteration % 100L == 1L) LOG.warning("Callback: no samples."); return }
        val sb = audioFrame.samples?.get(0) as? ShortBuffer; if (sb == null || !line.isOpen) { if (DETAILED_AUDIO_LOGGING && S_loopIteration % 100L == 1L) LOG.warning("PlayAudio: samples null or line closed."); return }
        val numSamples = sb.remaining(); if (numSamples == 0) return
        if (!line.isRunning && line.isOpen) { line.start(); if (!line.isRunning && DETAILED_AUDIO_LOGGING) LOG.warning("Line re-started, still not running.") }
        val bb = ByteBuffer.allocate(numSamples * 2); bb.order(if (line.format.isBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until numSamples) bb.putShort(sb.get(sb.position() + i)); val data = bb.array()
        try {
            val written = line.write(data, 0, data.size)
            if (written < data.size && DETAILED_AUDIO_LOGGING) LOG.warning("Partial write: $written of ${data.size}.")
            if (S_loopIteration % 200L == 1L && DETAILED_AUDIO_LOGGING && written > 0) LOG.info("Wrote $written bytes. Line: run=${line.isRunning}, active=${line.isActive()}")
        } catch (e: IllegalArgumentException) { LOG.log(Level.SEVERE, "Audio write error (line closed/format?): open=${line.isOpen}, fmt=${line.format}", e) }
    }

    private fun cleanupPlayerResources(releaseGrabber: Boolean, closeSoundLine: Boolean, forceShutdownExecutors: Boolean = false) {
        LOG.info("Cleanup: Grab=$releaseGrabber, Sound=$closeSoundLine, ForceExec=$forceShutdownExecutors")
        if (releaseGrabber) { grabber?.let { g -> try { g.stop(); g.release(); LOG.info("Grabber released.") } catch (e: Exception) { LOG.warning("Grabber release error: $e") } }; grabber = null }
        if (closeSoundLine) { localSoundLine?.let { l -> if (l.isOpen) try { l.drain(); l.stop(); l.close(); LOG.info("SoundLine closed.") } catch (e: Exception) { LOG.warning("SoundLine close error: $e") } }; localSoundLine = null }

        listOf(audioProcessingContext, videoProcessingContext).forEachIndexed { i, ctxDispatcher ->
            (ctxDispatcher as? Closeable)?.close() // ExecutorCoroutineDispatcher is Closeable
            if(i==0) audioProcessingContext = null else videoProcessingContext = null
            LOG.fine("Ctx ${if(i==0)"Audio" else "Video"} closed.")
        }
        isMediaClockInitialized = false; videoDimensionsFinalized = false; audioParamsFinalized = false
        LOG.info("Cleanup finished.")
    }
}

private fun FFmpegFrameGrabber.hasVideo(): Boolean = this.videoStream >= 0 && this.imageWidth > 0 && this.imageHeight > 0
private fun FFmpegFrameGrabber.hasAudio(): Boolean = this.audioStream >= 0 && this.audioChannels > 0 && this.sampleRate > 0
private fun FFmpegFrameGrabber.releaseQuietly() { try { this.release() } catch (e: Exception) { /* ignore */ } }
