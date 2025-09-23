package idv.neo.ffmpeg.media.player.desktop;

import static idv.neo.ffmpeg.media.player.core.utils.UtilsKt.getPixelFormatName;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import javax.sound.sampled.*;
import javax.swing.*;

import java.awt.image.BufferedImage;
import java.nio.ShortBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

public class SwingVideoPlayer {

    private static final Logger LOG = Logger.getLogger(SwingVideoPlayer.class.getName());
    private volatile Thread playThread;
    private final PlayerSurface videoPanel;

    // --- PlaybackTimer Inner Class (Identical to JavaFxPlayVideoAndAudio.java's) ---
    private static class PlaybackTimer {
        private long timerSystemStartTimeNanos = -1L;
        private long timerFirstFrameAbsoluteTimestampMicros = -1L;
        private final SourceDataLine soundLine;
        private boolean timerHasStarted = false;
        private boolean timerSoundLineEverRan = false;
        private boolean timerAudioClockActiveForLastCheck = false; // To track if audio clock was active

        public PlaybackTimer(SourceDataLine soundLine) {
            this.soundLine = soundLine;
        }

        public PlaybackTimer() {
            this.soundLine = null;
        }

        public void start(long firstFrameAbsoluteTsMicros) {
            if (timerHasStarted) return;
            this.timerFirstFrameAbsoluteTimestampMicros = firstFrameAbsoluteTsMicros;
            this.timerSystemStartTimeNanos = System.nanoTime();
            this.timerHasStarted = true;
            this.timerSoundLineEverRan = false;
            this.timerAudioClockActiveForLastCheck = false;
            // System.out.println("SwingPlayer: PlaybackTimer Started. FirstAbsTS: " + firstFrameAbsoluteTsMicros + " us");
        }

        public boolean isAudioClockReliable() {
            boolean isActive = soundLine != null && soundLine.isOpen() && soundLine.isRunning();
            // Optional: Log transitions for debugging
            // if (isActive && !timerAudioClockActiveForLastCheck) {
            //     System.out.println("SwingPlayer: PlaybackTimer - Audio clock is NOW considered RELIABLE.");
            // } else if (!isActive && timerAudioClockActiveForLastCheck) {
            //     System.out.println("SwingPlayer: PlaybackTimer - Audio clock was reliable, but NOT ANYMORE.");
            // }
            timerAudioClockActiveForLastCheck = isActive;
            return isActive;
        }

        public long getCurrentRelativePlaybackTimeMicros() {
            if (!timerHasStarted) return 0L;

            if (isAudioClockReliable()) {
                if (!timerSoundLineEverRan) {
                    timerSoundLineEverRan = true;
                    // System.out.println("SwingPlayer: PlaybackTimer - SoundLine is RUNNING, using its microsecondPosition.");
                }
                return soundLine.getMicrosecondPosition();
            } else {
                long systemDurationMicros = (System.nanoTime() - timerSystemStartTimeNanos) / 1000L;
                // Optional periodic log for fallback
                // if (loopIteration % 100 == 0 && soundLine != null) {
                //     System.out.println("SwingPlayer: PlaybackTimer - Audio clock UNRELIABLE. Using System.nanoTime(). Elapsed: " + systemDurationMicros + "us. SoundLine: isOpen=" + soundLine.isOpen() + ", isRunning=" + soundLine.isRunning());
                // }
                return systemDurationMicros;
            }
        }

        public long getFirstFrameAbsoluteTimestampMicros() {
            return timerFirstFrameAbsoluteTimestampMicros;
        }

        public boolean hasTimerStarted() {
            return timerHasStarted;
        }
    }
    // --- End PlaybackTimer ---

    private static long S_loopIteration = 0L; // Static for PlaybackTimer's optional logging

    // --- Sync Parameters (from JavaFxPlayVideoAndAudio & ViewModel) ---
    private static final long MAX_READ_AHEAD_MICROS = 700 * 1000L;
    private static final long VIDEO_DELAY_CAP_MILLIS_UNRELIABLE_TIMER = 1000L;
    private static final long VIDEO_MAX_SLEEP_RELIABLE_MS = 1000L; // From recent ViewModel
    private static final long MAIN_LOOP_DELAY_CAP_MILLIS_UNRELIABLE_TIMER = 300L;
    private static final long GENERAL_MAX_SLEEP_MILLIS = 2000L; // General cap
    private static final long MIN_MEANINGFUL_VIDEO_DELAY_MS = 10L;


    public SwingVideoPlayer(PlayerSurface videoPanel) {
        this.videoPanel = videoPanel;
    }

