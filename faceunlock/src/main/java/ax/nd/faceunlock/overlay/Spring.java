package ax.nd.faceunlock.overlay;

/**
 * نابض فيزيائي مستقل عن معدّل الإطارات (60/90/120Hz تعطي النتيجة نفسها تقريباً).
 *
 * response: زمن الدورة الطبيعية بالثواني (أصغر = أسرع)
 * dampingRatio: 1 = حرج بلا ارتداد، أقل من 1 = ارتداد خفيف (0.7 ≈ 5%)
 *
 * التكامل شبه الضمني (semi-implicit Euler) بخطوات فرعية ≤ 1/240s، فيبقى مستقراً حتى لو تأخر إطار.
 * جميع الحركات في الواجهة تُحرَّك كقيم مُطبَّعة 0..1 ثم تُحوَّل لبكسلات وقت الرسم.
 */
public final class Spring {

    private static final float MAX_SUBSTEP = 1f / 240f;
    private static final float MAX_DT = 0.064f;

    private final float stiffness;
    private final float damping;
    private float value;
    private float velocity;
    private float target;

    public Spring(float response, float dampingRatio, float initial) {
        float omega = (float) (2.0 * Math.PI / Math.max(0.05f, response));
        this.stiffness = omega * omega;
        this.damping = 2f * dampingRatio * omega;
        this.value = initial;
        this.target = initial;
    }

    public void setTarget(float t) {
        target = t;
    }

    public float target() {
        return target;
    }

    public float get() {
        return value;
    }

    public float velocity() {
        return velocity;
    }

    /** قفز فوري بلا حركة (يُصفّر السرعة). */
    public void snapTo(float v) {
        value = v;
        target = v;
        velocity = 0f;
    }

    /** دفعة سرعة لحظية (لتأثير ارتداد/نبضة). */
    public void kick(float v) {
        velocity += v;
    }

    public void step(float dtSeconds) {
        if (!(dtSeconds > 0f)) return;
        float dt = Math.min(dtSeconds, MAX_DT);
        int n = (int) Math.ceil(dt / MAX_SUBSTEP);
        float h = dt / n;
        for (int i = 0; i < n; i++) {
            float acc = stiffness * (target - value) - damping * velocity;
            velocity += acc * h;
            value += velocity * h;
        }
    }

    public boolean isAtRest() {
        return Math.abs(target - value) < 0.0015f && Math.abs(velocity) < 0.01f;
    }
}
