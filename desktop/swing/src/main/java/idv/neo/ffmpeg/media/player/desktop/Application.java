package idv.neo.ffmpeg.media.player.desktop;

import org.bytedeco.javacv.Frame;
import idv.neo.ffmpeg.media.player.core.JavaFxSwingComposeFFmpegPlayer; // Kotlin Player
import idv.neo.ffmpeg.media.player.core.PlayerEvent; // Kotlin PlayerEvent
import idv.neo.ffmpeg.media.player.core.UniversalFrameConverter;

// 導入 Kotlin 的函數接口
import kotlin.Unit;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Application {
    private static final Logger LOG_UI = Logger.getLogger(Application.class.getName() + ".UI");

    private static JTextField videoUrlField;
    private static JavaFxSwingComposeFFmpegPlayer player; // Use Kotlin Player
    private static PlayerSurface playerSurface;
    private static JButton playButton;
    private static JButton stopButton;
    private static JFrame frame;
    private static volatile int currentFramePixelFormat = -1;

    public static void main(String[] args) {
        System.setProperty("java.util.logging.SimpleFormatter.format",
                "[%1$tF %1$tT] [%4$-7s] %5$s %n");
        SwingUtilities.invokeLater(Application::createAndShowGUI);
    }

    private static void createAndShowGUI() {
        frame = new JFrame("Swing Player with Kotlin Core");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new Dimension(600, 400));

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                LOG_UI.info("Window closing event triggered.");
                if (player != null) {
                    LOG_UI.info("Stopping player due to window close...");
                    player.close();
                }
                frame.dispose();
                System.exit(0);
            }
        });

        playerSurface = new PlayerSurface();
        playerSurface.setPreferredSize(new Dimension(640, 480));
        frame.getContentPane().add(playerSurface, BorderLayout.CENTER);

        // PlayerEventCallback for Kotlin Player
        // 使用 kotlin.jvm.functions.Function1
        Function1<PlayerEvent, Unit> playerEventCallback = event -> {
            if (event instanceof PlayerEvent.VideoDimensionsDetected) {
                PlayerEvent.VideoDimensionsDetected d = (PlayerEvent.VideoDimensionsDetected) event;
                LOG_UI.info("Video dimensions detected: " + d.getWidth() + "x" + d.getHeight() +
                        ", PixelFormat: " + d.getPixelFormat() + ", FrameRate: " + d.getFrameRate());
                Application.currentFramePixelFormat = d.getPixelFormat();
                SwingUtilities.invokeLater(() -> {
                    // frame.pack(); // Optional resize
                });
            } else if (event instanceof PlayerEvent.PlaybackStarted) {
                LOG_UI.info("Playback started (UI callback).");
                SwingUtilities.invokeLater(() -> {
                    playButton.setText("Playing...");
                    playButton.setEnabled(false);
                    stopButton.setEnabled(true);
                    videoUrlField.setEnabled(false);
                });
            } else if (event instanceof PlayerEvent.EndOfMedia) {
                LOG_UI.info("End of media reached (UI callback).");
                SwingUtilities.invokeLater(() -> {
                    playButton.setText("Play Video");
                    playButton.setEnabled(true);
                    stopButton.setEnabled(false);
                    videoUrlField.setEnabled(true);
                    Application.currentFramePixelFormat = -1;
                });
            } else if (event instanceof PlayerEvent.Error) {
                PlayerEvent.Error err = (PlayerEvent.Error) event;
                LOG_UI.log(Level.SEVERE, "Player Error (UI callback): " + err.getErrorMessage(), err.getException());
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(frame,
                            err.getErrorMessage() + (err.getException() != null ? "\nDetails: " + err.getException().getMessage() : ""),
                            "Playback Error", JOptionPane.ERROR_MESSAGE);
                    playButton.setText("Play Video");
                    playButton.setEnabled(true);
                    stopButton.setEnabled(false);
                    videoUrlField.setEnabled(true);
                    Application.currentFramePixelFormat = -1;
                });
            }
            return Unit.INSTANCE; // Kotlin lambda 返回 Unit
        };

        // VideoFrameOutputCallback for Kotlin Player
        // 使用 kotlin.jvm.functions.Function2
        Function2<Frame, Long, Unit> videoFrameOutputCallback = (videoFrame, relativeTimestampMicros) -> {
            if (videoFrame != null) {
                BufferedImage swingImage = UniversalFrameConverter.convertToBufferedImage(videoFrame, currentFramePixelFormat);
                if (swingImage != null) {
                    SwingUtilities.invokeLater(() -> playerSurface.updateImage(swingImage));
                }
            }
            return Unit.INSTANCE; // Kotlin lambda 返回 Unit
        };

        player = new JavaFxSwingComposeFFmpegPlayer(
                videoFrameOutputCallback,
                playerEventCallback,
                null // AudioDataOutputCallback is Function3, can be null
        );

        // ... (rest of the UI setup code remains the same) ...

        JPanel controlPanel = new JPanel(new BorderLayout());
        JPanel inputPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        inputPanel.add(new JLabel("Video URL:"));
        videoUrlField = new JTextField(40);
        videoUrlField.setText("https://github.com/rambod-rahmani/ffmpeg-video-player/raw/refs/heads/master/Iron_Man-Trailer_HD.mp4");
        inputPanel.add(videoUrlField);
        controlPanel.add(inputPanel, BorderLayout.NORTH);

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        playButton = new JButton("Play Video");
        stopButton = new JButton("Stop");
        stopButton.setEnabled(false);

        buttonPanel.add(playButton);
        buttonPanel.add(stopButton);
        controlPanel.add(buttonPanel, BorderLayout.CENTER);

        frame.getContentPane().add(controlPanel, BorderLayout.SOUTH);

        playButton.addActionListener(e -> {
            String videoUrl = videoUrlField.getText().trim();
            if (videoUrl.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "Please enter a video URL.", "URL Missing", JOptionPane.WARNING_MESSAGE);
                return;
            }
            LOG_UI.info("Play button clicked. URL: " + videoUrl);
            player.start(videoUrl);
        });

        stopButton.addActionListener(e -> {
            LOG_UI.info("Stop button clicked.");
            if (player != null) {
                player.stop();
            }
            playButton.setText("Play Video");
            playButton.setEnabled(true);
            stopButton.setEnabled(false);
            videoUrlField.setEnabled(true);
        });

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
