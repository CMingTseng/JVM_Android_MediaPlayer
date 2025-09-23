package idv.neo.ffmpeg.media.player.desktop;

import javax.swing.*;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.JavaFxSwingFFmpegPlayer;
// Import the new UniversalFrameConverter, adjust package if it's different
import org.bytedeco.javacv.UniversalFrameConverter;


import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.image.BufferedImage;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Application {
    private static final Logger LOG_UI = Logger.getLogger(Application.class.getName() + ".UI");

    private static JTextField videoUrlField;
    private static JavaFxSwingFFmpegPlayer player;
    private static PlayerSurface playerSurface;
    private static JButton playButton;
    private static JFrame frame;
    private static volatile int currentFramePixelFormat = -1; // To store pixel format for converter

    public static void main(String[] args) {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "[%1$tF %1$tT] [%4$-7s] %5$s %n");
        SwingUtilities.invokeLater(Application::createAndShowGUI);
    }

    private static void createAndShowGUI() {
        frame = new JFrame("Generic Player with Swing UI (Universal Player)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(600, 400));

        playerSurface = new PlayerSurface();
        playerSurface.setPreferredSize(new Dimension(640, 480));
        frame.getContentPane().add(playerSurface, BorderLayout.CENTER);

        JavaFxSwingFFmpegPlayer.Builder playerBuilder = new JavaFxSwingFFmpegPlayer.Builder(
                (videoFrame, relativeTimestampMicros) -> { // VideoFrameOutputCallback
                    if (videoFrame != null) {
                        // Pass the stored/updated pixel format
                        BufferedImage swingImage = UniversalFrameConverter.convertToBufferedImage(videoFrame, currentFramePixelFormat);
                        if (swingImage != null) {
                            playerSurface.updateImage(swingImage);
                        }
                    }
                },
                new JavaFxSwingFFmpegPlayer.PlayerEventCallback() {
                    @Override
                    public void onVideoDimensionsDetected(int width, int height, int pixelFormat) { // Receive pixelFormat
                        LOG_UI.info("Video dimensions detected: " + width + "x" + height + ", PixelFormat Value: " + pixelFormat);
                        Application.currentFramePixelFormat = pixelFormat; // Store it for use by the converter
                        // Optional: Log the name of the pixel format if you have a utility for it
                        // LOG_UI.info("Pixel Format Name: " + YourPixelFormatUtils.getFormatName(pixelFormat));

                        SwingUtilities.invokeLater(() -> {
                            // UI updates if needed based on dimensions
                        });
                    }

                    @Override
                    public void onPlaybackStarted() {
                        LOG_UI.info("Playback started.");
                        // The pixel format should ideally be known by now from onVideoDimensionsDetected.
                        // So, the original logic trying to get grabber instance here is no longer needed.
                        SwingUtilities.invokeLater(() -> {
                            playButton.setText("Playing...");
                            playButton.setEnabled(false);
                            videoUrlField.setEnabled(false);
                        });
                    }

                    @Override
                    public void onEndOfMedia() {
                        LOG_UI.info("End of media reached.");
                        SwingUtilities.invokeLater(() -> {
                            playButton.setText("Play Video");
                            playButton.setEnabled(true);
                            videoUrlField.setEnabled(true);
                            Application.currentFramePixelFormat = -1; // Reset pixel format
                        });
                    }

                    @Override
                    public void onError(String errorMessage, Exception e) {
                        LOG_UI.log(Level.SEVERE, "Player Error: " + errorMessage, e);
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(frame,
                                    errorMessage + (e != null ? "\n" + e.getMessage() : ""),
                                    "Playback Error", JOptionPane.ERROR_MESSAGE);
                            playButton.setText("Play Video");
                            playButton.setEnabled(true);
                            videoUrlField.setEnabled(true);
                            Application.currentFramePixelFormat = -1; // Reset pixel format
                        });
                    }
                }
        );

        player = playerBuilder.build();

        JPanel controlPanel = new JPanel(new BorderLayout());
        JPanel inputPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        inputPanel.add(new JLabel("Video URL:"));
        videoUrlField = new JTextField(40);
        videoUrlField.setText("https://github.com/rambod-rahmani/ffmpeg-video-player/raw/refs/heads/master/Iron_Man-Trailer_HD.mp4");
        inputPanel.add(videoUrlField);
        controlPanel.add(inputPanel, BorderLayout.NORTH);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        playButton = new JButton("Play Video");
        buttonPanel.add(playButton);
        controlPanel.add(buttonPanel, BorderLayout.CENTER);
        frame.getContentPane().add(controlPanel, BorderLayout.SOUTH);

        playButton.addActionListener(e -> {
            String videoUrl = videoUrlField.getText().trim();
            if (videoUrl.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Please enter a video URL.", "URL Missing", JOptionPane.WARNING_MESSAGE);
                return;
            }
            LOG_UI.info("Play button clicked. URL: " + videoUrl);
            playButton.setEnabled(false);
            videoUrlField.setEnabled(false);
            player.start(videoUrl); // Player is async
        });

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}