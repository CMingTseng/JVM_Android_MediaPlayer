package idv.neo.ffmpeg.media.player.desktop;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.image.BufferedImage;

import javax.swing.JPanel;

public  class PlayerSurface extends JPanel {
    // ... (same as your existing VideoDisplayPanel)
    private BufferedImage currentImage;
    private final Object imageLock = new Object();

    public PlayerSurface() {
        setBackground(Color.BLACK);
        setDoubleBuffered(true); // Good practice for custom painting
    }

    public void updateImage(BufferedImage newImage) {
        if (newImage == null) return;
        // Create a new image or copy to avoid modification issues if the passed image is reused
        BufferedImage imageToPaint;
        // Ensure thread-safe access if newImage properties are read here
        // For simplicity, directly assigning, but defensive copy is safer if newImage is mutable externally.
        // However, FrameToBufferedImageConverter should return a new instance.
        synchronized (imageLock) {
            currentImage = newImage; // Assuming newImage is a fresh instance
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        BufferedImage imageToRender = null;
        synchronized (imageLock) {
            if (currentImage != null) {
                imageToRender = currentImage; // Get reference under lock
            }
        }
        if (imageToRender != null) {
            // Basic centering. Consider scaling options.
            int panelWidth = getWidth();
            int panelHeight = getHeight();
            int imgWidth = imageToRender.getWidth();
            int imgHeight = imageToRender.getHeight();

            // Simple scaling to fit, maintaining aspect ratio (optional)
            // double scale = Math.min((double)panelWidth / imgWidth, (double)panelHeight / imgHeight);
            // int scaledWidth = (int)(imgWidth * scale);
            // int scaledHeight = (int)(imgHeight * scale);
            // int x = (panelWidth - scaledWidth) / 2;
            // int y = (panelHeight - scaledHeight) / 2;
            // g.drawImage(imageToRender, x, y, scaledWidth, scaledHeight, this);

            // No scaling, just center
            int x = (panelWidth - imgWidth) / 2;
            int y = (panelHeight - imgHeight) / 2;
            g.drawImage(imageToRender, x, y, this);
        }
    }
}
