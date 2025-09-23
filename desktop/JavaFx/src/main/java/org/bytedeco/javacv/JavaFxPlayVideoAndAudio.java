package org.bytedeco.javacv;

import java.util.logging.Logger;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import java.util.logging.Level;
/**
 * @author Dmitriy Gerashenko <d.a.gerashenko@gmail.com>
 * @author Jarek Sacha
 */
// FrameConverter.INSTANCE.convert will be used from the Kotlin object
// JavaFXFrameConverter is not directly instantiated here if using the Kotlin FrameConverter
public class JavaFxPlayVideoAndAudio extends Application {

    private static final Logger LOG_UI = Logger.getLogger(JavaFxPlayVideoAndAudio.class.getName() + ".UI");
    private JavaFxSwingFFmpegPlayer player;
    private ImageView imageView;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(final Stage primaryStage) {
        LOG_UI.info("JavaFX Application start() method.");
        final StackPane root = new StackPane();
        imageView = new ImageView();

        root.getChildren().add(imageView);
        imageView.setPreserveRatio(true);
        imageView.fitWidthProperty().bind(root.widthProperty());
        imageView.fitHeightProperty().bind(root.heightProperty());

        final Scene scene = new Scene(root, 640, 480);
        primaryStage.setTitle("Generic Player with JavaFX UI (Universal Converter)");
        primaryStage.setScene(scene);
        primaryStage.show();

        JavaFxSwingFFmpegPlayer.Builder playerBuilder = new JavaFxSwingFFmpegPlayer.Builder(
                (videoFrame, relativeTimestampMicros) -> {
                    if (videoFrame != null) {
                        // Use the @JvmStatic method from the Kotlin UniversalFrameConverter
                        Image fxImage = UniversalFrameConverter.convertToFxImage(videoFrame); // <--- UPDATED
                        if (fxImage != null) {
                            Platform.runLater(() -> {
                                if (imageView != null) {
                                    imageView.setImage(fxImage);
                                }
                            });
                        }
                    }
                },
                new JavaFxSwingFFmpegPlayer.PlayerEventCallback() {
                    @Override
                    public void onVideoDimensionsDetected(int width, int height, int pixelFormat) { // <--- ADDED pixelFormat parameter
                        Platform.runLater(() -> {
                            LOG_UI.info("Video dimensions detected: " + width + "x" + height + ", PixelFormat: " + pixelFormat);
                            // JavaFX's UniversalFrameConverter.convertToFxImage doesn't currently use the pixelFormat explicitly,
                            // as JavaFXFrameConverter handles format detection internally.
                            // However, we receive it to match the interface and for potential future use or logging.

                            if (primaryStage != null && width > 0 && height > 0) {
                                // Consider existing stage dimensions to avoid unnecessary resizing if already optimal
                                double currentContentWidth = primaryStage.getWidth();
                                // Estimate content height (primaryStage.getHeight() includes decorations)
                                // This is a rough estimate; actual scene height might be more accurate if available.
                                double currentContentHeight = primaryStage.getHeight() - (primaryStage.getScene() != null ? (primaryStage.getHeight() - primaryStage.getScene().getHeight()) : 40);


                                // Only resize if significantly different or if current size is default/small
                                boolean needsResize = Math.abs(currentContentWidth - width) > 20 || Math.abs(currentContentHeight - height) > 20;
                                if (primaryStage.getWidth() < 100 || primaryStage.getHeight() < 100) { // If stage is very small (e.g. initial default)
                                    needsResize = true;
                                }

                                if (needsResize) {
                                    LOG_UI.info("Resizing JavaFX stage to fit video dimensions: " + width + "x" + height);
                                    primaryStage.setWidth(width);
                                    primaryStage.setHeight(height + 40); // Approximate height for window decorations
                                } else {
                                    LOG_UI.info("JavaFX stage size (" + primaryStage.getWidth() + "x" + primaryStage.getHeight() + ") is already adequate for video (" + width + "x" + height + "). No resize.");
                                }
                            }
                        });
                    }

                    @Override
                    public void onPlaybackStarted() {
                        Platform.runLater(() -> {
                            LOG_UI.info("Playback started (UI callback).");
                            // Example: Update UI elements to reflect "playing" state
                            // if (playButton != null) playButton.setDisable(true);
                            // if (statusLabel != null) statusLabel.setText("Playing...");
                        });
                    }

                    @Override
                    public void onEndOfMedia() {
                        Platform.runLater(() -> {
                            LOG_UI.info("End of media reached (UI callback).");
                            // Example: Reset UI elements
                            // if (playButton != null) playButton.setDisable(false);
                            // if (statusLabel != null) statusLabel.setText("Finished. Ready to play.");
                            if (imageView != null) {
                                // imageView.setImage(null); // Optionally clear the last displayed frame
                            }
                        });
                    }

                    @Override
                    public void onError(String errorMessage, Exception e) {
                        Platform.runLater(() -> {
                            LOG_UI.log(Level.SEVERE, "Player Error (UI callback): " + errorMessage, e);

                            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR);
                            alert.setTitle("Playback Error");
                            alert.setHeaderText("An error occurred during video playback.");
                            alert.setContentText("Error: " + errorMessage +
                                    (e != null ? "\nDetails: " + e.getClass().getSimpleName() + " - " + e.getMessage() : ""));
                            // Optional: Add expandable content for stack trace
                            // if (e != null) {
                            //     StringWriter sw = new StringWriter();
                            //     PrintWriter pw = new PrintWriter(sw);
                            //     e.printStackTrace(pw);
                            //     String exceptionText = sw.toString();
                            //     TextArea textArea = new TextArea(exceptionText);
                            //     textArea.setEditable(false);
                            //     textArea.setWrapText(true);
                            //     textArea.setMaxWidth(Double.MAX_VALUE);
                            //     textArea.setMaxHeight(Double.MAX_VALUE);
                            //     GridPane.setVgrow(textArea, Priority.ALWAYS);
                            //     GridPane.setHgrow(textArea, Priority.ALWAYS);
                            //     GridPane expContent = new GridPane();
                            //     expContent.setMaxWidth(Double.MAX_VALUE);
                            //     expContent.add(new Label("The exception stacktrace was:"), 0, 0);
                            //     expContent.add(textArea, 0, 1);
                            //     alert.getDialogPane().setExpandableContent(expContent);
                            // }
                            alert.showAndWait();

                            if (imageView != null) {
                                imageView.setImage(null); // Clear video display on error
                            }
                            // Reset UI elements like play button
                            // if (playButton != null) playButton.setDisable(false);
                        });
                    }
                }
        );

        this.player = playerBuilder.build();
        final String videoFilename = "https://github.com/rambod-rahmani/ffmpeg-video-player/raw/refs/heads/master/Iron_Man-Trailer_HD.mp4";
        LOG_UI.info("Starting player with media: " + videoFilename);
        player.start(videoFilename);
    }

    @Override
    public void stop() throws Exception {
        LOG_UI.info("JavaFX Application stop() method called.");
        if (player != null) {
            player.stop();
        }
        Platform.exit();
    }
}
