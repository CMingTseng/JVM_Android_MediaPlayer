package org.bytedeco.javacv;

import java.util.logging.Logger;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * @author Dmitriy Gerashenko <d.a.gerashenko@gmail.com>
 * @author Jarek Sacha
 */
public class JavaFxPlayVideoAndAudio extends Application {

    private static final Logger LOG = Logger.getLogger(JavaFxPlayVideoAndAudio.class.getName());
    private BytedecoFFmpegPlayer player;
    private ImageView imageView;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(final Stage primaryStage) {
        System.out.println("JavaFxPlayVideoAndAudio: Application start() method.");
        final StackPane root = new StackPane();
        imageView = new ImageView();
        root.getChildren().add(imageView);
        imageView.setPreserveRatio(true);
        imageView.fitWidthProperty().bind(root.widthProperty());
        imageView.fitHeightProperty().bind(root.heightProperty());

        final Scene scene = new Scene(root, 640, 480);
        primaryStage.setTitle("JavaFx Video + Audio Player (Further Refactored)");
        primaryStage.setScene(scene);
        primaryStage.show();

        // --- Instantiate and use BytedecoFFmpegPlayer ---
        player = new BytedecoFFmpegPlayer(
                // VideoFrameCallback: Receives raw org.bytedeco.javacv.Frame
                (rawVideoFrame, relativeTimestampMicros) -> {
                    if (rawVideoFrame != null && rawVideoFrame.image != null) {
                        // Convert the raw Frame to JavaFX Image here
                        // IMPORTANT: The rawVideoFrame in the callback might be reused by the player.
                        // The player internally clones it before submitting to the imageProcessingExecutor,
                        // and that clone is closed by the executor. So, direct use here within this
                        // callback scope *should* be okay IF the callback is lightweight and synchronous.
                        // If this callback itself were to do heavy async work with rawVideoFrame,
                        // it would need to clone it again.
                        // However, the player's provided rawVideoFrame to onFrame is already a clone
                        // specifically for this callback's processing thread.

                        Image fxImage = FrameConverter.convert(rawVideoFrame);
                        if (fxImage != null) {
                            Platform.runLater(() -> {
                                if (imageView != null) {
                                    imageView.setImage(fxImage);
                                }
                            });
                        }
                    } else {
                        // System.err.println("JavaFxPlayVideoAndAudio: Received null or imageless rawFrame from player callback.");
                    }
                },
                // Optional AudioDataCallback (remains the same if used)
                null, // Or your audio callback: (samples, lineToWriteTo, originalFrame) -> { /* ... */ }
                // PlayerEventCallback: Handles events like video dimensions
                (width, height) -> {
                    Platform.runLater(() -> {
                        System.out.println("JavaFxPlayVideoAndAudio: Video dimensions detected by player: " + width + "x" + height);
                        if (primaryStage != null && width > 0 && height > 0) {
                            primaryStage.setWidth(width);
                            // Add some extra height for title bar, etc. as in original code
                            primaryStage.setHeight(height + 40);
                        }
                    });
                }
        );

        final String videoFilename = "https://github.com/rambod-rahmani/ffmpeg-video-player/raw/refs/heads/master/Iron_Man-Trailer_HD.mp4";
        System.out.println("JavaFxPlayVideoAndAudio: Starting player with file: " + videoFilename);
        // Player no longer takes Stage directly
        player.start(videoFilename);
    }

    @Override
    public void stop() throws Exception {
        System.out.println("JavaFxPlayVideoAndAudio: Application stop() called.");
        if (player != null) {
            player.stop();
        }
        System.out.println("JavaFxPlayVideoAndAudio: Application stop() finished.");
        Platform.exit();
    }
}