    public void startStreaming(String videoUrl) {
        if (playThread != null && playThread.isAlive()) {
            playThread.interrupt();
            try {
                playThread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        playThread = new Thread(() -> {
            S_loopIteration = 0L;
            FFmpegFrameGrabber grabber = null;
            SourceDataLine localSoundLine = null;
            final ExecutorService imageProcessingExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Swing-ImageProcessor"));
            final ExecutorService audioPlaybackExecutor = Executors.newSingleThreadExecutor(r -> new Thread(r, "Swing-AudioPlayer"));

            PlaybackTimer playbackTimer = null; // Will be initialized
            int actualPixelFormat = -1;

            try {
                // --- INITIALIZE GRABBER AND SOUND LINE ---
                System.out.println("SwingPlayer: Initializing for URL: " + videoUrl);
                grabber = new FFmpegFrameGrabber(videoUrl);
                // grabber.setOption("stimeout", "5000000"); // 5 sec timeout
                grabber.start(); // Can throw
                actualPixelFormat = grabber.getPixelFormat();
                System.out.println(String.format("SwingPlayer: Grabber started. Format:%s(%d), Size:%dx%d, FPS:%.2f",
                         getPixelFormatName(actualPixelFormat), actualPixelFormat,
                        grabber.getImageWidth(), grabber.getImageHeight(), grabber.getFrameRate()));
                System.out.println(String.format("SwingPlayer: Audio: Ch:%d, Rate:%d, Codec:%s",
                        grabber.getAudioChannels(), grabber.getSampleRate(), grabber.getAudioCodecName()));


                if (grabber.getAudioChannels() > 0) {
                    float sampleRate = (float) grabber.getSampleRate();
                    AudioFormat audioFormat = new AudioFormat(sampleRate, 16, grabber.getAudioChannels(), true, false); // S16LE
                    DataLine.Info info = new DataLine.Info(SourceDataLine.class, audioFormat);

                    if (!AudioSystem.isLineSupported(info)) {
                        System.err.println("SwingPlayer: S16LE not supported: " + audioFormat + ". Trying S16BE.");
                        audioFormat = new AudioFormat(sampleRate, 16, grabber.getAudioChannels(), true, true); // S16BE
                        info = new DataLine.Info(SourceDataLine.class, audioFormat);
                    }

                    if (AudioSystem.isLineSupported(info)) {
                        localSoundLine = (SourceDataLine) AudioSystem.getLine(info);
                        int bufferMillis = 750;
                        int frameSize = audioFormat.getFrameSize();
                        if (frameSize == AudioSystem.NOT_SPECIFIED) frameSize = (audioFormat.getSampleSizeInBits() / 8) * audioFormat.getChannels();
                        int desiredBufferSize = (int) (frameSize * audioFormat.getFrameRate() * (bufferMillis / 1000.0f));
                        localSoundLine.open(audioFormat, desiredBufferSize);
                        localSoundLine.start();
                        playbackTimer = new PlaybackTimer(localSoundLine);
                        System.out.println("SwingPlayer: Audio line opened. Buffer: " + localSoundLine.getBufferSize() + " bytes. Format: " + audioFormat);
                    } else {
                        System.err.println("SwingPlayer: Audio line NOT supported (even S16BE). No audio.");
                        playbackTimer = new PlaybackTimer();
                    }
                } else {
                    System.out.println("SwingPlayer: No audio channels.");
                    playbackTimer = new PlaybackTimer();
                }
                final PlaybackTimer finalTimer = playbackTimer; // For use in lambdas/inner scope
                final SourceDataLine finalAudioLine = localSoundLine;
                final int finalActualPixelFormat = actualPixelFormat;


                // --- AUDIO WARM-UP (mimicking JavaFX logic) ---
                if (finalAudioLine != null) {
                    System.out.println("SwingPlayer: --- Starting Audio Warm-up ---");
                    boolean warmupSuccess = false;
                    long lastWarmupPlaybackTime = -1L;
                    int stableChecks = 0;
                    final int MAX_WARMUP_FRAMES = 30;
                    final int REQUIRED_STABLE_CHECKS = 3;

                    for (int i = 0; i < MAX_WARMUP_FRAMES; i++) {
                        if (Thread.interrupted()) break;
                        Frame warmFrame = grabber.grabFrame(true, true, false, false); // Prioritize audio
                        if (warmFrame == null) break;

                        if (!finalTimer.hasTimerStarted()) {
                            finalTimer.start(warmFrame.timestamp);
                        }
                        if (warmFrame.samples != null && warmFrame.samples[0] != null) {
                            final Frame audioFrameToPlay = warmFrame.clone();
                            // Play audio synchronously for warmup simplicity here, or use executor
                            playAudioFrameSwing(audioFrameToPlay, finalAudioLine);
                        }
                        try { Thread.sleep(25); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }

                        long currentWarmupTime = finalTimer.getCurrentRelativePlaybackTimeMicros();
                        if (finalTimer.isAudioClockReliable()) {
                            if (currentWarmupTime > 0 && currentWarmupTime > lastWarmupPlaybackTime) stableChecks++;
                            else if (currentWarmupTime == lastWarmupPlaybackTime && currentWarmupTime > 0) stableChecks++;
                            else stableChecks = 0;
                            if (stableChecks >= REQUIRED_STABLE_CHECKS) {
                                warmupSuccess = true; break;
                            }
                        } else stableChecks = 0;
                        lastWarmupPlaybackTime = currentWarmupTime;
                        // if (i % 5 == 0) System.out.println("SwingPlayer: [Warmup Iter " + (i + 1) + "] PlaybackTime:" + currentWarmupTime + ", Reliable:" + finalTimer.isAudioClockReliable() + ", StableChecks:" + stableChecks);
                    }
                    System.out.println("SwingPlayer: --- Audio Warm-up Finished. Success: " + warmupSuccess + " ---");
                }
                // --- END AUDIO WARM-UP ---


                System.out.println("SwingPlayer: Starting main frame processing loop (Relative Sync).");
                while (!Thread.interrupted()) {
                    S_loopIteration++;
                    Frame frame = grabber.grab();
                    if (frame == null) {
                        System.out.println("SwingPlayer: End of stream (null frame).");
                        break;
                    }

                    if (!finalTimer.hasTimerStarted()) {
                        finalTimer.start(frame.timestamp);
                    }

                    final long currentFrameAbsoluteTs = frame.timestamp;
                    final long currentFrameRelativeTs = currentFrameAbsoluteTs - finalTimer.getFirstFrameAbsoluteTimestampMicros();
                    final long currentRelativePlaybackTime = finalTimer.getCurrentRelativePlaybackTimeMicros();

                    if (S_loopIteration <= 10 || S_loopIteration % 50 == 1) {
                        System.out.printf("SwingPlayer: [Loop %d] AbsTS:%d, RelTS:%d, PlaybackTime:%d, AudioReliable:%b, Img:%b, Aud:%b%n",
                                S_loopIteration, currentFrameAbsoluteTs, currentFrameRelativeTs, currentRelativePlaybackTime,
                                finalTimer.isAudioClockReliable(), (frame.image != null), (frame.samples != null));
                    }

                    boolean hasImage = (frame.image != null && frame.imageWidth > 0 && frame.imageHeight > 0);
                    boolean hasAudio = (frame.samples != null && frame.samples[0] != null && finalAudioLine != null);

                    // --- ASYNCHRONOUS VIDEO PROCESSING (mimicking JavaFX's Executor) ---
                    if (hasImage) {
                        final Frame imageFrameForProcessing = frame.clone(); // Crucial
                        imageProcessingExecutor.submit(() -> {
                            if (Thread.currentThread().isInterrupted()) return;
                            try {
                                long playbackTimeAtRenderDecision = finalTimer.getCurrentRelativePlaybackTimeMicros();
                                long delayNeededMicros = currentFrameRelativeTs - playbackTimeAtRenderDecision;
                                long videoDelayMillis = 0L;

                                if (delayNeededMicros > 0) {
                                    videoDelayMillis = delayNeededMicros / 1000;
                                    if (videoDelayMillis < MIN_MEANINGFUL_VIDEO_DELAY_MS) {
                                        videoDelayMillis = 0L;
                                    } else {
                                        boolean audioGood = finalTimer.isAudioClockReliable();
                                        if (!audioGood && videoDelayMillis > VIDEO_DELAY_CAP_MILLIS_UNRELIABLE_TIMER) {
                                            videoDelayMillis = VIDEO_DELAY_CAP_MILLIS_UNRELIABLE_TIMER;
                                        } else if (audioGood && videoDelayMillis > VIDEO_MAX_SLEEP_RELIABLE_MS) {
                                            videoDelayMillis = VIDEO_MAX_SLEEP_RELIABLE_MS;
                                        }
                                    }
                                }
                                if (videoDelayMillis > 0) Thread.sleep(videoDelayMillis);

                                if (Thread.currentThread().isInterrupted()) return;

                                BufferedImage bImage = FrameConverter.INSTANCE.convert(imageFrameForProcessing, finalActualPixelFormat);
                                if (bImage != null) {
                                    // Update on EDT
                                    SwingUtilities.invokeLater(() -> videoPanel.updateImage(bImage));
                                } else {
                                    // System.err.println("SwingPlayer [ImageExec]: converDirectToBufferedImage returned null for RelTS:" + currentFrameRelativeTs);
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt(); // Preserve interrupt status
                            } catch (Exception e) {
                                System.err.println("SwingPlayer [ImageExec]: Exception: " + e.getMessage());
                                // e.printStackTrace();
                            }
                        });
                    }

                    // --- ASYNCHRONOUS AUDIO PROCESSING ---
                    if (hasAudio) {
                        final Frame audioFrameToPlay = frame.clone();
                        audioPlaybackExecutor.submit(() -> {
                            if (Thread.currentThread().isInterrupted()) return;
                            playAudioFrameSwing(audioFrameToPlay, finalAudioLine);
                        });
                    }

                    // --- MAIN LOOP SYNCHRONIZATION (Preventing excessive read-ahead) ---
                    long mainLoopSleepMillis = 0L;
                    long frameIsAheadByMicros = currentFrameRelativeTs - currentRelativePlaybackTime;

                    if (frameIsAheadByMicros > MAX_READ_AHEAD_MICROS) {
                        mainLoopSleepMillis = (frameIsAheadByMicros - MAX_READ_AHEAD_MICROS) / 1000;
                        boolean audioGood = finalTimer.isAudioClockReliable();
                        if (!audioGood && mainLoopSleepMillis > MAIN_LOOP_DELAY_CAP_MILLIS_UNRELIABLE_TIMER) {
                            mainLoopSleepMillis = MAIN_LOOP_DELAY_CAP_MILLIS_UNRELIABLE_TIMER;
                        } else if (audioGood && mainLoopSleepMillis > GENERAL_MAX_SLEEP_MILLIS) {
                            mainLoopSleepMillis = GENERAL_MAX_SLEEP_MILLIS;
                        }
                    }

                    if (mainLoopSleepMillis > 5) {
                        Thread.sleep(mainLoopSleepMillis);
                    }
                    // No explicit yield as Thread.sleep() yields.
                } // end while

            } catch (InterruptedException e) {
                LOG.info("SwingPlayer: Playback thread interrupted.");
                Thread.currentThread().interrupt(); // Preserve interrupt
            } catch (FFmpegFrameGrabber.Exception e) {
                LOG.log(Level.SEVERE, "SwingPlayer: Grabber exception", e);
            } catch (Exception e) {
                LOG.log(Level.SEVERE, "SwingPlayer: General exception in playback thread", e);
            } finally {
                LOG.info("SwingPlayer: Playback loop finished. Cleaning up...");
                if (grabber != null) try { grabber.stop(); grabber.release(); } catch (FFmpegFrameGrabber.Exception e) { LOG.log(Level.WARNING, "Error stopping grabber", e); }
                if (localSoundLine != null) { localSoundLine.drain(); localSoundLine.stop(); localSoundLine.close(); }
                shutdownExecutor(audioPlaybackExecutor, "AudioPlaybackExecutor-Swing");
                shutdownExecutor(imageProcessingExecutor, "ImageProcessingExecutor-Swing");
                LOG.info("SwingPlayer: Cleanup complete.");
            }
        });
        playThread.setDaemon(true);
        playThread.setName("Swing-VideoAudio-PlayerThread");
        playThread.start();
    }

    private void playAudioFrameSwing(Frame frame, SourceDataLine soundLineInstance) {
        // This method should be identical to the one in your previous SwingVideoPlayer.java
        // Ensure it correctly extracts ShortBuffer and writes to soundLineInstance
        if (soundLineInstance == null || !soundLineInstance.isOpen() || frame.samples == null || frame.samples.length == 0) return;

        Object sampleBufferObj = frame.samples[0];
        if (!(sampleBufferObj instanceof ShortBuffer)) return;

        ShortBuffer shortBuffer = (ShortBuffer) sampleBufferObj;
        int numSamplesRemaining = shortBuffer.remaining();
        if (numSamplesRemaining == 0) return;

        byte[] audioBytes = new byte[numSamplesRemaining * 2]; // Each short is 2 bytes

        // Use a loop that respects the buffer's current position and limit
        for (int i = 0; i < numSamplesRemaining; i++) {
            short val = shortBuffer.get(); // Reads from current position and advances it
            audioBytes[i * 2] = (byte) (val & 0xFF);
            audioBytes[i * 2 + 1] = (byte) ((val >> 8) & 0xFF);
        }
        // It's important that after this loop, shortBuffer.position() has advanced by numSamplesRemaining.

        try {
            soundLineInstance.write(audioBytes, 0, audioBytes.length);
        } catch (Exception e) {
            // Log this error, as it's critical for audio playback
            System.err.println("SwingPlayer [AudioPlayer]: Error writing audio to soundLine: " + e.getMessage());
        }
    }

    private void shutdownExecutor(ExecutorService executor, String name) {
        // Identical to previous version
        if (executor != null && !executor.isShutdown()) {
            System.out.println("SwingPlayer: Shutting down " + name + "...");
            executor.shutdown();
            try {
                if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    if (!executor.awaitTermination(2, TimeUnit.SECONDS))
                        System.err.println(name + " did not terminate.");
                }
            } catch (InterruptedException ie) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
