import ax.nd.faceunlock.overlay.CapsuleGeometry;
import ax.nd.faceunlock.overlay.GlyphPaths;
import ax.nd.faceunlock.overlay.PathSink;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/** معاينة تقريبية على الحاسوب لرسم الكبسولة (Java2D). الرسم الفعلي على الجهاز بـ Canvas بنفس الأرقام. */
public class CapsulePreview {

    static final class P2D implements PathSink {
        final Path2D.Float p = new Path2D.Float();
        public void moveTo(float x, float y) { p.moveTo(x, y); }
        public void lineTo(float x, float y) { p.lineTo(x, y); }
        public void quadTo(float a, float b, float x, float y) { p.quadTo(a, b, x, y); }
        public void cubicTo(float a, float b, float c, float d, float x, float y) { p.curveTo(a, b, c, d, x, y); }
        public void close() { p.closePath(); }
    }

    static final class State {
        String label = "";
        float p = 1f;          // ظهور
        float inset = 0.02f;   // 0 واسعة .. 0.14 مشدودة
        float blink = 0f;
        float lock = 0f;       // 0 وجه .. 1 قفل
        float lift = 0f;
        float swing = 0f;
        float pulse = -1f;     // <0 بلا نبضة
        float sweep = Float.NaN; // موضع شريط المسح [-1..1] أو NaN
        float dots = 0.5f;
    }

    static final float DENSITY = 3.0f;

    static void drawTile(Graphics2D g, int x0, int y0, int W, int H, boolean bright, int edge, State s) {
        AffineTransform base = g.getTransform();
        g.translate(x0, y0);
        g.setClip(0, 0, W, H);

        // خلفية: ورق جدار داكن أو لوحة الإضاءة الدافئة
        if (bright) {
            g.setPaint(new RadialGradientPaint(new Point2D.Float(W / 2f, H * 0.45f), H * 0.75f,
                    new float[]{0f, 1f}, new Color[]{new Color(255, 252, 246), new Color(255, 242, 224)}));
        } else {
            g.setPaint(new java.awt.GradientPaint(0, 0, new Color(24, 30, 52), 0, H, new Color(8, 10, 20)));
        }
        g.fillRect(0, 0, W, H);

        // فتحة الكاميرا (ثقب)
        float holeLen = 14f * DENSITY, holeDepth = 36f * DENSITY;
        CapsuleGeometry.Anchor a = (edge == CapsuleGeometry.EDGE_TOP)
                ? new CapsuleGeometry.Anchor(edge, W / 2f, holeLen, holeDepth, true)
                : new CapsuleGeometry.Anchor(edge, H / 2f, holeLen, holeDepth, true);

        CapsuleGeometry.Layout L = new CapsuleGeometry.Layout();
        CapsuleGeometry.layout(L, s.p, a.holeLen, a.holeDepth, DENSITY);

        AffineTransform screen = g.getTransform();
        // إحداثيات محلية -> الشاشة
        double theta;
        switch (edge) {
            case CapsuleGeometry.EDGE_RIGHT: g.translate(W, a.along); theta = Math.PI / 2; break;
            case CapsuleGeometry.EDGE_BOTTOM: g.translate(a.along, H); theta = Math.PI; break;
            case CapsuleGeometry.EDGE_LEFT: g.translate(0, a.along); theta = -Math.PI / 2; break;
            default: g.translate(a.along, 0); theta = 0;
        }
        g.rotate(theta);

        // الثقب: دائرة سوداء في الإحداثيات المحلية (u=0، v≈ نصف العمق)
        g.setColor(Color.BLACK);
        float holeR = 7f * DENSITY;
        g.fill(new Ellipse2D.Float(-holeR, holeDepth * 0.5f - holeR - 3 * DENSITY, holeR * 2, holeR * 2));

        // الحبّة
        P2D pill = new P2D();
        CapsuleGeometry.pill(pill, L, CapsuleGeometry.BEZEL_OVERLAP_PX);
        g.setColor(new Color(0f, 0f, 0f, clamp(L.pillAlpha)));
        g.fill(pill.p);
        g.setColor(new Color(1f, 1f, 1f, clamp(0.165f * L.pillAlpha)));
        g.setStroke(new BasicStroke(1.1f * DENSITY * 0.5f));
        g.draw(pill.p);

        // الأيقونة (قائمة بالنسبة للشاشة: تعاكس دوران الحافة)
        float alpha = L.contentAlpha;
        AffineTransform local = g.getTransform();
        g.translate(L.glyphU, L.glyphV);
        g.rotate(-theta);
        g.scale(L.glyphR, L.glyphR);
        drawGlyph(g, s, alpha);
        g.setTransform(local);

        // مؤشر التقدّم الأيمن: ثلاث نقاط
        for (int i = -1; i <= 1; i++) {
            float ph = (float) (0.5 + 0.5 * Math.sin((s.dots * 6.28318) - i * 1.1));
            float aDot = alpha * (0.30f + 0.55f * ph) * (1f - s.lock * 0.9f);
            g.setColor(new Color(1f, 1f, 1f, clamp(aDot)));
            float r = 1.7f * DENSITY;
            g.fill(new Ellipse2D.Float(L.dotsU + i * 6.2f * DENSITY - r, L.glyphV - r, r * 2, r * 2));
        }

        // نبضة النجاح
        if (s.pulse >= 0f) {
            g.translate(L.glyphU, L.glyphV);
            g.setColor(new Color(1f, 1f, 1f, clamp(0.38f * (1f - s.pulse))));
            g.setStroke(new BasicStroke(1.2f * DENSITY * 0.6f));
            float rr = L.glyphR * (1.05f + 1.5f * s.pulse);
            g.draw(new Ellipse2D.Float(-rr, -rr, rr * 2, rr * 2));
        }

        g.setTransform(screen);
        g.setClip(null);
        // تسمية
        g.setFont(new Font("SansSerif", Font.PLAIN, 22));
        g.setColor(bright ? new Color(30, 30, 30, 200) : new Color(255, 255, 255, 190));
        g.drawString(s.label, 14, H - 14);
        g.setTransform(base);
    }

