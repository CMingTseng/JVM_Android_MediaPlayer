package idv.neo.ffmpeg.media.player.desktop;

import javax.swing.*;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

public class Application {

    private static JTextField videoUrlField; // UI component for URL input
    private static SwingVideoPlayer player; // Player instance
    private static JButton playButton;       // Play button

    public static void main(String[] args) {
        SwingUtilities.invokeLater(Application::createAndShowGUI);
    }

    private static void createAndShowGUI() {
        JFrame frame = new JFrame("Swing Video Player");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(600, 400)); // Set a minimum size

        // --- Video Display Panel ---
        final PlayerSurface videoPanel = new PlayerSurface();
        videoPanel.setPreferredSize(new Dimension(640, 480));
        frame.getContentPane().add(videoPanel, BorderLayout.CENTER);

        // Initialize the player
        player = new SwingVideoPlayer(videoPanel);

        // --- Control Panel ---
        JPanel controlPanel = new JPanel(new BorderLayout()); // Use BorderLayout for more structure

        // Input Panel for URL
        JPanel inputPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        inputPanel.add(new JLabel("Video URL:"));
        videoUrlField = new JTextField(40);
//        videoUrlField.setText("https://github.com/rambod-rahmani/ffmpeg-video-player/raw/refs/heads/master/Iron_Man-Trailer_HD.mp4"); // Default URL
        videoUrlField.setText("https://video-tx-aws.langhongtw.com/live/6185039Y.flv"); // Default URL
        inputPanel.add(videoUrlField);

        controlPanel.add(inputPanel, BorderLayout.NORTH); // Add input panel to the top of control panel

        // Button Panel for Play button
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER));
        playButton = new JButton("Play Video");
        buttonPanel.add(playButton);

        controlPanel.add(buttonPanel, BorderLayout.CENTER); // Add button panel to the center of control panel

        frame.getContentPane().add(controlPanel, BorderLayout.SOUTH); // Add control panel to the bottom of the frame

        // --- Action Listener for Play Button ---
        playButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String videoUrl = videoUrlField.getText().trim(); // Get URL and trim whitespace

                if (videoUrl.isEmpty()) {
                    JOptionPane.showMessageDialog(frame,
                            "Please enter a video URL.",
                            "URL Missing",
                            JOptionPane.WARNING_MESSAGE);
                    return; // Do nothing if URL is empty
                }

                // It's good practice to disable the button while attempting to start
                playButton.setEnabled(false);
                videoUrlField.setEnabled(false); // Optionally disable URL field during playback attempt

                // Start streaming in a new thread so the button action listener returns quickly
                new Thread(() -> {
                    try {
                        player.startStreaming(videoUrl);
                    } catch (Exception ex) {
                        // Log error or show a message to the user
                        System.err.println("Error starting stream: " + ex.getMessage());
                        ex.printStackTrace();
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(frame,
                                    "Error starting stream: " + ex.getMessage(),
                                    "Playback Error",
                                    JOptionPane.ERROR_MESSAGE);
                        });
                    } finally {
                        // Re-enable UI components on the EDT after streaming attempt (success or fail)
                        SwingUtilities.invokeLater(() -> {
                            playButton.setEnabled(true);
                            videoUrlField.setEnabled(true);
                        });
                    }
                }).start();
            }
        });

        // Display the window
        frame.pack(); // Adjust window size to fit components
        frame.setLocationRelativeTo(null); // Center the window
        frame.setVisible(true);
    }
}
