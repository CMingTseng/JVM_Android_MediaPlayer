package org.bytedeco.javacv;

import javax.sound.sampled.*;

import java.nio.ShortBuffer;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class JavaFxSwingFFmpegPlayer {

    private static final Logger LOG = Logger.getLogger(JavaFxSwingFFmpegPlayer.class.getName());
    static volatile long S_loopIteration = 0;
    private static final boolean DETAILED_AUDIO_LOGGING = true; // Switch for very detailed audio path logs

    // --- PlaybackTimer based on BytedecoFFmpegPlayer's logic [5] ---
    private static class PlaybackTimer {
        private long timerSystemStartTimeNanos = -1L; // Fallback timer start
        private long timerFirstFrameAbsoluteTimestampMicros = -1L; // Anchor timestamp from media
        private final SourceDataLine soundLine;
        private boolean timerHasStarted = false;
        private boolean soundLineClockSuccessfullyUsed = false; // Tracks if soundLine.getMicrosecondPosition() was ever good
        private boolean soundLineEverRan = false; // Tracks if soundLine.isRunning() was ever true

        public PlaybackTimer(SourceDataLine soundLine) {
            this.soundLine = soundLine;
            LOG.info("PlaybackTimer ("+Integer.toHexString(hashCode())+"): Initialized. SoundLine " + (soundLine != null ? "provided." : "is NULL."));
        }
        public PlaybackTimer() { // Constructor for no audio
            this.soundLine = null;
            LOG.info("PlaybackTimer ("+Integer.toHexString(hashCode())+"): Initialized (NO SoundLine).");
        }

        public void start(long firstValidFrameTimestampMicros) {
            if (timerHasStarted) {
                // Allow restart only if the new timestamp is more "valid" (e.g., non-zero vs zero)
                if (firstValidFrameTimestampMicros != 0 && this.timerFirstFrameAbsoluteTimestampMicros == 0) {
                    LOG.info("PlaybackTimer ("+Integer.toHexString(hashCode())+"): RE-STARTING with non-zero TS: " + firstValidFrameTimestampMicros + " (was " + this.timerFirstFrameAbsoluteTimestampMicros + ")");
                } else {
                    LOG.info("PlaybackTimer ("+Integer.toHexString(hashCode())+"): start() called but already started with TS " + this.timerFirstFrameAbsoluteTimestampMicros + ". New TS " + firstValidFrameTimestampMicros + " ignored.");
                    return;
                }
            }
            this.timerFirstFrameAbsoluteTimestampMicros = firstValidFrameTimestampMicros;
            this.timerSystemStartTimeNanos = System.nanoTime(); // Always record system time as a base/fallback
            this.timerHasStarted = true;
            this.soundLineClockSuccessfullyUsed = false;
            this.soundLineEverRan = false;
            LOG.info("PlaybackTimer ("+Integer.toHexString(hashCode())+"): STARTED. FirstFrameAbsTS: " + firstValidFrameTimestampMicros + "us. SystemTimeAnchor: " + timerSystemStartTimeNanos + "ns.");
        }

        public boolean isAudioClockReliableAndActive() {
            if (!timerHasStarted || soundLine == null || !soundLine.isOpen() || !soundLine.isRunning()) {
                if (soundLineClockSuccessfullyUsed && DETAILED_AUDIO_LOGGING && S_loopIteration % 50 == 1) { // Log if it was good but now isn't
                    LOG.warning("PlaybackTimer ("+Integer.toHexString(hashCode())+"): Audio clock WAS reliable, but now soundLine is not running/open. isOpen: " + (soundLine !=null && soundLine.isOpen()) + ", isRunning: " + (soundLine !=null && soundLine.isRunning()));
                }
                soundLineClockSuccessfullyUsed = false; // Mark as not currently usable
                return false;
            }
            // If we reach here, soundLine is open and running
            if (!soundLineEverRan) {
                soundLineEverRan = true; // Mark that it has run at least once
                LOG.info("PlaybackTimer ("+Integer.toHexString(hashCode())+"): SoundLine is NOW RUNNING for the first time.");
            }
            soundLineClockSuccessfullyUsed = true; // Mark as currently good
            return true;
        }

        public long getCurrentRelativePlaybackTimeMicros() {
            if (!timerHasStarted) return 0L;

            if (isAudioClockReliableAndActive()) {
                long audioPos = soundLine.getMicrosecondPosition();
                if (DETAILED_AUDIO_LOGGING && S_loopIteration % 20 == 1) LOG.info("PlaybackTimer ("+Integer.toHexString(hashCode())+"): Using RELIABLE audio clock. Position: " + audioPos + "us");
                return audioPos;
            } else {
                long systemDurationMicros = (System.nanoTime() - timerSystemStartTimeNanos) / 1000L;
                if (DETAILED_AUDIO_LOGGING && S_loopIteration % 20 == 1) {
                    String reason = soundLine == null ? "NoSoundLine" : (!soundLine.isOpen() ? "NotOpen" : (!soundLine.isRunning() ? "NotRunning" : "Unknown"));
                    LOG.info("PlaybackTimer ("+Integer.toHexString(hashCode())+"): Using UNRELIABLE (SystemNanoTime) clock. Reason: " + reason + ". Elapsed: " + systemDurationMicros + "us.");
                }
                return systemDurationMicros;
            }
        }
        public long getFirstFrameAbsoluteTimestampMicros() { return timerFirstFrameAbsoluteTimestampMicros; }
        public boolean hasTimerStarted() { return timerHasStarted; }
    }
    // --- End PlaybackTimer ---

    @FunctionalInterface
    public interface VideoFrameOutputCallback {
        void onVideoFrameProcessed(Frame videoFrame, long relativeTimestampMicros);
    }
    @FunctionalInterface
    public interface AudioDataOutputCallback {
        void onAudioDataAvailable(ShortBuffer samples, SourceDataLine lineToWriteTo, Frame originalFrame);
    }
    public interface PlayerEventCallback {
        void onVideoDimensionsDetected(int width, int height, int pixelFormat);
        void onPlaybackStarted();
        void onEndOfMedia();
        void onError(String errorMessage, Exception e);
    }

    private FFmpegFrameGrabber grabber;
    private SourceDataLine localSoundLine;
    private ExecutorService frameProcessingExecutor;
    private ExecutorService audioPlaybackExecutor;
    private PlaybackTimer playbackTimer;
    private volatile Thread playThread;
    private volatile boolean stopRequested = false;
    private int grabAttemptCounter = 0;

    private final VideoFrameOutputCallback videoFrameOutputCallback;
    private final AudioDataOutputCallback audioDataOutputCallback;
    private final PlayerEventCallback playerEventCallback;

    private final long maxReadAheadBufferMicros = 700 * 1000L; // Default
    private final long videoDelayCapMillisUnreliableTimer = 1000L; // Default
    private final long videoMaxSleepReliableMs = 1000L; // Default
    private final long mainLoopDelayCapMillisUnreliableTimer = 250L; // Default
    private final long generalMaxSleepMillis = 2000L; // Default
    private final long minMeaningfulVideoDelayMs = 8L; // Default

    public static class Builder { /* ... Same as your previous working builder ... */
        private VideoFrameOutputCallback videoFrameOutputCallback;
        private AudioDataOutputCallback audioDataOutputCallback;
        private PlayerEventCallback playerEventCallback;
        public Builder(VideoFrameOutputCallback videoCallback, PlayerEventCallback eventCallback) {
            this.videoFrameOutputCallback = videoCallback;
            this.playerEventCallback = eventCallback;
        }
        public Builder audioDataOutputCallback(AudioDataOutputCallback callback) { this.audioDataOutputCallback = callback; return this; }
        public JavaFxSwingFFmpegPlayer build() {
            if (videoFrameOutputCallback == null) throw new IllegalStateException("VideoFrameOutputCallback cannot be null.");
            return new JavaFxSwingFFmpegPlayer(this);
        }
    }

    private JavaFxSwingFFmpegPlayer(Builder builder) { /* ... Same as your previous working constructor ... */
        this.videoFrameOutputCallback = builder.videoFrameOutputCallback;
        this.audioDataOutputCallback = builder.audioDataOutputCallback;
        this.playerEventCallback = builder.playerEventCallback;
    }

    public void start(final String mediaPath) {
        if (playThread != null && playThread.isAlive()) {
            LOG.warning("Player: start() called, but already running.");
            return;
        }
        stopRequested = false;

        ThreadFactory videoFrameProcessorFactory = r -> new Thread(r, "Player-VideoProcessor");
        ThreadFactory audioProcessorFactory = r -> new Thread(r, "Player-AudioProcessor");

        playThread = new Thread(() -> {
            S_loopIteration = 0;
            this.grabAttemptCounter = 0;

            shutdownExecutor(frameProcessingExecutor, "Previous VideoExecutor");
            frameProcessingExecutor = Executors.newSingleThreadExecutor(videoFrameProcessorFactory);

            shutdownExecutor(audioPlaybackExecutor, "Previous AudioExecutor");
            audioPlaybackExecutor = Executors.newSingleThreadExecutor(audioProcessorFactory);

            this.grabber = null; this.localSoundLine = null; this.playbackTimer = null;

            LOG.info("Player-Thread ("+Thread.currentThread().getName()+"): Starting playback for: " + mediaPath);
            try {
                grabber = new FFmpegFrameGrabber(mediaPath);
                // grabber.setOption("pixel_format", "bgr24"); // Still likely rejected here
                grabber.start();

                final int actualPixelFormat = grabber.getPixelFormat();
                final int frameWidth = grabber.getImageWidth();
                final int frameHeight = grabber.getImageHeight();
                LOG.info(String.format("Player: Grabber started. PixelFormat:%d, Size:%dx%d, FPS:%.2f, VideoCodecID:%d, AudioChannels:%d, SampleRate:%d, AudioCodecID:%d, Format:%s",
                        actualPixelFormat, frameWidth, frameHeight, grabber.getFrameRate(),
                        grabber.getVideoCodec(), grabber.getAudioChannels(), grabber.getSampleRate(),
                        grabber.getAudioCodec(), grabber.getFormat()));

                if (playerEventCallback != null) playerEventCallback.onVideoDimensionsDetected(frameWidth, frameHeight, actualPixelFormat);
                if (frameWidth <= 0 || frameHeight <= 0) { LOG.severe("Player: Invalid video dimensions."); cleanupPlayerResources(); return; }

                if (grabber.getAudioChannels() > 0 && grabber.getSampleRate() > 0) {
                    AudioFormat audioFormat = new AudioFormat((float) grabber.getSampleRate(), 16, grabber.getAudioChannels(), true, false); // S16LE
                    if (!AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, audioFormat))) {
                        audioFormat = new AudioFormat((float) grabber.getSampleRate(), 16, grabber.getAudioChannels(), true, true); // S16BE
                    }
                    if (AudioSystem.isLineSupported(new DataLine.Info(SourceDataLine.class, audioFormat))) {
                        localSoundLine = (SourceDataLine) AudioSystem.getLine(new DataLine.Info(SourceDataLine.class, audioFormat));
                        int bufferSize = Math.max(16384, (int) (audioFormat.getFrameSize() * audioFormat.getFrameRate() * 0.5f)); // 0.5 sec buffer
                        localSoundLine.open(audioFormat, bufferSize);
                        LOG.info("Player: Audio line opened. Format: " + audioFormat + " Buffer: " + localSoundLine.getBufferSize() + " requested: " + bufferSize);
                        localSoundLine.start(); // IMPORTANT: Start the line!
                        LOG.info("Player: Audio line started. isOpen: " + localSoundLine.isOpen() + ", isRunning: " + localSoundLine.isRunning() + ", isActive: " + localSoundLine.isActive());
                        playbackTimer = new PlaybackTimer(localSoundLine);
                    } else {
                        LOG.severe("Player: No supported audio line. No audio.");
                        playbackTimer = new PlaybackTimer(); // No-audio timer
                    }
                } else {
                    LOG.info("Player: No audio streams or zero sample rate. Using system timer.");
                    playbackTimer = new PlaybackTimer(); // No-audio timer
                }
                final SourceDataLine finalSoundLine = localSoundLine;
                final PlaybackTimer finalTimer = playbackTimer; // Essential for lambdas
                long firstValidTimestampFound = -1L; // To track the first non-zero timestamp for timer


                // --- Audio Warm-up ---
                if (finalSoundLine != null) {
                    LOG.info("Player: --- Starting Audio Warm-up ---");
                    int warmupAudioFramesSubmitted = 0;
                    for (int i = 0; i < 30 && !stopRequested; i++) { // Try to process a few audio frames
                        Frame warmFrame = null;
                        try { warmFrame = grabber.grabFrame(true, true, false, false); } // Grab audio primarily
                        catch (FrameGrabber.Exception ge) { LOG.warning("Player [Warmup]: Grabber exception: " + ge.getMessage()); break; }

                        if (warmFrame == null) { LOG.info("Player [Warmup]: End of stream."); break; }

                        // Initialize timer with the first valid (non-zero if possible) timestamp
                        if (!finalTimer.hasTimerStarted() && warmFrame.timestamp > 0) {
                            finalTimer.start(warmFrame.timestamp);
                            firstValidTimestampFound = warmFrame.timestamp;
                            LOG.info("Player [Warmup]: Timer started with first valid audio TS: " + firstValidTimestampFound);
                        } else if (!finalTimer.hasTimerStarted() && warmFrame.timestamp == 0 && i == 0) { // First frame TS is 0
                            finalTimer.start(0); // Start with 0, will update later if non-zero found
                            firstValidTimestampFound = 0;
                            LOG.info("Player [Warmup]: Timer started with initial audio TS 0.");
                        }


                        if (warmFrame.samples != null && warmFrame.samples[0] != null) {
                            if (DETAILED_AUDIO_LOGGING) LOG.info("Player [Warmup]: Got audio samples. Submitting to playback. TS: " + warmFrame.timestamp);
                            warmupAudioFramesSubmitted++;
                            final Frame audioClone = warmFrame.clone();
                            audioPlaybackExecutor.submit(() -> {
                                if(stopRequested) { audioClone.close(); return; }
                                playAudioFrameInternal(audioClone, finalSoundLine); // Directly call, not through callback here
                                audioClone.close();
                            });
                        }
                        warmFrame.close(); // Close the frame from grabber

                        // Give some time for audio to actually play and line to become active
                        try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }

                        if (finalTimer.isAudioClockReliableAndActive()) { // Check if clock is good
                            LOG.info("Player [Warmup]: Audio clock became reliable and active. Exiting warmup.");
                            break;
                        }
                    }
                    LOG.info("Player: --- Audio Warm-up Finished. Audio frames submitted: " + warmupAudioFramesSubmitted + ". Timer reliable: " + finalTimer.isAudioClockReliableAndActive() + " ---");
                }
                // --- End Audio Warm-up ---


                if (playerEventCallback != null && !stopRequested) playerEventCallback.onPlaybackStarted();
                LOG.info("Player: Starting main frame processing loop.");

                while (!Thread.interrupted() && !stopRequested) {
                    S_loopIteration++;
                    Frame frame;
                    try {
                        frame = grabber.grab();
                    } catch (FrameGrabber.Exception e) { LOG.log(Level.WARNING, "Player: Error grabbing frame.", e); if(playerEventCallback!=null) playerEventCallback.onError("Grab error",e); break; }
                    if (frame == null) { LOG.info("Player: End of stream."); if(playerEventCallback!=null) playerEventCallback.onEndOfMedia(); break; }

                    // Ensure timer is started with the first valid non-zero timestamp from any frame
                    if (!finalTimer.hasTimerStarted() && frame.timestamp > 0) {
                        finalTimer.start(frame.timestamp);
                        firstValidTimestampFound = frame.timestamp;
                        LOG.info("Player [MainLoop]: Timer started with first valid TS from main loop: " + firstValidTimestampFound);
                    } else if (finalTimer.hasTimerStarted() && finalTimer.getFirstFrameAbsoluteTimestampMicros() == 0 && frame.timestamp > 0) {
                        LOG.info("Player [MainLoop]: Timer was started with TS 0. Updating with first non-zero TS: " + frame.timestamp);
                        finalTimer.start(frame.timestamp); // Re-start/update with a non-zero timestamp
                        firstValidTimestampFound = frame.timestamp;
                    }

                    if (!finalTimer.hasTimerStarted()) { // Still no valid timestamp to start timer
                        LOG.warning("Player [MainLoop]: Timer not started (no valid TS yet), skipping frame. TS: " + frame.timestamp);
                        frame.close();
                        Thread.sleep(10); continue;
                    }

                    final long currentFrameAbsoluteTs = frame.timestamp;
                    if (currentFrameAbsoluteTs < firstValidTimestampFound && firstValidTimestampFound != 0) { // Basic check for out-of-order frames before timer anchor
                        LOG.warning("Player [MainLoop]: Frame TS " + currentFrameAbsoluteTs + " < FirstValidTS " + firstValidTimestampFound + ". Skipping.");
                        frame.close(); continue;
                    }

                    final long currentFrameRelativeTs = currentFrameAbsoluteTs - finalTimer.getFirstFrameAbsoluteTimestampMicros();
                    final long currentPlaybackTimeMicros = finalTimer.getCurrentRelativePlaybackTimeMicros();

                    boolean hasImage = (frame.image != null && frame.imageHeight > 0 && frame.imageWidth > 0);
                    boolean hasAudio = (frame.samples != null && frame.samples[0] != null);

                    if (S_loopIteration % 50 == 1) {
                        LOG.info(String.format("Player [MainLoop %d]: RelTS:%,dus, Playback:%,dus, Reliable:%b, Img:%b, Aud:%b",
                                S_loopIteration, currentFrameRelativeTs, currentPlaybackTimeMicros,
                                finalTimer.isAudioClockReliableAndActive(), hasImage, hasAudio));
                    }

                    if (hasImage) {
                        final Frame rawVideoFrame = frame.clone();
                        frameProcessingExecutor.submit(() -> {
                            if(stopRequested) { rawVideoFrame.close(); return; }
                            try {
                                long playbackTimeAtRenderDecision = finalTimer.getCurrentRelativePlaybackTimeMicros();
                                long videoDelayMicros = currentFrameRelativeTs - playbackTimeAtRenderDecision;
                                long sleepMillis = 0;
                                if (videoDelayMicros > (minMeaningfulVideoDelayMs * 1000L)) {
                                    sleepMillis = videoDelayMicros / 1000L;
                                    long capToUse = finalTimer.isAudioClockReliableAndActive() ? videoMaxSleepReliableMs : videoDelayCapMillisUnreliableTimer;
                                    sleepMillis = Math.min(sleepMillis, capToUse);
                                }
                                if (sleepMillis >= minMeaningfulVideoDelayMs) Thread.sleep(sleepMillis);

                                if (videoFrameOutputCallback != null && !stopRequested) videoFrameOutputCallback.onVideoFrameProcessed(rawVideoFrame, currentFrameRelativeTs);
                            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                            catch (Exception e) { LOG.log(Level.WARNING, "Player: Error in video processing task.", e); }
                            finally { rawVideoFrame.close(); }
                        });
                    }
                    if (hasAudio && finalSoundLine != null) {
                        if (DETAILED_AUDIO_LOGGING && S_loopIteration % 10 == 1) LOG.info("Player [MainLoop]: Got audio samples. Submitting to playback. TS: " + currentFrameAbsoluteTs);
                        final Frame audioClone = frame.clone();
                        audioPlaybackExecutor.submit(() -> {
                            if(stopRequested) { audioClone.close(); return; }
                            playAudioFrameInternal(audioClone, finalSoundLine); // Use direct call or callback
                            audioClone.close();
                        });
                    }
                    frame.close(); // Close original frame

                    // Main loop sleep for backpressure
                    long mainLoopSleepMillis = 0;
                    long frameReadAheadMicros = currentFrameRelativeTs - currentPlaybackTimeMicros;
                    if (frameReadAheadMicros > maxReadAheadBufferMicros) {
                        mainLoopSleepMillis = (frameReadAheadMicros - maxReadAheadBufferMicros) / 1000L;
                        long capToUse = finalTimer.isAudioClockReliableAndActive() ? generalMaxSleepMillis : mainLoopDelayCapMillisUnreliableTimer;
                        mainLoopSleepMillis = Math.min(mainLoopSleepMillis, capToUse);
                    }
                    if (mainLoopSleepMillis >= minMeaningfulVideoDelayMs) Thread.sleep(mainLoopSleepMillis);
                    else if (!hasImage && !hasAudio) Thread.sleep(5); // Small yield if no AV data
                }
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Player: Error in playback thread's main run block", e);
                if (playerEventCallback != null) playerEventCallback.onError("Critical playback error", e);
            } finally {
                LOG.info("Player: Playback thread ("+Thread.currentThread().getName()+") finishing. Cleanup...");
                stopRequested = true; // Ensure other threads/tasks know to stop
                cleanupPlayerResources();
                LOG.info("Player: Playback thread ("+Thread.currentThread().getName()+") terminated.");
            }
        });
        playThread.setName("GenericFFmpegPlayer-MainThread");
        playThread.start();
    }

    private void playAudioFrameInternal(Frame audioFrame, SourceDataLine line) {
        if (stopRequested) return;
        if (line == null || !line.isOpen() /*|| !line.isRunning() LET'S TRY WITHOUT THIS CHECK FOR NOW */ || audioFrame == null || audioFrame.samples == null || audioFrame.samples[0] == null) {
            if (DETAILED_AUDIO_LOGGING && S_loopIteration % 50 == 1) LOG.warning("[AudioInternal] Pre-condition fail: LineNull? "+(line==null)+" LineOpen? "+(line !=null && line.isOpen())+" LineRunning? "+(line !=null && line.isRunning())+" SamplesNull? "+(audioFrame==null || audioFrame.samples==null || audioFrame.samples[0]==null));
            return;
        }
        if (DETAILED_AUDIO_LOGGING && S_loopIteration % 10 == 1) LOG.info("[AudioInternal] Attempting to write audio. Line isOpen: " + line.isOpen() + ", isRunning: " + line.isRunning() + ", isActive: " + line.isActive() + ", BufferAvailable: " + line.available());

        try {
            ShortBuffer samplesBuffer = (ShortBuffer) audioFrame.samples[0];
            int numSamples = samplesBuffer.remaining();
            if (numSamples == 0) return;

            byte[] out = new byte[numSamples * 2]; // S16LE
            for (int i = 0; i < numSamples; i++) {
                short val = samplesBuffer.get(samplesBuffer.position() + i); // Absolute get
                out[i * 2] = (byte) (val & 0xFF);
                out[i * 2 + 1] = (byte) ((val >> 8) & 0xFF);
            }
            int written = line.write(out, 0, out.length);
            if (DETAILED_AUDIO_LOGGING && (S_loopIteration % 10 == 1 || written < out.length) ) {
                LOG.info("[AudioInternal] Wrote " + written + "/" + out.length + " bytes to audio line. LineAvailable after write: " + line.available() + ", Level: " + line.getLevel());
                if (written < out.length) LOG.warning("[AudioInternal] PARTIAL WRITE to audio line!");
            }
        } catch (Exception e) {
            if (DETAILED_AUDIO_LOGGING && S_loopIteration % 50 == 1) LOG.log(Level.WARNING, "[AudioInternal] Error writing audio samples to line.", e);
        }
    }

    public void stop() { /* ... Same as previous correct version ... */
        LOG.info("Player: stop() method called.");
        stopRequested = true;
        if (playThread != null && playThread.isAlive()) {
            LOG.info("Player: Interrupting playback thread: " + playThread.getName());
            playThread.interrupt();
            try {
                playThread.join(generalMaxSleepMillis + 1000);
                if (playThread.isAlive()) {
                    LOG.warning("Player: Playback thread did not terminate in time after interrupt and join.");
                } else {
                    LOG.info("Player: Playback thread joined.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.log(Level.WARNING, "Player: stop() interrupted while waiting for playThread.", e);
            }
        }
        shutdownExecutor(frameProcessingExecutor, "VideoExecutor (from stop)");
        shutdownExecutor(audioPlaybackExecutor, "AudioExecutor (from stop)");
        playThread = null;
        LOG.info("Player: stop() method finished.");
    }

    private void cleanupPlayerResources() { /* ... Same as previous correct version ... */
        LOG.info("Player: Performing resource cleanup...");
        if (grabber != null) {
            try {
                grabber.stop(); grabber.release();
                LOG.info("Player: Grabber stopped/released.");
            } catch (FrameGrabber.Exception e) { LOG.log(Level.WARNING, "Player: Error stopping/releasing grabber.", e); }
            grabber = null;
        }
        if (localSoundLine != null) {
            if (localSoundLine.isOpen()) {
                localSoundLine.drain(); localSoundLine.stop(); localSoundLine.close();
                LOG.info("Player: Audio line closed.");
            }
            localSoundLine = null;
        }
        shutdownExecutor(frameProcessingExecutor, "VideoFrameProcessingExecutor (cleanup)");
        frameProcessingExecutor = null;
        shutdownExecutor(audioPlaybackExecutor, "AudioPlaybackExecutor (cleanup)");
        audioPlaybackExecutor = null;
        playbackTimer = null;
        LOG.info("Player: Resource cleanup finished.");
    }

    private void shutdownExecutor(ExecutorService executor, String name) { /* ... Same as previous correct version ... */
        if (executor != null && !executor.isShutdown()) {
            LOG.info("Player: Shutting down executor: " + name);
            executor.shutdown();
            try {
                if (!executor.awaitTermination(1, TimeUnit.SECONDS)) { // Shorter wait for quicker feedback
                    List<Runnable> dropped = executor.shutdownNow();
                    LOG.warning("Player: Executor " + name + " forced shutdown. Dropped tasks: " + (dropped != null ? dropped.size() : "N/A"));
                    if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
                        LOG.severe("Player: Executor " + name + " did not terminate after force.");
                    }
                } else {
                    LOG.info("Player: Executor " + name + " shut down.");
                }
            } catch (InterruptedException ie) {
                executor.shutdownNow(); Thread.currentThread().interrupt();
            }
        }
    }
}
