package org.bytedeco.javacv;

import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.stage.Stage; // Added for resizing
import javax.sound.sampled.*;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
public class BytedecoFFmpegPlayer {

    private static final Logger LOG = Logger.getLogger(BytedecoFFmpegPlayer.class.getName());
    // Static for PlaybackTimer's optional logging, reset on each player start
    static volatile int loopIteration = 0;


    // --- PlaybackTimer Inner Static Class (Copied and adapted from JavaFxPlayVideoAndAudio) ---
    private static class PlaybackTimer {
        private long timerStartTimeNanos = -1L;
        private long firstFrameTimestampMicros = -1L;
        private final SourceDataLine soundLine;
        private boolean timerStarted = false;
        private boolean soundLineClockSuccessfullyUsed = false; // Tracks if sound line was ever used as the clock source
        private boolean soundLineEverRan = false; // To track if soundLine has run at least once

        public PlaybackTimer(SourceDataLine soundLine) {
            this.soundLine = soundLine;
        }

        public PlaybackTimer() {
            this.soundLine = null;
        }

        public void start(long firstValidFrameTimestampMicros) {
            if (timerStarted) return;
            this.firstFrameTimestampMicros = firstValidFrameTimestampMicros;
            this.timerStartTimeNanos = System.nanoTime();
            this.timerStarted = true;
            this.soundLineClockSuccessfullyUsed = false; // Reset on start
            this.soundLineEverRan = false; // Reset on start

            if (soundLine != null) {
                System.out.println("PlaybackTimer: Started. First frame TS (abs): " + firstValidFrameTimestampMicros +
                        "us. Will attempt to use soundLine. System time recorded.");
            } else {
                System.out.println("PlaybackTimer: Started. First frame TS (abs): " + firstValidFrameTimestampMicros +
                        "us. Using System.nanoTime() (no soundLine).");
            }
        }

        public long elapsedMicros() {
            if (!timerStarted) return 0;

            if (soundLine != null && soundLine.isOpen() && soundLine.isRunning()) {
                if (!soundLineEverRan) {
                    soundLineEverRan = true; // Mark that it has run
                    System.out.println("PlaybackTimer: SoundLine is NOW RUNNING. Will use its position.");
                }
                soundLineClockSuccessfullyUsed = true; // If it's running, we consider it successfully used for this call
                return soundLine.getMicrosecondPosition(); // This is time since soundLine.start()
            } else {
                // Fallback to system time if soundLine is not available or not active/running
                if (soundLineClockSuccessfullyUsed) { // It was running before but stopped
                    System.err.println("PlaybackTimer: SoundLine was used but is NOT RUNNING NOW. Reverting to System.nanoTime() based progress.");
                    // Optional: could try to adjust system time start to match last known audio position, but complex.
                }
                soundLineClockSuccessfullyUsed = false; // Mark as not currently using sound clock

                long systemDurationMicros = (System.nanoTime() - timerStartTimeNanos) / 1000;

                // Using BytedecoFFmpegPlayer.loopIteration for conditional logging
                if (soundLine != null && (BytedecoFFmpegPlayer.loopIteration < 20 || BytedecoFFmpegPlayer.loopIteration % 100 == 1)) {
                    System.err.println("PlaybackTimer: Using System.nanoTime() for elapsed. SystemDuration: " + systemDurationMicros +
                            "us. SoundLine state: isOpen=" + soundLine.isOpen() +
                            ", isRunning=" + soundLine.isRunning() +
                            ", EverRan=" + soundLineEverRan);
                }
                return systemDurationMicros;
            }
        }

        public boolean isAudioClockActive() {
            if (!timerStarted || soundLine == null) return false;
            // Additional check: ensure some data has been written or it's genuinely progressing
            // This can be tricky; soundLine.isRunning() should be the main indicator after initial buffer fill.
            return soundLine.isOpen() && soundLine.isRunning();
        }

        public long getFirstFrameTimestampMicros() {
            return firstFrameTimestampMicros;
        }

        public boolean hasTimerStarted() {
            return timerStarted;
        }
    }
    // --- End PlaybackTimer ---


    @FunctionalInterface
    public interface VideoFrameCallback {
        void onFrame(Image image, long relativeTimestampMicros);
    }

    // Optional: If you want to allow external audio processing
    @FunctionalInterface
    public interface AudioDataCallback {
        void onAudioData(ShortBuffer samples, SourceDataLine lineToWriteTo, Frame originalFrame);
    }

