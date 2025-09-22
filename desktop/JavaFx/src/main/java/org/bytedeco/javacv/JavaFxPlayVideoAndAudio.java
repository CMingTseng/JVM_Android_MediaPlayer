package org.bytedeco.javacv;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.JavaFXFrameConverter;

/**
 * @author Dmitriy Gerashenko <d.a.gerashenko@gmail.com>
 * @author Jarek Sacha
 */
public class JavaFxPlayVideoAndAudio extends Application {
    private static class PlaybackTimer {
        private long timerStartTimeNanos = -1L;
        private long firstFrameTimestampMicros = -1L;

        private final SourceDataLine soundLine;
        private boolean timerStarted = false;
        private boolean soundLineClockSuccessfullyUsed = false;
        private boolean soundLineEverRan = false; // To track if soundline has run at least once

        public PlaybackTimer(SourceDataLine soundLine) {
            this.soundLine = soundLine;
        }

        public PlaybackTimer() {
            this.soundLine = null;
        }

        /**
         * Starts the playback timer. This should be called when the first frame
         * (audio or video) is about to be processed.
         *
         * @param firstValidFrameTimestampMicros The timestamp of the first frame from the grabber (in microseconds).
         */
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

        /**
         * Gets the elapsed time in microseconds since the timer was started.
         * This represents the ideal playback position relative to the first frame.
         */
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

