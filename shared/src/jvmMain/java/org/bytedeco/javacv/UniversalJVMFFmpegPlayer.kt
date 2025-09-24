package org.bytedeco.javacv

import javax.sound.sampled.*
import java.nio.ShortBuffer
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

class UniversalJVMFFmpegPlayer private constructor(builder: Builder) {

    private val videoFrameOutputCallback: VideoFrameOutputCallback = builder.videoFrameOutputCallback
    private val audioDataOutputCallback: AudioDataOutputCallback? = builder.audioDataOutputCallback
    private val playerEventCallback: PlayerEventCallback? = builder.playerEventCallback

    private var grabber: FFmpegFrameGrabber? = null
    private var localSoundLine: SourceDataLine? = null
    private var frameProcessingExecutor: ExecutorService? = null
    private var audioPlaybackExecutor: ExecutorService? = null
    private var playbackTimer: PlaybackTimer? = null
    @Volatile private var playThread: Thread? = null
    @Volatile private var stopRequested: Boolean = false
    private var grabAttemptCounter: Int = 0

    // Sync parameters
    private val maxReadAheadBufferMicros: Long = builder.maxReadAheadBufferMicros
    private val videoDelayCapMillisUnreliableTimer: Long = builder.videoDelayCapMillisUnreliableTimer
    private val videoMaxSleepReliableMs: Long = builder.videoMaxSleepReliableMs
    private val mainLoopDelayCapMillisUnreliableTimer: Long = builder.mainLoopDelayCapMillisUnreliableTimer
    private val generalMaxSleepMillis: Long = builder.generalMaxSleepMillis
    private val minMeaningfulVideoDelayMs: Long = builder.minMeaningfulVideoDelayMs


    companion object {
        private val LOG: Logger = Logger.getLogger(UniversalJVMFFmpegPlayer::class.java.name)
        @Volatile @JvmStatic var S_loopIteration: Int = 0
        private const val KOTLIN_DETAILED_AUDIO_LOGGING = true
    }

    // --- PlaybackTimer Inner Class (修正) ---
    private class PlaybackTimer {
        private var timerStartTimeNanos: Long = -1L
        private var timerFirstFrameAbsoluteTimestampMicros: Long = -1L
        private val soundLine: SourceDataLine?
        private var timerStarted: Boolean = false
        private var soundLineClockSuccessfullyUsed: Boolean = false
        private var soundLineEverRan: Boolean = false

        constructor(soundLine: SourceDataLine?) {
            this.soundLine = soundLine
        }

        constructor() {
            this.soundLine = null
        }

        fun start(firstValidFrameTimestampMicros: Long) {
            if (timerStarted) {
                return
            }
            this.timerFirstFrameAbsoluteTimestampMicros = firstValidFrameTimestampMicros
            this.timerStartTimeNanos = System.nanoTime()
            this.timerStarted = true
            this.soundLineClockSuccessfullyUsed = false
            this.soundLineEverRan = false
            if (KOTLIN_DETAILED_AUDIO_LOGGING) {
                if (soundLine != null) {
                    LOG.info("PlaybackTimer (${this.hashCode().toString(16)}): Started. First frame TS (abs): $firstValidFrameTimestampMicros us. Will attempt to use soundLine. System time recorded.")
                } else {
                    LOG.info("PlaybackTimer (${this.hashCode().toString(16)}): Started. First frame TS (abs): $firstValidFrameTimestampMicros us. Using System.nanoTime() (no soundLine).")
                }
            }
        }

        fun elapsedMicros(): Long {
            if (!timerStarted) return 0L

            if (soundLine != null && soundLine.isOpen && soundLine.isRunning) {
                if (!soundLineEverRan) {
                    soundLineEverRan = true
                    if (KOTLIN_DETAILED_AUDIO_LOGGING) LOG.info("PlaybackTimer (${this.hashCode().toString(16)}): SoundLine is NOW RUNNING. Will use its position.")
                }
                soundLineClockSuccessfullyUsed = true
                return soundLine.microsecondPosition
            } else {
                if (soundLineClockSuccessfullyUsed) {
                    if (KOTLIN_DETAILED_AUDIO_LOGGING) LOG.warning("PlaybackTimer (${this.hashCode().toString(16)}): SoundLine was used but is NOT RUNNING NOW. Reverting to System.nanoTime() based progress.")
                }
                soundLineClockSuccessfullyUsed = false
                val systemDurationMicros = (System.nanoTime() - timerStartTimeNanos) / 1000L
                if (soundLine != null && (S_loopIteration < 20 || S_loopIteration % 100 == 1)) {
                    if (KOTLIN_DETAILED_AUDIO_LOGGING) LOG.warning("PlaybackTimer (${this.hashCode().toString(16)}): Using System.nanoTime() for elapsed. SystemDuration: $systemDurationMicros us. SoundLine state: isOpen=${soundLine.isOpen}, isRunning=${soundLine.isRunning}, EverRan=$soundLineEverRan")
                }
                return systemDurationMicros
            }
        }

