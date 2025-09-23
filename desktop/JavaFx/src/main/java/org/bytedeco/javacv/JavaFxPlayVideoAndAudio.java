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

    private static final Logger LOG = Logger.getLogger(JavaFxPlayVideoAndAudio.class.getName());

    private  BytedecoFFmpegPlayer player;


    public static void main(String[] args) {
        // FFmpegLogCallback.set(); // If you use FFmpegLogCallback, set it up here once globally
        launch(args);
    }

    @Override
    public void start(final Stage primaryStage) {
        System.out.println("JavaFxPlayVideoAndAudio: Application start() method.");
        final StackPane root = new StackPane();
        final ImageView imageView = new ImageView(); // Initialize ImageView
        root.getChildren().add(imageView);

        // Configure ImageView properties
        imageView.setPreserveRatio(true);
        imageView.fitWidthProperty().bind(root.widthProperty());
        imageView.fitHeightProperty().bind(root.heightProperty());

        final Scene scene = new Scene(root, 640, 480); // Initial size, player might resize
        primaryStage.setTitle("JavaFx Video + Audio Player (Refactored)");
        primaryStage.setScene(scene);
        primaryStage.show();

        // --- Instantiate and use BytedecoFFmpegPlayer ---
        player = new BytedecoFFmpegPlayer(
                // VideoFrameCallback: Handles displaying the image on the UI thread
                (fxImage, relativeTimestampMicros) -> {
                    if (fxImage != null) {
                        Platform.runLater(() -> {
                            if (imageView != null) { // Ensure imageView is still valid
                                imageView.setImage(fxImage);
                            }
                        });
                    } else {
                        // This case might happen if converter returns null
                        // System.err.println("JavaFxPlayVideoAndAudio: Received null fxImage from player callback.");
                    }
                }
                // Optional: Provide an AudioDataCallback for custom audio handling or visualization
                // For this example, we'll let BytedecoFFmpegPlayer handle audio internally.
            /*
            , (samples, lineToWriteTo, originalFrame) -> {
                // Example: Custom audio processing if needed
                // System.out.println("AudioDataCallback: Received audio samples. Buffer capacity: " + samples.capacity());
                // try {
                //     ByteBuffer outBuffer = ByteBuffer.allocate(samples.capacity() * 2);
                //     for (int i = 0; i < samples.capacity(); i++)
                //         outBuffer.putShort(samples.get(i));
                //     lineToWriteTo.write(outBuffer.array(), 0, outBuffer.capacity());
                // } catch (Exception e) {
                //     LOG.log(Level.WARNING, "Error in custom audio callback", e);
                // }
            }
            */
        );

        // final String videoFilename = "path/to/your/video.mp4"; // Replace with your video file or URL
        final String videoFilename = "https://github.com/rambod-rahmani/ffmpeg-video-player/raw/refs/heads/master/Iron_Man-Trailer_HD.mp4";
        System.out.println("JavaFxPlayVideoAndAudio: Starting player with file: " + videoFilename);
        player.start(videoFilename, primaryStage); // Pass primaryStage for potential resize
    }

    @Override
    public void stop() throws Exception {
        System.out.println("JavaFxPlayVideoAndAudio: Application stop() called.");
        if (player != null) {
            player.stop(); // Gracefully stop the player
        }
        // Platform.exit(); // Usually not needed, JavaFX handles this when last window closes.
        // If you must force exit, consider System.exit(0) after ensuring player cleanup.
        System.out.println("JavaFxPlayVideoAndAudio: Application stop() finished.");
        // Explicitly call Platform.exit() if the application doesn't close otherwise,
        // for example, if there are non-daemon threads still running that aren't managed by the player.
        // However, the player's thread is a daemon thread.
        Platform.exit(); // Ensure JavaFX platform shuts down.
    }
}