    static void drawGlyph(Graphics2D g, State s, float alpha) {
        float stroke = GlyphPaths.STROKE;
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        float faceA = alpha * (1f - s.lock);
        if (faceA > 0.01f) {
            AffineTransform t = g.getTransform();
            float sc = 1f - 0.2f * s.lock;
            g.scale(sc, sc);
            P2D br = new P2D(); GlyphPaths.brackets(br, s.inset);
            P2D ey = new P2D(); GlyphPaths.eyes(ey, s.blink);
            P2D no = new P2D(); GlyphPaths.nose(no);
            P2D mo = new P2D(); GlyphPaths.mouth(mo);
            g.setColor(new Color(1f, 1f, 1f, clamp(faceA * 0.66f)));
            g.draw(br.p); g.draw(ey.p); g.draw(no.p); g.draw(mo.p);
            if (!Float.isNaN(s.sweep)) {
                // إبراز بشريط متدرّج يمرّ فوق الملامح (نفس مبدأ shader + local matrix على الجهاز)
                LinearGradientPaint lg = new LinearGradientPaint(new Point2D.Float(0, s.sweep - 0.42f),
                        new Point2D.Float(0, s.sweep + 0.42f), new float[]{0f, 0.5f, 1f},
                        new Color[]{new Color(1f, 1f, 1f, 0f), new Color(1f, 1f, 1f, clamp(faceA)), new Color(1f, 1f, 1f, 0f)});
                g.setPaint(lg);
                g.draw(br.p); g.draw(ey.p); g.draw(no.p); g.draw(mo.p);
            }
            g.setTransform(t);
        }

        float lockA = alpha * s.lock;
        if (lockA > 0.01f) {
            AffineTransform t = g.getTransform();
            float sc = 0.8f + 0.2f * s.lock;
            g.scale(sc, sc);
            g.setColor(new Color(1f, 1f, 1f, clamp(lockA)));
            P2D sh = new P2D(); GlyphPaths.lockShackle(sh, s.lift, s.swing);
            g.draw(sh.p);
            P2D body = new P2D(); GlyphPaths.lockBody(body);
            g.fill(body.p);
            g.setColor(new Color(0f, 0f, 0f, clamp(lockA)));
            P2D dot = new P2D(); GlyphPaths.keyholeDot(dot);
            g.fill(dot.p);
            g.setStroke(new BasicStroke(0.075f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            P2D stem = new P2D(); GlyphPaths.keyholeStem(stem);
            g.draw(stem.p);
            g.setTransform(t);
        }
    }

    static float clamp(float v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }

    static State st(String label, float p, float inset, float blink, float lock, float lift, float swing, float pulse, float sweep) {
        State s = new State();
        s.label = label; s.p = p; s.inset = inset; s.blink = blink; s.lock = lock; s.lift = lift; s.swing = swing; s.pulse = pulse; s.sweep = sweep;
        return s;
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        String outDir = args.length > 0 ? args[0] : ".";

        State[] seq = new State[]{
                st("1 كاميرا فقط", 0f, 0.02f, 0, 0, 0, 0, -1, Float.NaN),
                st("2 تنمو", 0.55f, 0.02f, 0, 0, 0, 0, -1, Float.NaN),
                st("3 مسح", 1f, 0.02f, 0, 0, 0, 0, -1, -0.5f),
                st("4 وجه مُكتشف", 1f, 0.12f, 0, 0, 0, 0, -1, 0.35f),
                st("5 رمشة", 1f, 0.12f, 1f, 0, 0, 0, -1, Float.NaN),
                st("6 تحوّل لقفل", 1f, 0.12f, 0, 0.5f, 0, 0, -1, Float.NaN),
                st("7 رفض: قفل", 1f, 0.02f, 0, 1f, 0, 0, -1, Float.NaN),
                st("8 رفض: رفع 28%", 1f, 0.02f, 0, 1f, 0.28f, 0, -1, Float.NaN),
                st("9 فتح", 1f, 0.02f, 0, 1f, 0.05f, 30f, 0.25f, Float.NaN),
                st("10 نبضة", 1f, 0.02f, 0, 1f, 0.05f, 30f, 0.75f, Float.NaN),
        };

        int tw = 330 * 2 / 2 + 130, th = 230;
        int cols = 5, rows = 4;           // صفان للداكن وصفان للإضاءة الفاتحة
        BufferedImage img = new BufferedImage(cols * tw, rows * th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        for (int i = 0; i < seq.length; i++) {
            int c = i % cols, r = i / cols;
            drawTile(g, c * tw, r * th, tw, th, false, CapsuleGeometry.EDGE_TOP, seq[i]);
            drawTile(g, c * tw, (r + 2) * th, tw, th, true, CapsuleGeometry.EDGE_TOP, seq[i]);
        }
        g.dispose();
        ImageIO.write(img, "png", new File(outDir, "capsule_states_top.png"));

        // حواف أخرى: اليمين (التعيين القديم عند ROTATION_0) واليسار والأسفل
        int[] edges = {CapsuleGeometry.EDGE_RIGHT, CapsuleGeometry.EDGE_LEFT, CapsuleGeometry.EDGE_BOTTOM};
        String[] en = {"RIGHT", "LEFT", "BOTTOM"};
        BufferedImage img2 = new BufferedImage(3 * 520, 330, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = img2.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        for (int i = 0; i < 3; i++) {
            State s = st(en[i] + " - مسح", 1f, 0.12f, 0, 0, 0, 0, -1, 0.2f);
            drawTile(g2, i * 520, 0, 520, 330, i == 1, edges[i], s);
        }
        g2.dispose();
        ImageIO.write(img2, "png", new File(outDir, "capsule_edges.png"));

        // قفل مكبّر لفحص جودة الرسم
        BufferedImage img3 = new BufferedImage(5 * 220, 240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g3 = img3.createGraphics();
        g3.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g3.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g3.setColor(Color.BLACK);
        g3.fillRect(0, 0, img3.getWidth(), img3.getHeight());
        State[] zoom = {
                st("", 1f, 0.02f, 0, 0, 0, 0, -1, Float.NaN),
                st("", 1f, 0.14f, 0, 0, 0, 0, -1, 0.1f),
                st("", 1f, 0.02f, 0, 1f, 0, 0, -1, Float.NaN),
                st("", 1f, 0.02f, 0, 1f, 0.28f, 0, -1, Float.NaN),
                st("", 1f, 0.02f, 0, 1f, 0.05f, 30f, -1, Float.NaN)};
        for (int i = 0; i < 5; i++) {
            AffineTransform t0 = g3.getTransform();
            g3.translate(i * 220 + 110, 120);
            g3.scale(80, 80);
            drawGlyph(g3, zoom[i], 1f);
            g3.setTransform(t0);
        }
        g3.dispose();
        ImageIO.write(img3, "png", new File(outDir, "glyph_zoom.png"));
        System.out.println("preview written to " + outDir);
    }
}