        fun isAudioClockActive(): Boolean {
            if (!timerStarted || soundLine == null) return false
            return soundLine.isOpen && soundLine.isRunning
        }

        fun getFirstFrameAbsoluteTimestampMicros(): Long { // <--- **修正點 1：添加此方法**
            return timerFirstFrameAbsoluteTimestampMicros
        }

        fun hasTimerStarted(): Boolean = timerStarted
    }
    // --- End PlaybackTimer ---

    fun interface VideoFrameOutputCallback {
        fun onVideoFrameProcessed(videoFrame: Frame?, relativeTimestampMicros: Long)
    }

    fun interface AudioDataOutputCallback {
        fun onAudioDataAvailable(samples: ShortBuffer?, lineToWriteTo: SourceDataLine?, originalFrame: Frame?)
    }

    interface PlayerEventCallback {
        fun onVideoDimensionsDetected(width: Int, height: Int, pixelFormat: Int)
        fun onPlaybackStarted()
        fun onEndOfMedia()
        fun onError(errorMessage: String, e: Exception?)
    }

    class Builder(
        internal val videoFrameOutputCallback: VideoFrameOutputCallback,
        internal val playerEventCallback: PlayerEventCallback?
    ) {
        internal var audioDataOutputCallback: AudioDataOutputCallback? = null
        internal var maxReadAheadBufferMicros: Long = 700 * 1000L
        internal var videoDelayCapMillisUnreliableTimer: Long = 1000L
        internal var videoMaxSleepReliableMs: Long = 1000L
        internal var mainLoopDelayCapMillisUnreliableTimer: Long = 200L
        internal var generalMaxSleepMillis: Long = 2000L
        internal var minMeaningfulVideoDelayMs: Long = 8L

        fun audioDataOutputCallback(callback: AudioDataOutputCallback?) = apply { this.audioDataOutputCallback = callback }
        fun maxReadAheadBufferMicros(value: Long) = apply { if (value > 0) this.maxReadAheadBufferMicros = value }
        fun videoDelayCapMillisUnreliableTimer(value: Long) = apply { if (value > 0) this.videoDelayCapMillisUnreliableTimer = value }
        fun videoMaxSleepReliableMs(value: Long) = apply { if (value > 0) this.videoMaxSleepReliableMs = value }
        fun mainLoopDelayCapMillisUnreliableTimer(value: Long) = apply { if (value > 0) this.mainLoopDelayCapMillisUnreliableTimer = value }
        fun generalMaxSleepMillis(value: Long) = apply { if (value > 0) this.generalMaxSleepMillis = value }
        fun minMeaningfulVideoDelayMs(value: Long) = apply { if (value >= 0) this.minMeaningfulVideoDelayMs = value }

        fun build(): UniversalJVMFFmpegPlayer = UniversalJVMFFmpegPlayer(this)
    }