    private FFmpegFrameGrabber grabber;
    private SourceDataLine localSoundLine;
    private final JavaFXFrameConverter converter;
    private ExecutorService imageExecutor;
    private ExecutorService audioExecutor;
    private PlaybackTimer playbackTimer;
    private volatile Thread playThread;
    private int grabAttemptCounter = 0;


    private final VideoFrameCallback videoFrameCallback;
    private final AudioDataCallback audioDataCallback; // Can be null

    // Sync Parameters (from original JavaFxPlayVideoAndAudio)
    private final long maxReadAheadBufferMicros = 700 * 1000L;
    private final long videoDelayCapMillis = 1000;
    private final long mainLoopNotReliableSleepCapMillis = 200;


    public BytedecoFFmpegPlayer(VideoFrameCallback videoFrameCallback) {
        this(videoFrameCallback, null);
    }

    public BytedecoFFmpegPlayer(VideoFrameCallback videoFrameCallback, AudioDataCallback audioDataCallback) {
        this.converter = new JavaFXFrameConverter();
        this.videoFrameCallback = videoFrameCallback;
        this.audioDataCallback = audioDataCallback;
    }

    public void start(final String videoFilename, final Stage primaryStageForResize) {
        if (playThread != null && playThread.isAlive()) {
            LOG.warning("Player is already running. Call stop() first.");
            return;
        }

        playThread = new Thread(() -> {
            BytedecoFFmpegPlayer.loopIteration = 0; // Reset static loop iteration for this new playback
            this.grabAttemptCounter = 0;      // Reset instance grab attempt counter

            // Ensure fresh state for executors on each start, if not already shut down properly
            if (imageExecutor == null || imageExecutor.isShutdown()) {
                imageExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Player-ImageProcessor"));
            }
            if (audioExecutor == null || audioExecutor.isShutdown()) {
                audioExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Player-AudioProcessor"));
            }

            this.grabber = null; // Ensure fresh state
            this.localSoundLine = null;
            this.playbackTimer = null;


            try {
                System.out.println("BytedecoFFmpegPlayer: Initializing grabber for URL: " + videoFilename);
                grabber = new FFmpegFrameGrabber(videoFilename);
                // FFmpegLogCallback.set(); // Uncomment if needed and configured
                System.out.println("BytedecoFFmpegPlayer: Starting grabber...");
                grabber.start(); // This can throw FrameGrabber.Exception
                System.out.println("BytedecoFFmpegPlayer: Grabber started. " +
                        "PixelFormat:" + grabber.getPixelFormat() +
                        ", ImageW/H:" + grabber.getImageWidth() + "/" + grabber.getImageHeight() +
                        ", AudioChannels:" + grabber.getAudioChannels() +
                        ", SampleRate:" + grabber.getSampleRate() +
                        ", FrameRate:" + grabber.getFrameRate());

                final int frameWidth = grabber.getImageWidth();
                final int frameHeight = grabber.getImageHeight();
                if (frameWidth > 0 && frameHeight > 0 && primaryStageForResize != null) {
                    Platform.runLater(() -> {
                        primaryStageForResize.setWidth(frameWidth);
                        primaryStageForResize.setHeight(frameHeight + 40); // As per original
                    });
                }

                if (grabber.getAudioChannels() > 0) {
                    AudioFormat audioFormat = new AudioFormat((float) grabber.getSampleRate(), 16, grabber.getAudioChannels(), true, true);
                    DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
                    if (!AudioSystem.isLineSupported(info)) {
                        System.err.println("BytedecoFFmpegPlayer: Default audio format not supported: " + audioFormat + ". Trying alternative (signed/unsigned or endianness).");
                        // Potentially try other common formats if the first fails, e.g., signed false, or different endianness
                        // For simplicity, sticking to the original logic for now.
                        throw new LineUnavailableException("Audio format " + audioFormat + " not supported.");
                    }
                    localSoundLine = (SourceDataLine) AudioSystem.getLine(info);
                    int bytesPerFrame = audioFormat.getFrameSize();
                    if (bytesPerFrame == AudioSystem.NOT_SPECIFIED) {
                        bytesPerFrame = (audioFormat.getSampleSizeInBits() / 8) * audioFormat.getChannels();
                    }
                    float frameRate = audioFormat.getFrameRate(); // This is audio frame rate
                    int bufferTimeMillis = 750;
                    int desiredBufferSize = (int) (bytesPerFrame * frameRate * (bufferTimeMillis / 1000.0f));
                    System.out.println("BytedecoFFmpegPlayer: Desired audio buffer size: " + desiredBufferSize + " bytes for " + bufferTimeMillis + "ms.");
                    localSoundLine.open(audioFormat, desiredBufferSize);
                    localSoundLine.start(); // Start the line
                    playbackTimer = new PlaybackTimer(localSoundLine); // Pass the line to timer
                    System.out.println("BytedecoFFmpegPlayer: Audio line opened (buffer: " + localSoundLine.getBufferSize() + " bytes) and started.");
                } else {
                    playbackTimer = new PlaybackTimer(); // No audio line
                    System.out.println("BytedecoFFmpegPlayer: No audio channels. PlaybackTimer uses System.nanoTime().");
                }
                final SourceDataLine finalSoundLine = localSoundLine; // effectively final for lambda
                final PlaybackTimer finalPlaybackTimer = playbackTimer; // effectively final for lambda

                // --- AUDIO WARM-UP STAGE ---
                final int MAX_AUDIO_WARMUP_FRAMES = 30;
                // boolean audioWarmUpAttempted = false; // Not strictly needed here
                if (finalSoundLine != null) {
                    // audioWarmUpAttempted = true; // Implied by finalSoundLine != null
                    System.out.println("BytedecoFFmpegPlayer: --- Starting Audio Warm-up Stage (Max " + MAX_AUDIO_WARMUP_FRAMES + " frames) ---");
                    for (int warmupIter = 0; warmupIter < MAX_AUDIO_WARMUP_FRAMES; warmupIter++) {
                        if (Thread.interrupted()) {
                            System.out.println("BytedecoFFmpegPlayer: [Warmup] Interrupted.");
                            break;
                        }
                        Frame warmupFrame = null;
                        try {
                            warmupFrame = grabber.grab(); // Grab audio or video
                        } catch (FrameGrabber.Exception e) {
                            LOG.log(Level.WARNING, "[Warmup] Error grabbing frame", e);
                            break;
                        }

                        if (warmupFrame == null) {
                            System.out.println("BytedecoFFmpegPlayer: [Warmup] Grabber returned NULL. Ending warm-up.");
                            break;
                        }

                        BytedecoFFmpegPlayer.loopIteration++; // Increment for PlaybackTimer logging
                        this.grabAttemptCounter++;

                        if (!finalPlaybackTimer.hasTimerStarted()) {
                            System.out.println("BytedecoFFmpegPlayer: [Warmup, Iter " + BytedecoFFmpegPlayer.loopIteration + "] First frame for timer (TS_abs: " + warmupFrame.timestamp + "us). Starting PlaybackTimer.");
                            finalPlaybackTimer.start(warmupFrame.timestamp);
                        }

                        if (warmupFrame.samples != null && warmupFrame.samples[0] != null) {
                            final Frame audioFrameToWarm = warmupFrame.clone(); // Clone for async processing
                            audioExecutor.submit(() -> {
                                try {
                                    if (audioDataCallback != null) {
                                        audioDataCallback.onAudioData((ShortBuffer) audioFrameToWarm.samples[0], finalSoundLine, audioFrameToWarm);
                                    } else { // Default internal handling
                                        ShortBuffer samples = (ShortBuffer) audioFrameToWarm.samples[0];
                                        ByteBuffer outBuffer = ByteBuffer.allocate(samples.capacity() * 2);
                                        for (int i = 0; i < samples.capacity(); i++)
                                            outBuffer.putShort(samples.get(i));
                                        finalSoundLine.write(outBuffer.array(), 0, outBuffer.capacity());
                                    }
                                } catch (Exception e) {
                                    LOG.log(Level.WARNING, "[Warmup] Audio submission/processing error", e);
                                } finally {
                                    audioFrameToWarm.close(); // Release cloned frame
                                }
                            });
                        }
                        // Release original warmup frame if not used by audio (e.g., if it was video only or audio was cloned)
                        // If warmupFrame was audio and cloned, the original still needs handling.
                        // However, grabber.grab() might reuse its internal buffer, so closing the grabbed frame
                        // directly might be problematic unless cloning is always done for any processing.
                        // For safety, let's assume if it was processed (cloned), the original is implicitly handled by next grab or grabber's internal reuse.
                        // If it was not an audio frame or audio was not processed, it's just skipped.

                        try {
                            Thread.sleep(5); // Small delay to allow some audio processing
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            System.out.println("BytedecoFFmpegPlayer: [Warmup] Sleep interrupted.");
                            break;
                        }

                        if (finalPlaybackTimer.isAudioClockActive()) {
                            System.out.println("BytedecoFFmpegPlayer: [Warmup] Audio clock ACTIVE after " + (warmupIter + 1) + " frames with some data written.");
                            break;
                        }
                    }
                    if (finalPlaybackTimer.isAudioClockActive()) {
                        System.out.println("BytedecoFFmpegPlayer: --- Audio Warm-up: Audio Clock is Active. ---");
                    } else {
                        System.err.println("BytedecoFFmpegPlayer: --- Audio Warm-up: Audio Clock DID NOT become active. Potential issues with audio playback start. ---");
                    }
                }
                // --- END AUDIO WARM-UP ---

                System.out.println("BytedecoFFmpegPlayer: Starting main processing loop with RELATIVE synchronization...");

                while (!Thread.interrupted()) {
                    BytedecoFFmpegPlayer.loopIteration++;
                    this.grabAttemptCounter++;
                    Frame frame = null;
                    try {
                        frame = grabber.grab(); // Grab audio or video
                    } catch (FrameGrabber.Exception e) {
                        LOG.log(Level.WARNING, "Error grabbing frame in main loop", e);
                        break; // Exit loop on grab error
                    }

                    if (frame == null) {
                        System.err.println("BytedecoFFmpegPlayer: [Iter " + BytedecoFFmpegPlayer.loopIteration + "] Grabber returned NULL (end of stream or error). Ending loop.");
                        break;
                    }

                    if (!finalPlaybackTimer.hasTimerStarted()) {
                        // This case should ideally be hit only if no frames (even for warmup) were processed before.
                        System.out.println("BytedecoFFmpegPlayer: [MainLoop, Iter " + BytedecoFFmpegPlayer.loopIteration + "] First frame for timer (TS_abs: " + frame.timestamp + "us). Starting PlaybackTimer.");
                        finalPlaybackTimer.start(frame.timestamp);
                    }

                    final long currentFrameAbsoluteTimestampMicros = frame.timestamp;
                    final long currentFrameRelativeTimestampMicros = currentFrameAbsoluteTimestampMicros - finalPlaybackTimer.getFirstFrameTimestampMicros();
                    final long currentPlaybackTimeMicros = finalPlaybackTimer.elapsedMicros();

                    boolean hasImage = (frame.image != null && frame.image[0] != null);
                    boolean hasAudio = (frame.samples != null && frame.samples[0] != null);

                    if (BytedecoFFmpegPlayer.loopIteration <= 10 || BytedecoFFmpegPlayer.loopIteration % 50 == 1) {
                        System.out.println(String.format(
                                "BytedecoFFmpegPlayer: [Iter %d] FrameTS_abs:%d, FrameTS_rel:%d, PlaybackTime:%d, AudioClockActive:%b, Img:%b, Aud:%b",
                                BytedecoFFmpegPlayer.loopIteration, currentFrameAbsoluteTimestampMicros, currentFrameRelativeTimestampMicros, currentPlaybackTimeMicros,
                                finalPlaybackTimer.isAudioClockActive(), hasImage, hasAudio
                        ));
                    }

                    // --- IMAGE PROCESSING ---
                    if (hasImage) {
                        final Frame imageFrameToConvert = frame.clone(); // Clone for async processing
                        // final long imageFrameRelativeTs = currentFrameRelativeTimestampMicros; // Already available
                        imageExecutor.submit(() -> {
                            try {
                                long playbackTimeAtRenderDecision = finalPlaybackTimer.elapsedMicros(); // Get current time again
                                long delayNeededMicros = currentFrameRelativeTimestampMicros - playbackTimeAtRenderDecision;
                                long sleepMillis = 0;

                                if (delayNeededMicros > 1000) { // Only sleep if delay is > 1ms
                                    sleepMillis = delayNeededMicros / 1000;

                                    if (!finalPlaybackTimer.isAudioClockActive() && sleepMillis > videoDelayCapMillis) {
                                        System.err.println("[ImageExec] Audio clock not active. Video sleep " + sleepMillis + "ms for RelTS:" + currentFrameRelativeTimestampMicros + " too long. Capping to " + videoDelayCapMillis + "ms.");
                                        sleepMillis = videoDelayCapMillis;
                                    }
                                    // Cap for early playback with active audio clock (original logic)
                                    else if (finalPlaybackTimer.isAudioClockActive() && playbackTimeAtRenderDecision < 1500000 && sleepMillis > 300) {
                                        System.err.println("[ImageExec] Audio clock active but elapsed small (" + playbackTimeAtRenderDecision + "us). Video sleep " + sleepMillis + "ms for RelTS:" + currentFrameRelativeTimestampMicros + ". Capping to 300ms.");
                                        sleepMillis = 300;
                                    }
                                }

                                if (sleepMillis > 0) { // Perform sleep if needed
                                    Thread.sleep(sleepMillis);
                                }

                                final Image fxImage = converter.convert(imageFrameToConvert);
                                if (videoFrameCallback != null && fxImage != null) {
                                    // Callback is responsible for Platform.runLater
                                    videoFrameCallback.onFrame(fxImage, currentFrameRelativeTimestampMicros);
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                System.out.println("[ImageExec] Interrupted during sleep/processing.");
                            } catch (Exception e) {
                                LOG.log(Level.WARNING, "Error in image processing task", e);
                            } finally {
                                imageFrameToConvert.close(); // Release cloned frame resources
                            }
                        });
                    }

                    // --- AUDIO PROCESSING ---
                    if (hasAudio && finalSoundLine != null) {
                        final Frame audioFrameToProcess = frame.clone(); // Clone for async processing
                        audioExecutor.submit(() -> {
                            try {
                                if (audioDataCallback != null) {
                                    audioDataCallback.onAudioData((ShortBuffer) audioFrameToProcess.samples[0], finalSoundLine, audioFrameToProcess);
                                } else { // Default internal handling
                                    ShortBuffer samples = (ShortBuffer) audioFrameToProcess.samples[0];
                                    ByteBuffer outBuffer = ByteBuffer.allocate(samples.capacity() * 2);
                                    for (int i = 0; i < samples.capacity(); i++)
                                        outBuffer.putShort(samples.get(i));
                                    finalSoundLine.write(outBuffer.array(), 0, outBuffer.capacity());
                                }
                            } catch (Exception e) {
                                LOG.log(Level.WARNING, "Error in audio processing task", e);
                            } finally {
                                audioFrameToProcess.close(); // Release cloned frame resources
                            }
                        });
                    }

                    // The original 'frame' from grabber.grab() is not explicitly closed here.
                    // It's assumed that FFmpegFrameGrabber manages the lifecycle of the returned Frame object,
                    // potentially reusing its internal buffers on the next call to grab().
                    // If frames were always cloned for processing (as done above for image/audio tasks),
                    // then the original might not need explicit closing in this loop.
                    // This matches the behavior of the original code snippet.

                    // --- MAIN LOOP SLEEP LOGIC (Backpressure for reading ahead) ---
                    long mainLoopSleepMillis = 0;
                    // How far ahead the current grabbed frame's timestamp is from the current playback time
                    long frameAheadOfPlaybackMicros = currentFrameRelativeTimestampMicros - currentPlaybackTimeMicros;

                    if (frameAheadOfPlaybackMicros > maxReadAheadBufferMicros) {
                        // If we've read too far ahead, sleep the main grabbing loop
                        mainLoopSleepMillis = (frameAheadOfPlaybackMicros - maxReadAheadBufferMicros) / 1000;

                        if (!finalPlaybackTimer.isAudioClockActive() && mainLoopSleepMillis > mainLoopNotReliableSleepCapMillis) {
                            System.err.println("[MainLoop] Audio clock not active. Main loop read-ahead sleep " + mainLoopSleepMillis + "ms too long. Capping to " + mainLoopNotReliableSleepCapMillis + "ms.");
                            mainLoopSleepMillis = mainLoopNotReliableSleepCapMillis;
                        }
                        // Similar cap if audio clock is active but elapsed time is still small (original logic)
                        else if (finalPlaybackTimer.isAudioClockActive() && currentPlaybackTimeMicros < 1500000 && mainLoopSleepMillis > 300) {
                            System.err.println("[MainLoop] Audio clock active but elapsed small (" + currentPlaybackTimeMicros + "us). Main loop read-ahead sleep " + mainLoopSleepMillis + "ms. Capping to 300ms.");
                            mainLoopSleepMillis = 300;
                        }
                    }

                    if (mainLoopSleepMillis > 5) { // Only sleep if meaningful
                        System.out.println("[MainLoop] Sleeping for " + mainLoopSleepMillis + "ms. FrameRelTS:" + currentFrameRelativeTimestampMicros + ", PlaybackTime:" + currentPlaybackTimeMicros + ", AudioClockActive:" + finalPlaybackTimer.isAudioClockActive());
                        Thread.sleep(mainLoopSleepMillis);
                    }
                }
                System.out.println("BytedecoFFmpegPlayer: ***** Main processing loop finished *****");

            } catch (FrameGrabber.Exception e) { // For grabber.start()
                LOG.log(Level.SEVERE, "Fatal error starting FFmpegFrameGrabber", e);
            } catch (LineUnavailableException e) { // For AudioSystem.getLine()
                LOG.log(Level.SEVERE, "Fatal error obtaining audio line", e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Preserve interrupt status
                LOG.log(Level.INFO, "Playback thread interrupted.", e);
            } catch (Exception e) { // Catch-all for other unexpected errors in the playback loop
                LOG.log(Level.SEVERE, "Unexpected error in playback thread", e);
            } finally {
                System.out.println("BytedecoFFmpegPlayer: Entering finally block for cleanup...");
                if (grabber != null) {
                    try {
                        System.out.println("BytedecoFFmpegPlayer: Stopping grabber...");
                        grabber.stop();
                        System.out.println("BytedecoFFmpegPlayer: Releasing grabber...");
                        grabber.release(); // Important to release native resources
                        System.out.println("BytedecoFFmpegPlayer: Grabber stopped and released.");
                    } catch (FrameGrabber.Exception e) {
                        LOG.log(Level.WARNING, "Error stopping/releasing grabber", e);
                    }
                }
                if (localSoundLine != null) {
                    System.out.println("BytedecoFFmpegPlayer: Draining, stopping, and closing audio line...");
                    localSoundLine.drain(); // Wait for buffered audio to play out
                    localSoundLine.stop();
                    localSoundLine.close();
                    System.out.println("BytedecoFFmpegPlayer: Audio line processed and closed.");
                }
                // Shutdown executors after they are no longer needed
                shutdownExecutor(audioExecutor, "AudioExecutor");
                shutdownExecutor(imageExecutor, "ImageExecutor");
                System.out.println("BytedecoFFmpegPlayer: Cleanup finished.");
            }
        });
        playThread.setDaemon(true); // So it doesn't prevent JVM exit
        playThread.setName("BytedecoFFmpegPlayer-Thread");
        playThread.start();
    }

    public void stop() {
        System.out.println("BytedecoFFmpegPlayer: stop() called.");
        if (playThread != null) {
            playThread.interrupt(); // Signal the thread to stop
            try {
                playThread.join(5000); // Wait for the thread to finish
                if (playThread.isAlive()) {
                    LOG.warning("Playback thread did not terminate after 5 seconds.");
                    // Consider more forceful shutdown of executors if needed, though they should respond to interrupt
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOG.log(Level.WARNING, "Interrupted while waiting for playThread to join.", e);
            }
            playThread = null; // Dereference the thread
        }
        // Ensure executors are shut down even if thread termination was problematic
        // However, proper shutdown should happen in thread's finally block
        if (audioExecutor != null && !audioExecutor.isShutdown()) {
            System.out.println("BytedecoFFmpegPlayer: stop() ensuring AudioExecutor is shutdown.");
            shutdownExecutor(audioExecutor, "AudioExecutor from stop()");
        }
        if (imageExecutor != null && !imageExecutor.isShutdown()) {
            System.out.println("BytedecoFFmpegPlayer: stop() ensuring ImageExecutor is shutdown.");
            shutdownExecutor(imageExecutor, "ImageExecutor from stop()");
        }
        System.out.println("BytedecoFFmpegPlayer: stop() finished.");
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null && !executor.isShutdown()) {
            System.out.println("BytedecoFFmpegPlayer: Shutting down " + name + "...");
            executor.shutdown(); // Disable new tasks from being submitted
            try {
                // Wait a while for existing tasks to terminate
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow(); // Cancel currently executing tasks
                    // Wait a while for tasks to respond to being cancelled
                    if (!executor.awaitTermination(2, TimeUnit.SECONDS))
                        System.err.println("BytedecoFFmpegPlayer: " + name + " did not terminate after shutdownNow.");
                } else {
                    System.out.println("BytedecoFFmpegPlayer: " + name + " shut down gracefully.");
                }
            } catch (InterruptedException ie) {
                // (Re-)Cancel if current thread also interrupted
                executor.shutdownNow();
                // Preserve interrupt status
                Thread.currentThread().interrupt();
            }
        }
    }
}
