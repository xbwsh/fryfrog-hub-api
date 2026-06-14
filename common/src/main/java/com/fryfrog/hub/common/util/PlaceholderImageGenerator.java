package com.fryfrog.hub.common.util;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

public class PlaceholderImageGenerator {

    private static final Color[] COLORS = {
            new Color(0x4A90A4),
            new Color(0x6B5B95),
            new Color(0x88B04B),
            new Color(0xF7CAC9),
            new Color(0xF0E68C),
            new Color(0xDD4124),
            new Color(0x45B8AC),
            new Color(0xE15D44),
            new Color(0x7FCDCD),
            new Color(0xBC243C)
    };

    public static byte[] generate(String title, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Color bgColor = getColorForTitle(title);
        g2d.setColor(bgColor);
        g2d.fillRect(0, 0, width, height);

        String displayText = getDisplayText(title);
        int fontSize = Math.min(width, height) / 6;
        g2d.setFont(new Font("SansSerif", Font.BOLD, fontSize));

        FontMetrics fm = g2d.getFontMetrics();
        int textWidth = fm.stringWidth(displayText);
        int textHeight = fm.getHeight();

        int x = (width - textWidth) / 2;
        int y = (height - textHeight) / 2 + fm.getAscent();

        g2d.setColor(Color.WHITE);
        g2d.drawString(displayText, x, y);

        g2d.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", baos);
        return baos.toByteArray();
    }

    private static Color getColorForTitle(String title) {
        if (title == null || title.isBlank()) {
            return COLORS[0];
        }
        int hash = title.hashCode();
        return COLORS[Math.abs(hash) % COLORS.length];
    }

    private static String getDisplayText(String title) {
        if (title == null || title.isBlank()) {
            return "?";
        }

        String trimmed = title.trim();
        if (trimmed.length() <= 4) {
            return trimmed;
        }

        return trimmed.substring(0, 4);
    }
}