                if (soundLine != null && (loopIteration < 20 || loopIteration % 100 == 1)) {
                    System.err.println("PlaybackTimer: Using System.nanoTime() for elapsed. SystemDuration: " + systemDurationMicros +
                            "us. SoundLine state: isOpen=" + soundLine.isOpen() +
                            ", isRunning=" + soundLine.isRunning() +
                            ", EverRan=" + soundLineEverRan);
                }
                return systemDurationMicros;
            }
        }

        /**
         * Checks if the sound line is currently open and running, indicating the audio clock is active.
         */
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


    private static final Logger LOG = Logger.getLogger(JavaFxPlayVideoAndAudio.class.getName());
    private static volatile Thread playThread;
    private static volatile int loopIteration = 0;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(final Stage primaryStage) throws Exception {
        final StackPane root = new StackPane();
        final ImageView imageView = new ImageView();
        root.getChildren().add(imageView);
        imageView.setPreserveRatio(true);
        imageView.fitWidthProperty().bind(root.widthProperty());
        imageView.fitHeightProperty().bind(root.heightProperty());
        final Scene scene = new Scene(root, 640, 480);
        primaryStage.setTitle("JavaFx Video + Audio");
        primaryStage.setScene(scene);
        primaryStage.show();
        playThread = new Thread(() -> {
            loopIteration = 0;
            FFmpegFrameGrabber grabber = null;
            SourceDataLine localSoundLine = null;
            final JavaFXFrameConverter converter = new JavaFXFrameConverter();
            final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
            final ExecutorService audioExecutor = Executors.newSingleThreadExecutor();
            PlaybackTimer playbackTimer = null;
            final long maxReadAheadBufferMicros = 700 * 1000L;
            final long videoDelayCapMillis = 1000;
            final long mainLoopNotReliableSleepCapMillis = 200;
            int grabAttemptCounter = 0;

            try {
                final String videoFilename = "https://github.com/rambod-rahmani/ffmpeg-video-player/raw/refs/heads/master/Iron_Man-Trailer_HD.mp4";
                System.out.println("JavaFxPlayVideoAndAudio: Initializing grabber for URL: " + videoFilename);
                grabber = new FFmpegFrameGrabber(videoFilename);
                FFmpegLogCallback.set();
                System.out.println("JavaFxPlayVideoAndAudio: Starting grabber...");
                grabber.start();
                System.out.println("JavaFxPlayVideoAndAudio: Grabber started. " +
                        "PixelFormat:" + grabber.getPixelFormat() +
                        ", ImageW/H:" + grabber.getImageWidth() + "/" + grabber.getImageHeight() +
                        ", AudioChannels:" + grabber.getAudioChannels() +
                        ", SampleRate:" + grabber.getSampleRate() +
                        ", FrameRate:" + grabber.getFrameRate());

                final int frameWidth = grabber.getImageWidth();
                final int frameHeight = grabber.getImageHeight();
                if (frameWidth > 0 && frameHeight > 0) {
                    Platform.runLater(() -> {
                        primaryStage.setWidth(frameWidth);
                        primaryStage.setHeight(frameHeight + 40);
                    });
                }


                if (grabber.getAudioChannels() > 0) {
                    AudioFormat audioFormat = new AudioFormat((float) grabber.getSampleRate(), 16, grabber.getAudioChannels(), true, true);
                    DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
                    localSoundLine = (SourceDataLine) AudioSystem.getLine(info);
                    int bytesPerFrame = audioFormat.getFrameSize();
                    if (bytesPerFrame == AudioSystem.NOT_SPECIFIED) {
                        bytesPerFrame = (audioFormat.getSampleSizeInBits() / 8) * audioFormat.getChannels();
                    }
                    float frameRate = audioFormat.getFrameRate();
                    int bufferTimeMillis = 750;
                    int desiredBufferSize = (int) (bytesPerFrame * frameRate * (bufferTimeMillis / 1000.0f));
                    System.out.println("JavaFxPlayVideoAndAudio: Desired audio buffer size: " + desiredBufferSize + " bytes for " + bufferTimeMillis + "ms.");
                    localSoundLine.open(audioFormat, desiredBufferSize);
                    localSoundLine.start(); // Start the line
                    playbackTimer = new PlaybackTimer(localSoundLine); // Pass the line to timer
                    System.out.println("JavaFxPlayVideoAndAudio: Audio line opened (buffer: " + localSoundLine.getBufferSize() + " bytes) and started.");
                } else {
                    playbackTimer = new PlaybackTimer(); // No audio line
                    System.out.println("JavaFxPlayVideoAndAudio: No audio channels. PlaybackTimer uses System.nanoTime().");
                }
                final SourceDataLine finalSoundLine = localSoundLine;
                final PlaybackTimer finalPlaybackTimer = playbackTimer;

                // --- AUDIO WARM-UP STAGE (Same as before, good to keep) ---
                final int MAX_AUDIO_WARMUP_FRAMES = 30;
                boolean audioWarmUpAttempted = false;
                if (finalSoundLine != null) {
                    audioWarmUpAttempted = true;
                    System.out.println("JavaFxPlayVideoAndAudio: --- Starting Audio Warm-up Stage (Max " + MAX_AUDIO_WARMUP_FRAMES + " frames) ---");
                    for (int warmupIter = 0; warmupIter < MAX_AUDIO_WARMUP_FRAMES; warmupIter++) {
                        if (Thread.interrupted()) break;
                        Frame warmupFrame = grabber.grab();
                        if (warmupFrame == null) break;

                        loopIteration++;
                        grabAttemptCounter++;

                        if (!finalPlaybackTimer.hasTimerStarted()) {
                            System.out.println("JavaFxPlayVideoAndAudio: [Warmup, Iter " + loopIteration + "] First frame for timer (TS_abs: " + warmupFrame.timestamp + "us). Starting PlaybackTimer.");
                            finalPlaybackTimer.start(warmupFrame.timestamp);
                        }

                        if (warmupFrame.samples != null && warmupFrame.samples[0] != null) {
                            final Frame audioFrameToWarm = warmupFrame.clone();
                            audioExecutor.submit(() -> {
                                try {
                                    ShortBuffer samples = (ShortBuffer) audioFrameToWarm.samples[0];
                                    ByteBuffer outBuffer = ByteBuffer.allocate(samples.capacity() * 2);
                                    for (int i = 0; i < samples.capacity(); i++)
                                        outBuffer.putShort(samples.get(i));
                                    finalSoundLine.write(outBuffer.array(), 0, outBuffer.capacity());
                                } catch (Exception e) { /* log */ }
                            });
                        }
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        if (finalPlaybackTimer.isAudioClockActive()) { // Check using the new method
                            System.out.println("JavaFxPlayVideoAndAudio: [Warmup] Audio clock ACTIVE after " + (warmupIter + 1) + " frames.");
                            break;
                        }
                    }
                    if (finalPlaybackTimer.isAudioClockActive()) {
                        System.out.println("JavaFxPlayVideoAndAudio: --- Audio Warm-up: Audio Clock is Active. ---");
                    } else {
                        System.err.println("JavaFxPlayVideoAndAudio: --- Audio Warm-up: Audio Clock DID NOT become active. ---");
                    }
                }

                System.out.println("JavaFxPlayVideoAndAudio: Starting main processing loop with RELATIVE synchronization...");

                while (!Thread.interrupted()) {
                    loopIteration++;
                    grabAttemptCounter++;
                    Frame frame = null;
                    try {
                        frame = grabber.grab();
                    } catch (FrameGrabber.Exception e) {
                        break;
                    }

                    if (frame == null) {
                        System.err.println("JavaFxPlayVideoAndAudio: [Iter " + loopIteration + "] Grabber returned NULL. Ending loop.");
                        break;
                    }

                    if (!finalPlaybackTimer.hasTimerStarted()) {
                        System.out.println("JavaFxPlayVideoAndAudio: [MainLoop, Iter " + loopIteration + "] First frame for timer (TS_abs: " + frame.timestamp + "us). Starting PlaybackTimer.");
                        finalPlaybackTimer.start(frame.timestamp);
                    }
                    final long currentFrameRelativeTimestampMicros = frame.timestamp - finalPlaybackTimer.getFirstFrameTimestampMicros();
                    final long currentPlaybackTimeMicros = finalPlaybackTimer.elapsedMicros();
                    boolean hasImage = (frame.image != null && frame.image[0] != null);
                    boolean hasAudio = (frame.samples != null && frame.samples[0] != null);

                    if (loopIteration <= 10 || loopIteration % 50 == 1) {
                        System.out.println(String.format(
                                "JavaFxPlayVideoAndAudio: [Iter %d] FrameTS_abs:%d, FrameTS_rel:%d, PlaybackTime:%d, AudioClockActive:%b, Img:%b, Aud:%b",
                                loopIteration, frame.timestamp, currentFrameRelativeTimestampMicros, currentPlaybackTimeMicros,
                                finalPlaybackTimer.isAudioClockActive(), hasImage, hasAudio
                        ));
                    }

                    // --- IMAGE PROCESSING ---
                    if (hasImage) {
                        final Frame imageFrameToConvert = frame.clone();
                        final long imageFrameRelativeTs = currentFrameRelativeTimestampMicros;
                        imageExecutor.submit(() -> {
                            try {
                                long playbackTimeAtRenderDecision = finalPlaybackTimer.elapsedMicros();
                                long delayNeededMicros = imageFrameRelativeTs - playbackTimeAtRenderDecision;
                                long sleepMillis = 0;
                                if (delayNeededMicros > 1000) {
                                    sleepMillis = delayNeededMicros / 1000;
                                    if (!finalPlaybackTimer.isAudioClockActive() && sleepMillis > videoDelayCapMillis) {
                                        System.err.println("[ImageExec] Audio clock not active. Video sleep " + sleepMillis + "ms for RelTS:" + imageFrameRelativeTs + " too long. Capping to " + videoDelayCapMillis + "ms.");
                                        sleepMillis = videoDelayCapMillis;
                                    }
                                    else if (finalPlaybackTimer.isAudioClockActive() && playbackTimeAtRenderDecision < 1500000 && sleepMillis > 300) {
                                        System.err.println("[ImageExec] Audio clock active but elapsed small (" + playbackTimeAtRenderDecision + "us). Video sleep " + sleepMillis + "ms for RelTS:" + imageFrameRelativeTs + ". Capping to 300ms.");
                                        sleepMillis = 300;
                                    }
                                }
                                if (sleepMillis > 0) Thread.sleep(sleepMillis);

                                final Image fxImage = converter.convert(imageFrameToConvert);
                                if (fxImage != null) {
                                    Platform.runLater(() -> imageView.setImage(fxImage));
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } catch (Exception e) { /* log error */ }
                        });
                    }

                    // --- AUDIO PROCESSING ---
                    if (hasAudio && finalSoundLine != null) {
                        final Frame audioFrame = frame.clone();
                        audioExecutor.submit(() -> {
                            try {
                                ShortBuffer samples = (ShortBuffer) audioFrame.samples[0];
                                ByteBuffer outBuffer = ByteBuffer.allocate(samples.capacity() * 2);
                                for (int i = 0; i < samples.capacity(); i++)
                                    outBuffer.putShort(samples.get(i));
                                finalSoundLine.write(outBuffer.array(), 0, outBuffer.capacity());
                            } catch (Exception e) { /* log */ }
                        });
                    }
                    long mainLoopSleepMillis = 0;
                    long frameAheadOfPlaybackMicros = currentFrameRelativeTimestampMicros - currentPlaybackTimeMicros;
                    if (frameAheadOfPlaybackMicros > maxReadAheadBufferMicros) {
                        mainLoopSleepMillis = (frameAheadOfPlaybackMicros - maxReadAheadBufferMicros) / 1000;
                        if (!finalPlaybackTimer.isAudioClockActive() && mainLoopSleepMillis > mainLoopNotReliableSleepCapMillis) {
                            System.err.println("[MainLoop] Audio clock not active. Main sleep " + mainLoopSleepMillis + "ms too long. Capping to " + mainLoopNotReliableSleepCapMillis + "ms.");
                            mainLoopSleepMillis = mainLoopNotReliableSleepCapMillis;
                        }
                        // Similar cap if audio clock is active but elapsed time is still small
                        else if (finalPlaybackTimer.isAudioClockActive() && currentPlaybackTimeMicros < 1500000 && mainLoopSleepMillis > 300) {
                            System.err.println("[MainLoop] Audio clock active but elapsed small (" + currentPlaybackTimeMicros + "us). Main sleep " + mainLoopSleepMillis + "ms. Capping to 300ms.");
                            mainLoopSleepMillis = 300;
                        }
                    }
                    if (mainLoopSleepMillis > 5) {
                        System.out.println("[MainLoop] Sleeping for " + mainLoopSleepMillis + "ms. FrameRelTS:" + currentFrameRelativeTimestampMicros + ", PlaybackTime:" + currentPlaybackTimeMicros + ", AudioClockActive:" + finalPlaybackTimer.isAudioClockActive());
                        Thread.sleep(mainLoopSleepMillis);
                    }
                }
                System.out.println("JavaFxPlayVideoAndAudio: ***** Main processing loop finished *****");
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Error in playback thread", e);
                e.printStackTrace();
            } finally {
                System.out.println("JavaFxPlayVideoAndAudio: Entering finally block for cleanup...");
                if (grabber != null) {
                    try {
                        grabber.stop();
                        grabber.release();
                    } catch (Exception e) {
                        LOG.log(Level.WARNING, "Error stopping grabber", e);
                    }
                }
                if (localSoundLine != null) { /* ... drain, stop, close ... */
                    localSoundLine.drain();
                    localSoundLine.stop();
                    localSoundLine.close();
                }
                shutdownExecutor(audioExecutor, "AudioExecutor");
                shutdownExecutor(imageExecutor, "ImageExecutor");
                System.out.println("JavaFxPlayVideoAndAudio: Cleanup finished.");
            }
        });
        playThread.setDaemon(true);
        playThread.setName("JavaFX-RelativeSync-Thread");
        playThread.start();
    }
    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null && !executor.isShutdown()) {
            System.out.println("JavaFxPlayVideoAndAudio: Shutting down " + name + "...");
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    if (!executor.awaitTermination(2, TimeUnit.SECONDS))
                        System.err.println(name + " did not terminate.");
                } else {
                    System.out.println("JavaFxPlayVideoAndAudio: " + name + " shut down gracefully.");
                }
            } catch (InterruptedException ie) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void stop() throws Exception {
        System.out.println("JavaFxPlayVideoAndAudio: Application stop() called.");
        if (playThread != null) {
            playThread.interrupt();
            try {
                playThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        Platform.exit();
        System.out.println("JavaFxPlayVideoAndAudio: Application stop() finished.");
    }
}
