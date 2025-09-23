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
    static volatile int loopIteration = 0;

    // --- PlaybackTimer Inner Static Class (Remains the same as before) ---
    private static class PlaybackTimer {
        private long timerStartTimeNanos = -1L;
        private long firstFrameTimestampMicros = -1L;
        private final SourceDataLine soundLine;
        private boolean timerStarted = false;
        private boolean soundLineClockSuccessfullyUsed = false;
        private boolean soundLineEverRan = false;

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
            this.soundLineClockSuccessfullyUsed = false;
            this.soundLineEverRan = false;
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
                    soundLineEverRan = true;
                    System.out.println("PlaybackTimer: SoundLine is NOW RUNNING. Will use its position.");
                }
                soundLineClockSuccessfullyUsed = true;
                return soundLine.getMicrosecondPosition();
            } else {
                if (soundLineClockSuccessfullyUsed) {
                    System.err.println("PlaybackTimer: SoundLine was used but is NOT RUNNING NOW. Reverting to System.nanoTime() based progress.");
                }
                soundLineClockSuccessfullyUsed = false;
                long systemDurationMicros = (System.nanoTime() - timerStartTimeNanos) / 1000;
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

    /**
     * Callback for delivering raw video frames.
     * The implementation is responsible for converting/displaying the frame.
     */
    @FunctionalInterface
    public interface VideoFrameCallback {
        /**
         * Called when a new video frame is available.
         * IMPORTANT: The provided 'videoFrame' might be reused by the grabber.
         * If you need to process it asynchronously or keep it longer, clone it.
         * @param videoFrame The raw video frame from FFmpegFrameGrabber.
         * @param relativeTimestampMicros The relative timestamp of this frame in microseconds.
         */
        void onFrame(Frame videoFrame, long relativeTimestampMicros);
    }

    /**
     * Callback for delivering raw audio data.
     */
    @FunctionalInterface
    public interface AudioDataCallback {
        void onAudioData(ShortBuffer samples, SourceDataLine lineToWriteTo, Frame originalFrame);
    }

    /**
     * Callback for player events like video dimension detection.
     */
    @FunctionalInterface
    public interface PlayerEventCallback {
        /**
         * Called when video dimensions are determined.
         * @param width The width of the video.
         * @param height The height of the video.
         */
        void onVideoDimensionsDetected(int width, int height);

        // Could add other events like onPlaybackStarted, onEndOfMedia, onError, etc.
    }

    private FFmpegFrameGrabber grabber;
    private SourceDataLine localSoundLine;
    // private final JavaFXFrameConverter converter; // Removed: UI layer will handle conversion
    private ExecutorService imageProcessingExecutor; // Renamed, as it processes raw Frames now
    private ExecutorService audioExecutor;
    private PlaybackTimer playbackTimer;
    private volatile Thread playThread;
    private int grabAttemptCounter = 0;

    private final VideoFrameCallback videoFrameCallback;
    private final AudioDataCallback audioDataCallback;
    private final PlayerEventCallback playerEventCallback; // Added

    // Sync Parameters (remain the same)
    private final long maxReadAheadBufferMicros = 700 * 1000L;
    private final long videoDelayCapMillis = 1000;
    private final long mainLoopNotReliableSleepCapMillis = 200;

    public BytedecoFFmpegPlayer(VideoFrameCallback videoFrameCallback,
                                AudioDataCallback audioDataCallback,
                                PlayerEventCallback playerEventCallback) {
        // this.converter = new JavaFXFrameConverter(); // Removed
        this.videoFrameCallback = videoFrameCallback;
        this.audioDataCallback = audioDataCallback;
        this.playerEventCallback = playerEventCallback; // Store the new callback
    }

    // Simplified constructor if no audio/event callbacks are needed by the client
    public BytedecoFFmpegPlayer(VideoFrameCallback videoFrameCallback, PlayerEventCallback playerEventCallback) {
        this(videoFrameCallback, null, playerEventCallback);
    }


    // Changed Stage primaryStageForResize to use playerEventCallback
    public void start(final String videoFilename) {
        if (playThread != null && playThread.isAlive()) {
            LOG.warning("Player is already running. Call stop() first.");
            return;
        }

        playThread = new Thread(() -> {
            BytedecoFFmpegPlayer.loopIteration = 0;
            this.grabAttemptCounter = 0;

            if (imageProcessingExecutor == null || imageProcessingExecutor.isShutdown()) {
                imageProcessingExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Player-RawFrameProcessor"));
            }
            if (audioExecutor == null || audioExecutor.isShutdown()) {
                audioExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Player-AudioProcessor"));
            }

            this.grabber = null;
            this.localSoundLine = null;
            this.playbackTimer = null;

            try {
                System.out.println("BytedecoFFmpegPlayer: Initializing grabber for URL: " + videoFilename);
                grabber = new FFmpegFrameGrabber(videoFilename);
                System.out.println("BytedecoFFmpegPlayer: Starting grabber...");
                grabber.start();
                System.out.println("BytedecoFFmpegPlayer: Grabber started. " +
                        "PixelFormat:" + grabber.getPixelFormat() +
                        ", ImageW/H:" + grabber.getImageWidth() + "/" + grabber.getImageHeight() +
                        ", AudioChannels:" + grabber.getAudioChannels() +
                        ", SampleRate:" + grabber.getSampleRate() +
                        ", FrameRate:" + grabber.getFrameRate());

                final int frameWidth = grabber.getImageWidth();
                final int frameHeight = grabber.getImageHeight();

                // Notify UI layer about video dimensions via callback
                if (frameWidth > 0 && frameHeight > 0 && playerEventCallback != null) {
                    playerEventCallback.onVideoDimensionsDetected(frameWidth, frameHeight);
                }

                if (grabber.getAudioChannels() > 0) {
                    AudioFormat audioFormat = new AudioFormat((float) grabber.getSampleRate(), 16, grabber.getAudioChannels(), true, true);
                    DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
                    if (!AudioSystem.isLineSupported(info)) {
                        System.err.println("BytedecoFFmpegPlayer: Default audio format not supported: " + audioFormat);
                        throw new LineUnavailableException("Audio format " + audioFormat + " not supported.");
                    }
                    localSoundLine = (SourceDataLine) AudioSystem.getLine(info);
                    int bytesPerFrame = audioFormat.getFrameSize();
                    if (bytesPerFrame == AudioSystem.NOT_SPECIFIED) {
                        bytesPerFrame = (audioFormat.getSampleSizeInBits() / 8) * audioFormat.getChannels();
                    }
                    float frameRate = audioFormat.getFrameRate();
                    int bufferTimeMillis = 750;
                    int desiredBufferSize = (int) (bytesPerFrame * frameRate * (bufferTimeMillis / 1000.0f));
                    System.out.println("BytedecoFFmpegPlayer: Desired audio buffer size: " + desiredBufferSize + " bytes for " + bufferTimeMillis + "ms.");
                    localSoundLine.open(audioFormat, desiredBufferSize);
                    localSoundLine.start();
                    playbackTimer = new PlaybackTimer(localSoundLine);
                    System.out.println("BytedecoFFmpegPlayer: Audio line opened (buffer: " + localSoundLine.getBufferSize() + " bytes) and started.");
                } else {
                    playbackTimer = new PlaybackTimer();
                    System.out.println("BytedecoFFmpegPlayer: No audio channels. PlaybackTimer uses System.nanoTime().");
                }
                final SourceDataLine finalSoundLine = localSoundLine;
                final PlaybackTimer finalPlaybackTimer = playbackTimer;

                // --- AUDIO WARM-UP STAGE (Logic remains similar) ---
                final int MAX_AUDIO_WARMUP_FRAMES = 30;
                if (finalSoundLine != null) {
                    System.out.println("BytedecoFFmpegPlayer: --- Starting Audio Warm-up Stage (Max " + MAX_AUDIO_WARMUP_FRAMES + " frames) ---");
                    for (int warmupIter = 0; warmupIter < MAX_AUDIO_WARMUP_FRAMES; warmupIter++) {
                        if (Thread.interrupted()) { System.out.println("BytedecoFFmpegPlayer: [Warmup] Interrupted."); break; }
                        Frame warmupFrame = null;
                        try { warmupFrame = grabber.grab(); } catch (FrameGrabber.Exception e) { LOG.log(Level.WARNING, "[Warmup] Error grabbing frame", e); break; }
                        if (warmupFrame == null) { System.out.println("BytedecoFFmpegPlayer: [Warmup] Grabber returned NULL. Ending warm-up."); break; }
                        BytedecoFFmpegPlayer.loopIteration++; this.grabAttemptCounter++;
                        if (!finalPlaybackTimer.hasTimerStarted()) {
                            System.out.println("BytedecoFFmpegPlayer: [Warmup, Iter " + BytedecoFFmpegPlayer.loopIteration + "] First frame for timer (TS_abs: " + warmupFrame.timestamp + "us). Starting PlaybackTimer.");
                            finalPlaybackTimer.start(warmupFrame.timestamp);
                        }
                        if (warmupFrame.samples != null && warmupFrame.samples[0] != null) {
                            final Frame audioFrameToWarm = warmupFrame.clone();
                            audioExecutor.submit(() -> {
                                try {
                                    if (audioDataCallback != null) {
                                        audioDataCallback.onAudioData((ShortBuffer) audioFrameToWarm.samples[0], finalSoundLine, audioFrameToWarm);
                                    } else {
                                        ShortBuffer samples = (ShortBuffer) audioFrameToWarm.samples[0];
                                        ByteBuffer outBuffer = ByteBuffer.allocate(samples.capacity() * 2);
                                        for (int i = 0; i < samples.capacity(); i++) outBuffer.putShort(samples.get(i));
                                        finalSoundLine.write(outBuffer.array(), 0, outBuffer.capacity());
                                    }
                                } catch (Exception e) { LOG.log(Level.WARNING, "[Warmup] Audio submission/processing error", e);
                                } finally { audioFrameToWarm.close(); }
                            });
                        }
                        try { Thread.sleep(5); } catch (InterruptedException e) { Thread.currentThread().interrupt(); System.out.println("BytedecoFFmpegPlayer: [Warmup] Sleep interrupted."); break; }
                        if (finalPlaybackTimer.isAudioClockActive()) { System.out.println("BytedecoFFmpegPlayer: [Warmup] Audio clock ACTIVE after " + (warmupIter + 1) + " frames."); break; }
                    }
                    if (finalPlaybackTimer.isAudioClockActive()) { System.out.println("BytedecoFFmpegPlayer: --- Audio Warm-up: Audio Clock is Active. ---");
                    } else { System.err.println("BytedecoFFmpegPlayer: --- Audio Warm-up: Audio Clock DID NOT become active. ---"); }
                }
                // --- END AUDIO WARM-UP ---

                System.out.println("BytedecoFFmpegPlayer: Starting main processing loop with RELATIVE synchronization...");
                while (!Thread.interrupted()) {
                    BytedecoFFmpegPlayer.loopIteration++;
                    this.grabAttemptCounter++;
                    Frame frame;
                    try {
                        frame = grabber.grab();
                    } catch (FrameGrabber.Exception e) {
                        LOG.log(Level.WARNING, "Error grabbing frame in main loop", e); break;
                    }
                    if (frame == null) {
                        System.err.println("BytedecoFFmpegPlayer: [Iter " + BytedecoFFmpegPlayer.loopIteration + "] Grabber returned NULL. Ending loop."); break;
                    }
                    if (!finalPlaybackTimer.hasTimerStarted()) {
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
                                finalPlaybackTimer.isAudioClockActive(), hasImage, hasAudio));
                    }

                    // --- RAW FRAME PROCESSING (Formerly Image Processing) ---
                    if (hasImage && videoFrameCallback != null) {
                        // Cloning is important here if the callback processes asynchronously
                        // or if the original frame from grabber might be overwritten.
                        final Frame rawVideoFrame = frame.clone();
                        imageProcessingExecutor.submit(() -> {
                            try {
                                long playbackTimeAtRenderDecision = finalPlaybackTimer.elapsedMicros();
                                long delayNeededMicros = currentFrameRelativeTimestampMicros - playbackTimeAtRenderDecision;
                                long sleepMillis = 0;
                                if (delayNeededMicros > 1000) {
                                    sleepMillis = delayNeededMicros / 1000;
                                    if (!finalPlaybackTimer.isAudioClockActive() && sleepMillis > videoDelayCapMillis) {
                                        System.err.println("[RawFrameExec] Audio clock not active. Video sleep " + sleepMillis + "ms for RelTS:" + currentFrameRelativeTimestampMicros + " too long. Capping to " + videoDelayCapMillis + "ms.");
                                        sleepMillis = videoDelayCapMillis;
                                    } else if (finalPlaybackTimer.isAudioClockActive() && playbackTimeAtRenderDecision < 1500000 && sleepMillis > 300) {
                                        System.err.println("[RawFrameExec] Audio clock active but elapsed small (" + playbackTimeAtRenderDecision + "us). Video sleep " + sleepMillis + "ms for RelTS:" + currentFrameRelativeTimestampMicros + ". Capping to 300ms.");
                                        sleepMillis = 300;
                                    }
                                }
                                if (sleepMillis > 0) { Thread.sleep(sleepMillis); }

                                // Pass the raw Frame to the callback
                                videoFrameCallback.onFrame(rawVideoFrame, currentFrameRelativeTimestampMicros);

                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                System.out.println("[RawFrameExec] Interrupted.");
                            } catch (Exception e) {
                                LOG.log(Level.WARNING, "Error in raw video frame processing task", e);
                            } finally {
                                rawVideoFrame.close(); // Release the cloned frame
                            }
                        });
                    } else if (hasImage && videoFrameCallback == null) {
                        // If there's an image but no callback, we might log or just discard.
                        // For now, matches original behavior of depending on callback.
                    }


                    // --- AUDIO PROCESSING (Logic remains similar) ---
                    if (hasAudio && finalSoundLine != null) {
                        final Frame audioFrameToProcess = frame.clone();
                        audioExecutor.submit(() -> {
                            try {
                                if (audioDataCallback != null) {
                                    audioDataCallback.onAudioData((ShortBuffer) audioFrameToProcess.samples[0], finalSoundLine, audioFrameToProcess);
                                } else {
                                    ShortBuffer samples = (ShortBuffer) audioFrameToProcess.samples[0];
                                    ByteBuffer outBuffer = ByteBuffer.allocate(samples.capacity() * 2);
                                    for (int i = 0; i < samples.capacity(); i++) outBuffer.putShort(samples.get(i));
                                    finalSoundLine.write(outBuffer.array(), 0, outBuffer.capacity());
                                }
                            } catch (Exception e) { LOG.log(Level.WARNING, "Error in audio processing task", e);
                            } finally { audioFrameToProcess.close(); }
                        });
                    }

                    // Original frame from grabber.grab() is not closed here.
                    // Assumed to be managed by the grabber or overwritten by the next grab().
                    // Clones are made for async tasks and are closed in their respective finally blocks.


                    // --- MAIN LOOP SLEEP LOGIC (Remains similar) ---
                    long mainLoopSleepMillis = 0;
                    long frameAheadOfPlaybackMicros = currentFrameRelativeTimestampMicros - currentPlaybackTimeMicros;
                    if (frameAheadOfPlaybackMicros > maxReadAheadBufferMicros) {
                        mainLoopSleepMillis = (frameAheadOfPlaybackMicros - maxReadAheadBufferMicros) / 1000;
                        if (!finalPlaybackTimer.isAudioClockActive() && mainLoopSleepMillis > mainLoopNotReliableSleepCapMillis) {
                            mainLoopSleepMillis = mainLoopNotReliableSleepCapMillis;
                        } else if (finalPlaybackTimer.isAudioClockActive() && currentPlaybackTimeMicros < 1500000 && mainLoopSleepMillis > 300) {
                            mainLoopSleepMillis = 300;
                        }
                    }
                    if (mainLoopSleepMillis > 5) {
                        Thread.sleep(mainLoopSleepMillis);
                    }
                }
                System.out.println("BytedecoFFmpegPlayer: ***** Main processing loop finished *****");

            } catch (FrameGrabber.Exception e) { LOG.log(Level.SEVERE, "Fatal error starting FFmpegFrameGrabber", e);
            } catch (LineUnavailableException e) { LOG.log(Level.SEVERE, "Fatal error obtaining audio line", e);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); LOG.log(Level.INFO, "Playback thread interrupted.", e);
            } catch (Exception e) { LOG.log(Level.SEVERE, "Unexpected error in playback thread", e);
            } finally {
                System.out.println("BytedecoFFmpegPlayer: Entering finally block for cleanup...");
                if (grabber != null) {
                    try { grabber.stop(); grabber.release(); } catch (FrameGrabber.Exception e) { LOG.log(Level.WARNING, "Error stopping/releasing grabber", e); }
                }
                if (localSoundLine != null) { localSoundLine.drain(); localSoundLine.stop(); localSoundLine.close(); }
                shutdownExecutor(audioExecutor, "AudioExecutor");
                shutdownExecutor(imageProcessingExecutor, "RawFrameProcessingExecutor"); // Renamed
                System.out.println("BytedecoFFmpegPlayer: Cleanup finished.");
            }
        });
        playThread.setDaemon(true);
        playThread.setName("BytedecoFFmpegPlayer-Thread");
        playThread.start();
    }

    public void stop() { // Remains largely the same
        System.out.println("BytedecoFFmpegPlayer: stop() called.");
        if (playThread != null) {
            playThread.interrupt();
            try {
                playThread.join(5000);
                if (playThread.isAlive()) { LOG.warning("Playback thread did not terminate after 5 seconds."); }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); LOG.log(Level.WARNING, "Interrupted while waiting for playThread to join.", e); }
            playThread = null;
        }
        if (audioExecutor != null && !audioExecutor.isShutdown()) { shutdownExecutor(audioExecutor, "AudioExecutor from stop()"); }
        if (imageProcessingExecutor != null && !imageProcessingExecutor.isShutdown()) { shutdownExecutor(imageProcessingExecutor, "RawFrameExecutor from stop()");}
        System.out.println("BytedecoFFmpegPlayer: stop() finished.");
    }

    private void shutdownExecutor(ExecutorService executor, String name) { // Remains the same
        if (executor != null && !executor.isShutdown()) {
            System.out.println("BytedecoFFmpegPlayer: Shutting down " + name + "...");
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    if (!executor.awaitTermination(2, TimeUnit.SECONDS)) System.err.println("BytedecoFFmpegPlayer: " + name + " did not terminate.");
                } else { System.out.println("BytedecoFFmpegPlayer: " + name + " shut down gracefully."); }
            } catch (InterruptedException ie) { executor.shutdownNow(); Thread.currentThread().interrupt(); }
        }
    }
}