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
        private Long systemStartTimeNanos = -1L;
        private final SourceDataLine soundLine;
        private boolean timerStarted = false;

        public PlaybackTimer(SourceDataLine soundLine) {
            this.soundLine = soundLine;
        }

        public PlaybackTimer() {
            this.soundLine = null;
        }

        public void start() {
            if (timerStarted) return;
            systemStartTimeNanos = System.nanoTime();

            if (soundLine != null) {
                System.out.println("PlaybackTimer: Started. Will attempt to use soundLine.getMicrosecondPosition(). System time recorded: " + systemStartTimeNanos);
            } else {
                System.out.println("PlaybackTimer: Started using System.nanoTime() (no soundLine).");
            }
            timerStarted = true;
        }

        public long elapsedMicros() {
            if (!timerStarted) return 0;

            if (soundLine != null && soundLine.isOpen() && soundLine.isRunning()) {
                long soundLinePos = soundLine.getMicrosecondPosition();
                return soundLinePos;
            } else {
                if (systemStartTimeNanos < 0) return 0;
                long elapsedSystemMicros = (System.nanoTime() - systemStartTimeNanos) / 1000;
                if (soundLine != null && timerStarted && (loopIteration < 200 || loopIteration % 200 == 1) ) {
                    System.err.println("PlaybackTimer: Fallback to System.nanoTime(). Elapsed: " + elapsedSystemMicros +
                            "us. SoundLine state: isOpen=" + (soundLine.isOpen()) +
                            ", isActive=" + (soundLine.isActive()) +
                            ", isRunning=" + (soundLine.isRunning()));
                }
                return elapsedSystemMicros;
            }
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
        primaryStage.setTitle("JavaFx Video + Audio (No Sleep Test)");
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
            long lastSuccessfullyGrabbedFrameTimestamp = -1L;

            int grabAttemptCounter = 0;
            int successfulNonNullGrabs = 0;
            boolean firstFrameGrabbed = false;

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
                    AudioFormat audioFormat = new AudioFormat((float)grabber.getSampleRate(), 16, grabber.getAudioChannels(), true, true);
                    DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);
                    localSoundLine = (SourceDataLine) AudioSystem.getLine(info);

                    int bytesPerFrame = audioFormat.getFrameSize();
                    float frameRate = audioFormat.getFrameRate();
                    int bufferTimeMillis = 750; // Increased buffer time to 750ms
                    int desiredBufferSize = (int) (bytesPerFrame * frameRate * (bufferTimeMillis / 1000.0f));
                    if (bytesPerFrame == AudioSystem.NOT_SPECIFIED) { // Handle case where frame size isn't directly available
                        bytesPerFrame = (audioFormat.getSampleSizeInBits() / 8) * audioFormat.getChannels();
                        desiredBufferSize = (int) (bytesPerFrame * frameRate * (bufferTimeMillis / 1000.0f));
                        System.out.println("JavaFxPlayVideoAndAudio: Audio frame size was NOT_SPECIFIED, calculated as: " + bytesPerFrame);
                    }
                    System.out.println("JavaFxPlayVideoAndAudio: Desired audio buffer size: " + desiredBufferSize + " bytes for " + bufferTimeMillis + "ms.");

                    localSoundLine.open(audioFormat, desiredBufferSize);

                    localSoundLine.start();
                    playbackTimer = new PlaybackTimer(localSoundLine);
                    System.out.println("JavaFxPlayVideoAndAudio: Audio line opened (actual buffer: " + localSoundLine.getBufferSize() + " bytes) and started. PlaybackTimer uses soundLine.");
                } else {
                    playbackTimer = new PlaybackTimer();
                    System.out.println("JavaFxPlayVideoAndAudio: No audio channels. PlaybackTimer uses System.nanoTime().");
                }
                final SourceDataLine finalSoundLine = localSoundLine;
                final PlaybackTimer finalPlaybackTimer = playbackTimer;

                System.out.println("JavaFxPlayVideoAndAudio: Starting main processing loop (ALL Thread.sleep DISABLED)...");

                while (!Thread.interrupted()) {
                    loopIteration++;
                    grabAttemptCounter++;
                    Frame frame = null;
                    try {
                        frame = grabber.grab();
                    } catch (FrameGrabber.Exception e) {
                        System.err.println("JavaFxPlayVideoAndAudio: [Iter " +loopIteration + "] EXCEPTION during grabber.grab(): " + e.getMessage());
                        break;
                    }

                    if (frame != null) {
                        successfulNonNullGrabs++;
                        if (!firstFrameGrabbed) {
                            System.out.println("JavaFxPlayVideoAndAudio: [Iter " +loopIteration + "] First valid frame grabbed (TS: " + frame.timestamp + "). Starting PlaybackTimer.");
                            finalPlaybackTimer.start();
                            firstFrameGrabbed = true;
                        }
                        lastSuccessfullyGrabbedFrameTimestamp = frame.timestamp;

                        boolean hasImage = (frame.image != null && frame.image[0] != null);
                        boolean hasAudio = (frame.samples != null && frame.samples[0] != null);

                        if (successfulNonNullGrabs % 50 == 1 && successfulNonNullGrabs > 1) {
                            System.out.println(String.format("JavaFxPlayVideoAndAudio: [Iter %d, SuccessGrab %d] TS:%d, HasImg:%b, HasAud:%b, ElapsedMicros:%d",
                                    loopIteration, successfulNonNullGrabs, frame.timestamp,
                                    hasImage, hasAudio, finalPlaybackTimer.elapsedMicros()
                            ));
                        }

                        if (hasImage) {
                            final Frame imageFrameToConvert = frame.clone();
                            imageExecutor.submit(() -> {
                                try {
                                    // final long videoFrameActualTimestamp = imageFrameToConvert.timestamp;
                                    // long elapsedMicrosBeforeDisplay = finalPlaybackTimer.elapsedMicros();
                                    // long delayNeededMicros = videoFrameActualTimestamp - elapsedMicrosBeforeDisplay;

                                    // <<<<< Thread.sleep for video sync TEMPORARILY DISABLED >>>>>
                                    /*
                                    if (delayNeededMicros > 1000) {
                                        long sleepMillis = delayNeededMicros / 1000;
                                        System.out.println("[ImageExecutor] Would sleep for " + sleepMillis + "ms. SKIPPING.");
                                        // Thread.sleep(sleepMillis);
                                    }
                                    */
                                    final Image fxImage = converter.convert(imageFrameToConvert);
                                    if (fxImage != null) {
                                        Platform.runLater(() -> imageView.setImage(fxImage));
                                    } else {
                                        System.err.println("JavaFxPlayVideoAndAudio: [ImageExecutor] converter.convert() returned NULL for image TS: " + imageFrameToConvert.timestamp);
                                    }
                                } catch (Exception e) { // Catch InterruptedException if sleep was re-enabled
                                    System.err.println("JavaFxPlayVideoAndAudio: [ImageExecutor] Exception processing image frame: " + e.getMessage());
                                }
                            });
                        }

                        if (hasAudio && finalSoundLine != null) {
                            final Frame audioFrame = frame.clone();
                            audioExecutor.submit(() -> {
                                try {
                                    if (audioFrame.samples != null && audioFrame.samples[0] != null) {
                                        ShortBuffer samples = (ShortBuffer) audioFrame.samples[0];
                                        samples.rewind();
                                        ByteBuffer outBuffer = ByteBuffer.allocate(samples.capacity() * 2);
                                        for (int i = 0; i < samples.capacity(); i++) {
                                            outBuffer.putShort(samples.get(i));
                                        }
                                        finalSoundLine.write(outBuffer.array(), 0, outBuffer.capacity());
                                    }
                                } catch (Exception e) {
                                    System.err.println("JavaFxPlayVideoAndAudio: [AudioExecutor] EXCEPTION writing to soundLine: " + e.getMessage());
                                }
                            });
                        }
                    } else {
                        System.err.println("JavaFxPlayVideoAndAudio: [Iter "+loopIteration+"] grabber.grab() RETURNED NULL. Breaking loop.");
                        break;
                    }
                }

                System.out.println("JavaFxPlayVideoAndAudio: ***** Main processing loop finished *****");

            } catch (Exception e) {
                LOG.log(Level.SEVERE, "Error in playback thread", e);
                e.printStackTrace();
            } finally {
                System.out.println("JavaFxPlayVideoAndAudio: Entering finally block for cleanup...");
                if (grabber != null) {
                    try { grabber.stop(); grabber.release(); } catch (Exception e) { LOG.log(Level.WARNING, "Error stopping grabber", e); }
                }
                if (localSoundLine != null) {
                    localSoundLine.drain(); localSoundLine.stop(); localSoundLine.close();
                }
                shutdownExecutor(audioExecutor, "AudioExecutor");
                shutdownExecutor(imageExecutor, "ImageExecutor");
                System.out.println("JavaFxPlayVideoAndAudio: Cleanup finished.");
            }
        });
        playThread.setDaemon(true);
        playThread.setName("JavaFX-NoSleepTest-Thread");
        playThread.start();
    }


    private void shutdownExecutor(ExecutorService executor, String name) {
        if (executor != null && !executor.isShutdown()) {
            System.out.println("JavaFxPlayVideoAndAudio: Shutting down " + name + "...");
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    System.err.println("JavaFxPlayVideoAndAudio: " + name + " did not terminate in 2s, forcing shutdown...");
                    executor.shutdownNow();
                    if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                        System.err.println("JavaFxPlayVideoAndAudio: " + name + " did not terminate even after shutdownNow().");
                    }
                } else {
                    System.out.println("JavaFxPlayVideoAndAudio: " + name + " shut down gracefully.");
                }
            } catch (InterruptedException ie) {
                System.err.println("JavaFxPlayVideoAndAudio: " + name + " shutdown interrupted.");
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
            try { playThread.join(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        Platform.exit();
        System.out.println("JavaFxPlayVideoAndAudio: Application stop() finished.");
    }
}
