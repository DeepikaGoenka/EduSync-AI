package com.edusync.edusync.service;

import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Service;

@Service
public class LectureSlideRenderer {

    private static final int W = 1280;
    private static final int H = 720;
    private static final Color ACCENT = new Color(45, 212, 191);
    private static final Color BG_TOP = new Color(12, 18, 38);
    private static final Color BG_BOTTOM = new Color(30, 41, 90);
    private static final String FONT = "Segoe UI";

    // Frames: 0 = title only, 1..n = bullets revealed one by one, last = everything.
    public List<File> renderFrames(LectureSlideSpec s, int slideNo, int total, File dir)
            throws IOException {

        List<File> files = new ArrayList<>();
        int n = s.bullets().size();

        for (int k = 0; k <= n; k++) {
            File f = new File(dir, "frame-" + slideNo + "-" + k + ".png");
            ImageIO.write(draw(s, slideNo, total, k, false), "png", f);
            files.add(f);
        }

        File last = new File(dir, "frame-" + slideNo + "-final.png");
        ImageIO.write(draw(s, slideNo, total, n, true), "png", last);
        files.add(last);

        return files;
    }

    private BufferedImage draw(LectureSlideSpec s, int slideNo, int total,
                               int visible, boolean finalFrame) {

        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

            // Background
            g.setPaint(new GradientPaint(0, 0, BG_TOP, W, H, BG_BOTTOM));
            g.fillRect(0, 0, W, H);
            g.setColor(new Color(255, 255, 255, 12));
            g.fillOval(W - 380, -160, 520, 520);
            g.fillOval(-200, H - 260, 420, 420);
            g.setColor(ACCENT);
            g.fillRect(0, 0, W, 8);

            // Header tag
            g.setFont(new Font(FONT, Font.BOLD, 16));
            g.setColor(ACCENT);
            g.drawString("EDUSYNC AI  |  LECTURE", 70, 52);

            // Title: shrink until it fits on one line (down to 34pt), otherwise two lines.
            int tsz = 46;
            Font titleFont;
            List<String> titleLines;
            while (true) {
                titleFont = new Font(FONT, Font.BOLD, tsz);
                titleLines = wrap(g.getFontMetrics(titleFont), s.title(), W - 140);
                if (titleLines.size() <= 1 || tsz <= 34) {
                    break;
                }
                tsz -= 4;
            }
            if (titleLines.size() > 2) {
                titleLines = titleLines.subList(0, 2);
            }
            int step = tsz + 10;
            int ty = 104;
            g.setFont(titleFont);
            g.setColor(Color.WHITE);
            for (String line : titleLines) {
                g.drawString(line, 70, ty);
                ty += step;
            }
            g.setColor(ACCENT);
            g.fillRoundRect(70, ty - step + 22, 110, 6, 6, 6);

            int bodyTop = ty - step + 46;
            int bodyBottom = 540;
            boolean hasFlow = s.flow() != null && s.flow().size() >= 2;
            int left = 70;
            int colW = hasFlow ? 610 : W - 140;
            int avail = bodyBottom - bodyTop;

            // Layout is computed for ALL bullets so nothing jumps between frames.
            // Font shrinks from 30pt down to 18pt until every bullet fits.
            int fs = 30;
            int lineH = fs + 7;
            int gap = 16;
            List<List<String>> wrapped = new ArrayList<>();

            while (true) {
                FontMetrics fm = g.getFontMetrics(new Font(FONT, Font.PLAIN, fs));
                lineH = fs + 7;
                wrapped = new ArrayList<>();
                int needed = 0;
                for (String b : s.bullets()) {
                    List<String> lines = wrap(fm, b, colW - 40);
                    wrapped.add(lines);
                    needed += lines.size() * lineH + gap;
                }
                if (needed <= avail || fs <= 18) {
                    break;
                }
                fs -= 1;
            }

            Font bodyFont = new Font(FONT, Font.PLAIN, fs);
            int y = bodyTop + fs;

            for (int i = 0; i < wrapped.size(); i++) {
                List<String> lines = wrapped.get(i);
                int blockH = lines.size() * lineH;

                if (i < visible) {
                    boolean current = !finalFrame && i == visible - 1;

                    if (current) {
                        g.setColor(new Color(255, 255, 255, 24));
                        g.fill(new RoundRectangle2D.Double(
                                left - 18, y - (int) (fs * 0.95), colW + 10, blockH + 6, 18, 18));
                    }

                    g.setColor(current ? ACCENT : new Color(45, 212, 191, 140));
                    g.fillOval(left, (int) (y - fs * 0.35 - 7), 14, 14);

                    g.setFont(bodyFont);
                    g.setColor(current ? Color.WHITE : new Color(205, 214, 238));
                    int ly = y;
                    for (String line : lines) {
                        g.drawString(line, left + 34, ly);
                        ly += lineH;
                    }
                }
                y += blockH + gap;
            }

            // Flow diagram on the right, built step by step.
            if (hasFlow) {
                int m = s.flow().size();
                int n = Math.max(1, s.bullets().size());
                int shown = finalFrame ? m
                        : (visible == 0 ? 0 : (int) Math.ceil(visible * (double) m / n));
                drawFlow(g, s.flow(), 730, bodyTop, 480, bodyBottom - bodyTop, shown, finalFrame);
            }

            // Key idea card on the final frame.
            if (finalFrame && s.takeaway() != null && !s.takeaway().isBlank()) {
                int cy = 555;
                int ch = 104;
                g.setColor(new Color(45, 212, 191, 34));
                g.fill(new RoundRectangle2D.Double(70, cy, W - 140, ch, 22, 22));
                g.setColor(ACCENT);
                g.fillRoundRect(70, cy, 8, ch, 8, 8);

                g.setFont(new Font(FONT, Font.BOLD, 15));
                g.drawString("KEY IDEA", 100, cy + 26);

                g.setFont(new Font(FONT, Font.PLAIN, 24));
                FontMetrics km = g.getFontMetrics();
                g.setColor(Color.WHITE);
                List<String> tl = wrap(km, s.takeaway(), W - 140 - 60);
                int ky = cy + 58;
                for (int i = 0; i < Math.min(2, tl.size()); i++) {
                    g.drawString(tl.get(i), 100, ky);
                    ky += 30;
                }
            }

            // Footer and progress bar
            g.setFont(new Font(FONT, Font.PLAIN, 16));
            g.setColor(new Color(150, 160, 190));
            g.drawString("EduSync AI", 70, 696);
            String right = "Slide " + slideNo + " of " + total;
            g.drawString(right, W - 70 - g.getFontMetrics().stringWidth(right), 696);

            g.setColor(new Color(255, 255, 255, 28));
            g.fillRect(0, H - 8, W, 8);
            g.setColor(ACCENT);
            g.fillRect(0, H - 8, (int) (W * (slideNo / (double) total)), 8);

        } finally {
            g.dispose();
        }