    fun start(mediaPath: String) {
        if (playThread?.isAlive == true) {
            LOG.warning("Player: start() called, but already running.")
            return
        }
        stopRequested = false

        val videoFrameProcessorFactory = ThreadFactory { r -> Thread(r, "Player-VideoProcessor").apply { isDaemon = true } }
        val audioProcessorFactory = ThreadFactory { r -> Thread(r, "Player-AudioProcessor").apply { isDaemon = true } }

        playThread = Thread {
            S_loopIteration = 0
            this.grabAttemptCounter = 0

            shutdownExecutor(frameProcessingExecutor, "Previous VideoExecutor")
            frameProcessingExecutor = Executors.newSingleThreadExecutor(videoFrameProcessorFactory)

            shutdownExecutor(audioPlaybackExecutor, "Previous AudioExecutor")
            audioPlaybackExecutor = Executors.newSingleThreadExecutor(audioProcessorFactory)

            this.grabber = null
            this.localSoundLine = null
            this.playbackTimer = null

            LOG.info("Player-Thread (${Thread.currentThread().name}): Starting playback logic for: $mediaPath")
            var currentGrabber: FFmpegFrameGrabber? = null

            try {
                LOG.info("Player: Initializing FFmpegFrameGrabber for: $mediaPath")
                val tempGrabber = FFmpegFrameGrabber(mediaPath)
                LOG.info("Player: Calling grabber.start()...")
                tempGrabber.start()
                currentGrabber = tempGrabber
                this.grabber = currentGrabber

                val actualPixelFormat = currentGrabber.pixelFormat
                val frameWidth = currentGrabber.imageWidth
                val frameHeight = currentGrabber.imageHeight
                val frameRate = currentGrabber.frameRate
                val audioChannels = currentGrabber.audioChannels
                val sampleRate = currentGrabber.sampleRate

                LOG.info("Player: Grabber started. PixelFormat:${actualPixelFormat}, ImageW/H:${frameWidth}/${frameHeight}, AudioChannels:${audioChannels}, SampleRate:${sampleRate}, FrameRate:${frameRate}")

                if (frameWidth <= 0 || frameHeight <= 0) {
                    val errorMsg = "Invalid video dimensions after grabber.start(). Width: $frameWidth, Height: $frameHeight"
                    LOG.severe("Player: $errorMsg")
                    playerEventCallback?.onError(errorMsg, null)
                    return@Thread // Exit this lambda
                }
                playerEventCallback?.onVideoDimensionsDetected(frameWidth, frameHeight, actualPixelFormat)

                if (audioChannels > 0) {
                    val audioFormat = AudioFormat(sampleRate.toFloat(), 16, audioChannels, true, true)
                    val info = DataLine.Info(SourceDataLine::class.java, audioFormat)
                    if (!AudioSystem.isLineSupported(info)) {
                        throw LineUnavailableException("Audio format $audioFormat not supported by AudioSystem.")
                    }
                    localSoundLine = (AudioSystem.getLine(info) as SourceDataLine).apply {
                        var bytesPerFrame = audioFormat.frameSize
                        if (bytesPerFrame == AudioSystem.NOT_SPECIFIED) {
                            bytesPerFrame = (audioFormat.sampleSizeInBits / 8) * audioFormat.channels
                        }
                        val bufferTimeMillis = 750
                        val desiredBufferSize = (bytesPerFrame * audioFormat.frameRate * (bufferTimeMillis / 1000.0f)).toInt()
                        LOG.info("Player: Desired audio buffer size: $desiredBufferSize bytes for $bufferTimeMillis ms.")
                        open(audioFormat, desiredBufferSize)
                        start()
                    }
                    playbackTimer = PlaybackTimer(localSoundLine)
                    LOG.info("Player: Audio line opened (buffer: ${localSoundLine?.bufferSize} bytes) and started.")
                } else {
                    playbackTimer = PlaybackTimer()
                    LOG.info("Player: No audio channels. PlaybackTimer uses System.nanoTime().")
                }

                val currentTimer = playbackTimer ?: throw IllegalStateException("PlaybackTimer not initialized")
                val finalSoundLineRef = localSoundLine

                if (finalSoundLineRef != null) {
                    LOG.info("Player: --- Starting Audio Warm-up Stage ---")
                    for (warmupIter in 0 until 30) {
                        if (stopRequested || Thread.currentThread().isInterrupted) { LOG.info("Player: [Warmup] Interrupted."); break }
                        var warmupFrame: Frame? = null
                        try { warmupFrame = currentGrabber.grab() }
                        catch (e: FrameGrabber.Exception) { LOG.log(Level.WARNING, "[Warmup] Error grabbing frame", e); break }
                        if (warmupFrame == null) { LOG.info("Player: [Warmup] Grabber returned NULL. Ending warm-up."); break }

                        S_loopIteration++
                        this.grabAttemptCounter++

                        if (!currentTimer.hasTimerStarted()) {
                            if (warmupFrame.timestamp > 0L || currentGrabber.audioChannels == 0) { //  Ensure Long comparison
                                LOG.info("Player [Warmup, Iter $S_loopIteration] First frame for timer (TS_abs: ${warmupFrame.timestamp}us). Starting PlaybackTimer.")
                                currentTimer.start(warmupFrame.timestamp)
                            } else if (warmupFrame.timestamp == 0L && KOTLIN_DETAILED_AUDIO_LOGGING) {
                                LOG.info("Player [Warmup, Iter $S_loopIteration] Timer not started, frame TS is 0.")
                            }
                        }

                        if (warmupFrame.samples != null && warmupFrame.samples[0] != null) {
                            val audioFrameToWarm = warmupFrame.clone()
                            audioPlaybackExecutor?.submit {
                                if (stopRequested) { audioFrameToWarm.close(); return@submit }
                                try {
                                    if (audioDataOutputCallback != null) {
                                        audioDataOutputCallback.onAudioDataAvailable(audioFrameToWarm.samples[0] as? ShortBuffer, finalSoundLineRef, audioFrameToWarm)
                                    } else {
                                        playAudioFrameInternal(audioFrameToWarm, finalSoundLineRef)
                                    }
                                } catch (e: Exception) {
                                    LOG.log(Level.WARNING, "[Warmup] Audio submission/processing error", e)
                                } finally {
                                    audioFrameToWarm.close()
                                }
                            }
                        }
                        warmupFrame.close()
                        try { Thread.sleep(5) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
                        if (currentTimer.isAudioClockActive()) {
                            LOG.info("Player: [Warmup] Audio clock ACTIVE.")
                            break
                        }
                    }
                    if (!currentTimer.isAudioClockActive()) {
                        LOG.warning("Player: --- Audio Warm-up: Audio Clock DID NOT become active. ---")
                    }
                }

                playerEventCallback?.onPlaybackStarted()
                LOG.info("Player: Starting main processing loop...")

                while (!Thread.currentThread().isInterrupted && !stopRequested) {
                    S_loopIteration++
                    this.grabAttemptCounter++
                    var frame: Frame? = null
                    try {
                        frame = currentGrabber.grab()
                    } catch (e: FrameGrabber.Exception) {
                        LOG.log(Level.WARNING, "Player: Error grabbing frame in main loop", e)
                        playerEventCallback?.onError("Error grabbing frame", e)
                        break
                    }
                    if (frame == null) {
                        LOG.info("Player: [Iter $S_loopIteration] Grabber returned NULL. Ending loop.")
                        playerEventCallback?.onEndOfMedia()
                        break
                    }

                    if (!currentTimer.hasTimerStarted()) {
                        if (frame.timestamp > 0L || currentGrabber.audioChannels == 0) { // Ensure Long comparison
                            LOG.info("Player [MainLoop, Iter $S_loopIteration] Timer not started. Starting with TS: ${frame.timestamp}us.")
                            currentTimer.start(frame.timestamp)
                        } else if (KOTLIN_DETAILED_AUDIO_LOGGING) {
                            LOG.info("Player [MainLoop, Iter $S_loopIteration] Timer not started, frame TS is 0.")
                        }
                    }
                    if (!currentTimer.hasTimerStarted()) {
                        LOG.warning("Player [MainLoop, Iter $S_loopIteration] Timer still not started. Skipping frame.")
                        frame.close()
                        try { Thread.sleep(10) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
                        continue
                    }

                    val currentFrameAbsoluteTimestampMicros = frame.timestamp
                    val currentFrameRelativeTimestampMicros = currentFrameAbsoluteTimestampMicros - currentTimer.getFirstFrameAbsoluteTimestampMicros()
                    val currentPlaybackTimeMicros = currentTimer.elapsedMicros()

                    val hasImage = frame.image != null && frame.imageHeight > 0 && frame.imageWidth > 0
                    val hasAudio = frame.samples != null && frame.samples[0] != null

                    if (S_loopIteration <= 10 || S_loopIteration % 50 == 1) {
                        LOG.info(String.format(
                            "Player: [Iter %d] FrameTS_abs:%,d, FrameTS_rel:%,d, PlaybackTime:%,d, AudioClockActive:%b, Img:%b, Aud:%b",
                            S_loopIteration, currentFrameAbsoluteTimestampMicros, currentFrameRelativeTimestampMicros, currentPlaybackTimeMicros,
                            currentTimer.isAudioClockActive(), hasImage, hasAudio
                        ))
                    }

                    if (hasImage) {
                        val videoDelayCapToUse = videoDelayCapMillisUnreliableTimer * 1000L
                        val videoDelayMicros = currentFrameRelativeTimestampMicros - currentPlaybackTimeMicros

                        if (videoDelayMicros.compareTo(-videoDelayCapToUse) < 0) { // <--- **修正點 2**
                            if (KOTLIN_DETAILED_AUDIO_LOGGING) LOG.warning("Player [Iter $S_loopIteration]: Video frame TOO LATE (Delay: ${videoDelayMicros/1000}ms). Skipping.")
                            // Consider if skipping is the best strategy, or just not sleeping
                        }
                        // else { // Only process if not too late, or always process and let sync handle it
                        val rawVideoFrame = frame.clone()
                        frameProcessingExecutor?.submit {
                            if (stopRequested) { rawVideoFrame.close(); return@submit }
                            try {
                                videoFrameOutputCallback.onVideoFrameProcessed(rawVideoFrame, currentFrameRelativeTimestampMicros)
                            } catch (e: Exception) {
                                LOG.log(Level.WARNING, "Player: Exception in video frame processing task for frame RelTS ${currentFrameRelativeTimestampMicros}us.", e)
                            } finally {
                                rawVideoFrame.close()
                            }
                        }
                        // }
                    }

                    if (hasAudio && finalSoundLineRef != null) {
                        val audioFrameToPlay = frame.clone()
                        audioPlaybackExecutor?.submit {
                            if (stopRequested) { audioFrameToPlay.close(); return@submit }
                            try {
                                if (audioDataOutputCallback != null) {
                                    audioDataOutputCallback.onAudioDataAvailable(audioFrameToPlay.samples[0] as? ShortBuffer, finalSoundLineRef, audioFrameToPlay)
                                } else {
                                    playAudioFrameInternal(audioFrameToPlay, finalSoundLineRef)
                                }
                            } catch (e: Exception) {
                                LOG.log(Level.WARNING, "Player: Exception in audio frame playback task for frame AbsTS ${audioFrameToPlay.timestamp}us.", e)
                            } finally {
                                audioFrameToPlay.close()
                            }
                        }
                    }
                    frame.close()

                    var sleepTimeMicros = (currentFrameRelativeTimestampMicros - currentPlaybackTimeMicros) - maxReadAheadBufferMicros
                    if (sleepTimeMicros.compareTo(0L) < 0) { // <--- **修正點 2**
                        sleepTimeMicros = 0L
                    }

                    val currentMainLoopSleepCapMillis = if(currentTimer.isAudioClockActive()) {
                        generalMaxSleepMillis
                    } else {
                        mainLoopDelayCapMillisUnreliableTimer
                    }

                    if ((sleepTimeMicros / 1000L).compareTo(currentMainLoopSleepCapMillis) > 0) { // <--- **修正點 2**
                        sleepTimeMicros = currentMainLoopSleepCapMillis * 1000L
                    }

                    if (sleepTimeMicros.compareTo(0L) > 0) { // <--- **修正點 2**
                        try {
                            Thread.sleep(sleepTimeMicros / 1000L, (sleepTimeMicros % 1000L).toInt() * 1000)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    } else if (sleepTimeMicros == 0L && !hasImage && !hasAudio) {
                        try { Thread.sleep(1L) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
                    }
                } // end while
            } catch (e: Exception) {
                LOG.log(Level.SEVERE, "Player: Critical error in playback thread's main run block", e)
                playerEventCallback?.onError("Critical playback error: ${e.message}", e)
            } finally {
                LOG.info("Player: Playback thread (${Thread.currentThread().name}) finishing. Initiating cleanup...")
                stopRequested = true
                cleanupPlayerResources()
                LOG.info("Player: Playback thread (${Thread.currentThread().name}) terminated.")
            }
        }.apply {
            name = "UniversalJVMPlayer-MainThread"
        }
        playThread?.start()
    }

    private fun playAudioFrameInternal(audioFrame: Frame?, line: SourceDataLine?) {
        if (stopRequested) return
        if (line == null || !line.isOpen || audioFrame?.samples == null || audioFrame.samples[0] == null) {
            if (KOTLIN_DETAILED_AUDIO_LOGGING && S_loopIteration > 0 && S_loopIteration % 50 == 1) {
                LOG.warning("[AudioInternal] Pre-condition fail: LineNull? ${line == null} LineOpen? ${line?.isOpen} SamplesNull? ${audioFrame?.samples == null || audioFrame.samples[0] == null}")
            }
            return
        }
        if (KOTLIN_DETAILED_AUDIO_LOGGING && S_loopIteration > 0 && S_loopIteration % 10 == 1) {
            LOG.info("[AudioInternal] Attempting to write audio. Line isOpen: ${line.isOpen}, isRunning: ${line.isRunning}, isActive: ${line.isActive}, BufferAvailable: ${line.available()}, TS: ${audioFrame.timestamp}")
        }

        try {
            val samplesBuffer = audioFrame.samples[0] as? ShortBuffer ?: return
            val numSamples = samplesBuffer.remaining()
            if (numSamples == 0) return

            val outBytes = ByteArray(numSamples * 2)
            val byteBuffer = ByteBuffer.wrap(outBytes)

            for (i in 0 until numSamples) {
                byteBuffer.putShort(samplesBuffer.get(samplesBuffer.position() + i))
            }

            val written = line.write(outBytes, 0, outBytes.size)

            if (KOTLIN_DETAILED_AUDIO_LOGGING && S_loopIteration > 0 && (S_loopIteration % 10 == 1 || written < outBytes.size)) {
                LOG.info("[AudioInternal] Wrote $written/${outBytes.size} bytes. LineAvailable: ${line.available()}, Level: ${line.level}")
                if (written < outBytes.size) LOG.warning("[AudioInternal] PARTIAL WRITE!")
            }
        } catch (e: Exception) {
            if (KOTLIN_DETAILED_AUDIO_LOGGING && S_loopIteration > 0 && S_loopIteration % 50 == 1) {
                LOG.log(Level.WARNING, "[AudioInternal] Error writing audio samples.", e)
            }
        }
    }

    fun stop() {
        LOG.info("Player: stop() method called.")
        stopRequested = true
        playThread?.interrupt()
        try {
            playThread?.join(generalMaxSleepMillis + 500)
            if (playThread?.isAlive == true) {
                LOG.warning("Player: Playback thread did not terminate in time.")
            } else {
                LOG.info("Player: Playback thread joined successfully.")
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            LOG.log(Level.WARNING, "Player: stop() interrupted.", e)
        }
        shutdownExecutor(frameProcessingExecutor, "VideoExecutor (from stop)")
        shutdownExecutor(audioPlaybackExecutor, "AudioExecutor (from stop)")
        playThread = null
        LOG.info("Player: stop() method finished.")
    }

    private fun cleanupPlayerResources() {
        LOG.info("Player: Performing resource cleanup...")
        grabber?.let { g ->
            try {
                if (KOTLIN_DETAILED_AUDIO_LOGGING) LOG.info("Player: Attempting to stop grabber...")
                g.stop()
                if (KOTLIN_DETAILED_AUDIO_LOGGING) LOG.info("Player: Attempting to release grabber...")
                g.release()
                LOG.info("Player: Grabber stopped and released successfully.")
            } catch (e: FrameGrabber.Exception) {
                LOG.log(Level.WARNING, "Player: Exception during grabber stop/release.", e)
            }
        }
        grabber = null

        localSoundLine?.let { line ->
            if (line.isOpen) {
                if (KOTLIN_DETAILED_AUDIO_LOGGING) LOG.info("Player: Draining, stopping, and closing audio line...")
                line.drain()
                line.stop()
                line.close()
                LOG.info("Player: Audio line processed and closed successfully.")
            }
        }
        localSoundLine = null

        shutdownExecutor(frameProcessingExecutor, "VideoFrameProcessingExecutor (cleanup)")
        frameProcessingExecutor = null
        shutdownExecutor(audioPlaybackExecutor, "AudioPlaybackExecutor (cleanup)")
        audioPlaybackExecutor = null
        playbackTimer = null
        LOG.info("Player: Resource cleanup finished.")
    }

    private fun shutdownExecutor(executor: ExecutorService?, name: String) {
        executor?.let { exec ->
            if (!exec.isShutdown) {
                LOG.info("Player: Shutting down executor: $name")
                exec.shutdown()
                try {
                    if (!exec.awaitTermination(1500, TimeUnit.MILLISECONDS)) {
                        LOG.warning("Player: Executor $name did not terminate gracefully. Forcing shutdownNow().")
                        val droppedTasks = exec.shutdownNow()
                        LOG.warning("Player: Executor $name dropped ${droppedTasks?.size ?: "N/A"} tasks.")
                        if (!exec.awaitTermination(1000, TimeUnit.MILLISECONDS)) {
                            LOG.severe("Player: Executor $name did not terminate after force.")
                        } else {
                            LOG.info("Player: Executor $name terminated after force.")
                        }
                    } else {
                        LOG.info("Player: Executor $name shut down gracefully.")
                    }
                } catch (ie: InterruptedException) {
                    LOG.warning("Player: Interrupted during $name shutdown. Forcing.")
                    exec.shutdownNow()
                    Thread.currentThread().interrupt()
                }
            }
        }
    }
}