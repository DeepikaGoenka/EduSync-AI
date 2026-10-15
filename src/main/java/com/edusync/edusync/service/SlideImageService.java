package com.edusync.edusync.service;

import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

@Service
public class SlideImageService {

    public String createSlideImage(
            String title,
            String[] points,
            int slideNumber) {

        try {
            int width = 1280;
            int height = 720;

            BufferedImage image = new BufferedImage(
                    width, height, BufferedImage.TYPE_INT_RGB);

            Graphics2D g = image.createGraphics();
            g.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);

            int left = 70;
            int right = 70;
            int maxWidth = width - left - right;
            int y = 65;

            // Title: wrap long titles automatically
            g.setColor(new Color(30, 60, 120));
            Font titleFont = new Font("Arial", Font.BOLD, 38);
            g.setFont(titleFont);

            List<String> titleLines = wrapText(
                    g, title == null ? "" : title, maxWidth);

            for (String line : titleLines) {
                g.drawString(line, left, y);
                y += 48;
            }

            y += 28;

            // Bullet points: wrap text to fit slide width
            g.setColor(Color.DARK_GRAY);
            int fontSize = 27;
            Font bodyFont = new Font("Arial", Font.PLAIN, fontSize);

            g.setFont(bodyFont);

            List<List<String>> wrappedPoints = new ArrayList<>();
            int availableHeight = 590 - y;
            int requiredHeight = 0;

            for (String point : points) {
                List<String> lines = wrapText(
                        g, point == null ? "" : point, maxWidth - 45);
                wrappedPoints.add(lines);
                requiredHeight += lines.size() * 36 + 16;
            }

            // Reduce font size if content is too tall
            while (requiredHeight > availableHeight && fontSize > 19) {
                fontSize -= 2;
                bodyFont = new Font("Arial", Font.PLAIN, fontSize);
                g.setFont(bodyFont);

                wrappedPoints.clear();
                requiredHeight = 0;

                for (String point : points) {
                    List<String> lines = wrapText(
                            g, point == null ? "" : point, maxWidth - 45);
                    wrappedPoints.add(lines);
                    requiredHeight += lines.size() * (fontSize + 9) + 12;
                }
            }

            int lineHeight = fontSize + 9;

            for (List<String> lines : wrappedPoints) {
                if (y + lineHeight > 625) {
                    break;
                }

                g.setColor(new Color(30, 60, 120));
                g.drawString("•", left, y);

                g.setColor(Color.DARK_GRAY);

                for (String line : lines) {
                    if (y + lineHeight > 625) {
                        break;
                    }

                    g.drawString(line, left + 35, y);
                    y += lineHeight;
                }

                y += 12;
            }

            // Footer
            g.setColor(Color.GRAY);
            g.setFont(new Font("Arial", Font.PLAIN, 18));
            g.drawString("EduSync AI", 70, 680);
            g.drawString("Slide " + slideNumber, 1100, 680);

            g.dispose();

            File directory = new File("generated-slides");
            if (!directory.exists()) {
                directory.mkdirs();
            }

            File output = new File(
                    directory, "slide-" + slideNumber + ".png");

            ImageIO.write(image, "png", output);

            return output.getAbsolutePath();

        } catch (Exception e) {
            throw new RuntimeException(
                    "Unable to create slide image.", e);
        }
    }

    private List<String> wrapText(
            Graphics2D g,
            String text,
            int maxWidth) {

        List<String> result = new ArrayList<>();

        if (text == null || text.isBlank()) {
            result.add("");
            return result;
        }

        StringBuilder current = new StringBuilder();

        for (String word : text.trim().split("\\s+")) {
            String candidate = current.length() == 0
                    ? word
                    : current + " " + word;

            if (g.getFontMetrics().stringWidth(candidate) <= maxWidth) {
                current = new StringBuilder(candidate);
            } else {
                if (current.length() > 0) {
                    result.add(current.toString());
                }
                current = new StringBuilder(word);
            }
        }

        if (current.length() > 0) {
            result.add(current.toString());
        }

        return result;
    }
}