        return img;
    }

    private void drawFlow(Graphics2D g, List<String> flow, int x, int top, int w, int h,
                          int shown, boolean finalFrame) {

        int m = Math.min(flow.size(), 5);
        int arrowGap = 18;
        int boxH = Math.min(78, (h - (m - 1) * arrowGap) / m);

        g.setFont(new Font(FONT, Font.BOLD, 20));
        FontMetrics fm = g.getFontMetrics();

        for (int i = 0; i < m && i < shown; i++) {
            int by = top + i * (boxH + arrowGap);
            boolean latest = !finalFrame && i == shown - 1;

            g.setColor(latest ? new Color(45, 212, 191, 70) : new Color(255, 255, 255, 26));
            g.fill(new RoundRectangle2D.Double(x, by, w, boxH, 20, 20));

            g.setColor(ACCENT);
            g.setStroke(new BasicStroke(2f));
            g.draw(new RoundRectangle2D.Double(x, by, w, boxH, 20, 20));
            g.setStroke(new BasicStroke(1f));

            g.fillOval(x + 14, by + boxH / 2 - 14, 28, 28);
            g.setColor(BG_TOP);
            String num = String.valueOf(i + 1);
            g.drawString(num, x + 28 - fm.stringWidth(num) / 2, by + boxH / 2 + 7);

            g.setColor(Color.WHITE);
            List<String> lines = wrap(fm, flow.get(i), w - 74);
            int lh = 24;
            int textH = Math.min(lines.size(), 2) * lh;
            int baseline = by + (boxH - textH) / 2 + 18;
            for (int j = 0; j < Math.min(lines.size(), 2); j++) {
                g.drawString(lines.get(j), x + 54, baseline);
                baseline += lh;
            }

            if (i < m - 1 && i + 1 < shown) {
                int cx = x + w / 2;
                int y1 = by + boxH + 3;
                int y2 = by + boxH + arrowGap - 2;
                g.setColor(ACCENT);
                g.fillPolygon(new int[] { cx - 8, cx + 8, cx }, new int[] { y1, y1, y2 }, 3);
            }
        }
    }

    private List<String> wrap(FontMetrics fm, String text, int maxWidth) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();

        if (text != null && !text.isBlank()) {
            for (String word : text.trim().split("\\s+")) {
                String cand = cur.length() == 0 ? word : cur + " " + word;
                if (cur.length() == 0 || fm.stringWidth(cand) <= maxWidth) {
                    cur = new StringBuilder(cand);
                } else {
                    lines.add(cur.toString());
                    cur = new StringBuilder(word);
                }
            }
        }

        if (cur.length() > 0) {
            lines.add(cur.toString());
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }
}
