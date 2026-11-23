import ax.nd.faceunlock.overlay.EnrollRingGeometry;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** معاينة حلقة التسجيل بنفس معادلات EnrollRingView.onDraw (ألوان الوضع الفاتح/الداكن). */
public class RingPreview {
    static final float D = 3f;

    static float clamp01(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    static Color mix(Color a, Color b, float t) {
        float k = clamp01(t);
        return new Color((int) (a.getRed() + (b.getRed() - a.getRed()) * k),
                (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * k),
                (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * k));
    }

    static void draw(Graphics2D g, int size, boolean dark, int stage, float progress, float[] done, float visible, boolean error, String label) {
        Color bg = dark ? new Color(0, 0, 0) : new Color(242, 242, 247);
        g.setColor(bg);
        g.fillRect(0, 0, size, size + 40);
        Color inactive = dark ? Color.decode("#3A3A3C") : Color.decode("#C7C7CC");
        Color active = dark ? Color.decode("#0A84FF") : Color.decode("#007AFF");
        Color doneC = dark ? Color.decode("#30D158") : Color.decode("#34C759");
        Color err = dark ? Color.decode("#FF453A") : Color.decode("#FF3B30");

        // "الكاميرا" داخل الحلقة (دائرة داكنة تقريبية)
        float rOuter = size * 0.5f - 5f * D;
        float baseLen = 9f * D;
        g.setColor(dark ? new Color(28, 28, 30) : new Color(255, 255, 255));
        float camR = rOuter - baseLen * 1.9f;
        g.fill(new Ellipse2D.Float(size / 2f - camR, size / 2f - camR, camR * 2, camR * 2));
        g.setColor(dark ? new Color(255, 255, 255, 60) : new Color(0, 0, 0, 50));
        g.setFont(new Font("SansSerif", Font.PLAIN, 24));
        g.drawString(label, 12, size + 28);

        AffineTransform t0 = g.getTransform();
        g.translate(size / 2f, size / 2f);
        g.setStroke(new BasicStroke(3.2f * D, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        boolean allDone = stage >= 4;
        float[] e = new float[4];
        for (int s = 0; s < 4; s++) {
            boolean cur = (s == stage) && !allDone;
            for (int t = 0; t < EnrollRingGeometry.TICKS_PER_STAGE; t++) {
                float dist = EnrollRingGeometry.distanceFromCenter(t);
                float tickDone = clamp01(done[s] * 1.6f - dist * 0.6f);
                float filled = 0f;
                if (cur) {
                    filled = clamp01((progress - dist) * 6f + 0.5f);
                    if (progress < 0.02f) {
                        float ph = 0.5f + 0.5f * (float) Math.sin((0.25f - dist * 0.5f) * 2f * Math.PI);
                        filled = 0.28f * ph;
                    }
                }
                Color base = mix(inactive, (error && cur) ? err : active, filled);
                Color c = mix(base, doneC, tickDone);
                float alphaDim = 0.45f + 0.55f * visible;
                float alpha = (cur || tickDone > 0f) ? 1f : alphaDim;
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (255 * alpha)));
                float len = baseLen * (1f + 0.40f * filled + 0.55f * tickDone);
                EnrollRingGeometry.tickEnds(s, t, rOuter, len, e);
                g.draw(new Line2D.Float(e[0], e[1], e[2], e[3]));
            }
        }
        g.setTransform(t0);
    }

    public static void main(String[] a) throws Exception {
        System.setProperty("java.awt.headless", "true");
        int size = 330;
        Object[][] cases = {
            {"1 لا وجه", 0, 0f, new float[]{0, 0, 0, 0}, 0f, false},
            {"2 المرحلة 1: انتظار", 0, 0f, new float[]{0, 0, 0, 0}, 1f, false},
            {"3 المرحلة 1: 50%", 0, 0.5f, new float[]{0, 0, 0, 0}, 1f, false},
            {"4 المرحلة 1: 100%", 0, 1f, new float[]{0, 0, 0, 0}, 1f, false},
            {"5 اكتملت 1 / 2: 60%", 1, 0.6f, new float[]{1, 0, 0, 0}, 1f, false},
            {"6 اكتملت 1-2 / 3: 30%", 2, 0.3f, new float[]{1, 1, 0, 0}, 1f, false},
            {"7 اكتملت 3 / 4: 90%", 3, 0.9f, new float[]{1, 1, 1, 0}, 1f, false},
            {"8 اكتمل التسجيل", 4, 0f, new float[]{1, 1, 1, 1}, 1f, false},
            {"9 خطأ حفظ", 1, 0.5f, new float[]{1, 0, 0, 0}, 1f, true},
            {"10 موجة اكتمال (0.45)", 1, 0f, new float[]{0.45f, 0, 0, 0}, 1f, false},
        };
        int cols = 5, rows = 4;
        BufferedImage img = new BufferedImage(cols * size, rows * (size + 40), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        for (int i = 0; i < cases.length; i++) {
            Object[] c = cases[i];
            int col = i % cols, row = i / cols;
            for (int th = 0; th < 2; th++) {
                AffineTransform t0 = g.getTransform();
                g.translate(col * size, (row + th * 2) * (size + 40));
                g.setClip(0, 0, size, size + 40);
                draw(g, size, th == 1, (Integer) c[1], (Float) c[2], (float[]) c[3], (Float) c[4], (Boolean) c[5], (String) c[0]);
                g.setClip(null);
                g.setTransform(t0);
            }
        }
        g.dispose();
        ImageIO.write(img, "png", new File(a[0], "enroll_ring_states.png"));
        System.out.println("ok");
    }
}
