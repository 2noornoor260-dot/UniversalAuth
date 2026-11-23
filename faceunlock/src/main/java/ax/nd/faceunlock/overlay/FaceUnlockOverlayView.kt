package ax.nd.faceunlock.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.Choreographer
import android.view.View
import android.view.WindowInsets
import kotlin.math.PI
import kotlin.math.sin

/**
 * طبقة واحدة تُرسَم فيها إضاءة الظلام ثم الكبسولة فوقها (ترتيب الرسم = ترتيب الطبقات).
 *
 * لماذا طبقة واحدة؟ في النسخة السابقة كانت لوحة الإضاءة والكبسولة نافذتين منفصلتين، ولوحة الإضاءة تُضاف
 * لاحقاً فتعلو الكبسولة وتغطيها (أبيض 85% فوق كبسولة سوداء). هنا لا يمكن أن يحدث ذلك أبداً.
 * ولأن النافذة واحدة لا يُعدَّل WindowManager أثناء الحركة، فتبقى الحركة سلسة على كل معدّلات التحديث.
 *
 * التصميم أحادي اللون (أبيض #FFFFFF على أسود #000000) وملتصق بحافة الكاميرا (-2px خلف الإطار)،
 * ويأخذ موضع فتحة الكاميرا من القطع (DisplayCutout) تلقائياً مع إمكانية التحديد اليدوي.
 *
 * يجب استدعاء كل الدوال العامة من خيط الواجهة.
 */
class FaceUnlockOverlayView(context: Context) : View(context) {

    private class PathAdapter : PathSink {
        val path = Path()
        override fun moveTo(x: Float, y: Float) {
            path.moveTo(x, y)
        }

        override fun lineTo(x: Float, y: Float) {
            path.lineTo(x, y)
        }

        override fun quadTo(cx: Float, cy: Float, x: Float, y: Float) {
            path.quadTo(cx, cy, x, y)
        }

        override fun cubicTo(c1x: Float, c1y: Float, c2x: Float, c2y: Float, x: Float, y: Float) {
            path.cubicTo(c1x, c1y, c2x, c2y, x, y)
        }

        override fun close() {
            path.close()
        }

        fun reset(): PathAdapter {
            path.rewind()
            return this
        }
    }

    private enum class Phase { IDLE, SCANNING, REJECT, SUCCESS, RETRACT }

    private class Event(val at: Float, val action: () -> Unit)

    private val density = resources.displayMetrics.density

    // ── النوابض (كلها مُطبَّعة 0..1) ──
    private val appearIn = Spring(0.42f, 0.72f, 0f)   // ظهور بارتداد خفيف (~4%)
    private val appearOut = Spring(0.30f, 1.0f, 0f)   // اختفاء حرج بلا ارتداد: يعود سلساً إلى الكاميرا
    private val engage = Spring(0.28f, 1.0f, 0f)      // تشديد أقواس الوجه عند اكتشافه
    private val lockMorph = Spring(0.32f, 0.9f, 0f)   // وجه → قفل
    private val lift = Spring(0.20f, 0.55f, 0f)       // رفع ذراع القفل (ارتداد "نقرة" الإغلاق)
    private val swing = Spring(0.30f, 0.75f, 0f)      // تأرجح الفتح
    private val pulse = Spring(0.50f, 1.0f, 0f)       // نبضة النجاح
    private val fill = Spring(0.36f, 1.0f, 0f)        // إضاءة الشاشة
    private val glide = Spring(0.24f, 0.7f, 0f)       // انزلاق الرفض نحو الحافة

    // ── الحالة ──
    private var phase = Phase.IDLE
    private var retracting = false
    private var retractStartClock = 0f
    private var finishCallback: (() -> Unit)? = null
    private var capsuleEnabled = true
    private var edgeMode = "auto"
    private var surfaceRotation = 0
    private var cutoutRects: Array<IntArray> = emptyArray()
    private var anchor: CapsuleGeometry.Anchor? = null
    private var fillStrength = 0.9f

    private var clock = 0f
    private val events = ArrayList<Event>()
    private var sweepPhase = 0f
    private var dotsPhase = 0f
    private var blinkT = -1f
    private var blinkCountdown = 1.2f

    // ── الأدوات (تُنشأ مرة واحدة: لا تخصيص ذاكرة أثناء الرسم) ──
    private val geo = CapsuleGeometry.Layout()
    private val pillSink = PathAdapter()
    private val bracketsSink = PathAdapter()
    private val eyesSink = PathAdapter()
    private val noseSink = PathAdapter()
    private val mouthSink = PathAdapter()
    private val shackleSink = PathAdapter()
    private val bodySink = PathAdapter()
    private val keyDotSink = PathAdapter()
    private val keyStemSink = PathAdapter()

    private val pillFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 0.6f * density
    }
    private val glyphStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = GlyphPaths.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val keyholeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val keyholeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = 0.075f
        strokeCap = Paint.Cap.ROUND
    }
    private val sweepShader = LinearGradient(
        0f, -0.42f, 0f, 0.42f,
        intArrayOf(0x00FFFFFF, -0x1, 0x00FFFFFF),
        floatArrayOf(0f, 0.5f, 1f),
        Shader.TileMode.CLAMP
    )
    private val sweepMatrix = Matrix()
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = GlyphPaths.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        shader = sweepShader
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * density
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ── حلقة الإطارات ──
    private var running = false
    private var lastFrameNanos = 0L
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            val dt = if (lastFrameNanos == 0L) 1f / 60f
            else ((frameTimeNanos - lastFrameNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)
            lastFrameNanos = frameTimeNanos

            stepAll(dt)
            invalidate()

            val cb = takeFinishIfDone()
            if (cb != null) {
                running = false
                lastFrameNanos = 0L
                phase = Phase.IDLE
                cb.invoke()
                return
            }
            if (phase != Phase.IDLE) {
                Choreographer.getInstance().postFrameCallback(this)
            } else {
                running = false
                lastFrameNanos = 0L
            }
        }
    }

    // ───────────────────────── واجهة الاستخدام ─────────────────────────

    /** @param edgeMode auto | top | right | bottom | left */
    fun configure(edgeMode: String, capsuleEnabled: Boolean, surfaceRotation: Int, fillStrength: Float) {
        this.edgeMode = edgeMode
        this.capsuleEnabled = capsuleEnabled
        this.surfaceRotation = surfaceRotation
        this.fillStrength = fillStrength.coerceIn(0.5f, 1f)
        recomputeAnchor()
    }

    fun show() {
        if (phase == Phase.IDLE || phase == Phase.RETRACT) {
            retracting = false
            finishCallback = null
            phase = Phase.SCANNING
        }
        if (capsuleEnabled) appearIn.setTarget(1f)
        ensureRunning()
    }

    fun setEngaged(engaged: Boolean) {
        engage.setTarget(if (engaged) 1f else 0f)
        ensureRunning()
    }

    fun setFillLight(on: Boolean) {
        fill.setTarget(if (on) 1f else 0f)
        ensureRunning()
    }

    /** رفض: قفل يرفع ذراعه 28% ثم ينغلق بنقرة، مع انزلاق خفيف نحو الحافة (مرة كل دورتي رفض). */
    fun playReject() {
        if (!capsuleEnabled || phase != Phase.SCANNING || retracting) return
        phase = Phase.REJECT
        lockMorph.setTarget(1f)
        lift.setTarget(0.28f)
        glide.setTarget(1f)
        schedule(0.26f) {
            lift.setTarget(0f)
            glide.setTarget(0f)
        }
        schedule(0.85f) { lockMorph.setTarget(0f) }
        schedule(1.25f) { if (phase == Phase.REJECT) phase = Phase.SCANNING }
        ensureRunning()
    }

    /** نجاح: يتحول الوجه إلى قفل يُفتح بنبضة، ثم تعود الكبسولة إلى الكاميرا وتُستدعى onFinished. */
    fun playSuccess(onFinished: () -> Unit) {
        if (phase == Phase.SUCCESS || phase == Phase.RETRACT) return
        phase = Phase.SUCCESS
        engage.setTarget(0f)
        if (!capsuleEnabled) {
            fill.setTarget(0f)
            schedule(0.28f) { retract(onFinished) }
            ensureRunning()
            return
        }
        lockMorph.setTarget(1f)
        schedule(0.11f) {
            swing.setTarget(1f)
            pulse.setTarget(1f)
        }
        schedule(0.12f) { fill.setTarget(0f) }
        schedule(0.40f) { retract(onFinished) }
        ensureRunning()
    }

    /** يُنهي العرض: تعود الكبسولة إلى فتحة الكاميرا (حرج بلا ارتداد) وتخبو الإضاءة، ثم onFinished. */
    fun retract(onFinished: () -> Unit) {
        if (retracting) return
        retracting = true
        phase = Phase.RETRACT
        retractStartClock = clock
        appearOut.snapTo(appearIn.get())
        appearOut.kick(appearIn.velocity())
        appearOut.setTarget(0f)
        appearIn.snapTo(0f)
        fill.setTarget(0f)
        engage.setTarget(0f)
        finishCallback = onFinished
        ensureRunning()
    }

    fun stopAnimations() {
        running = false
        lastFrameNanos = 0L
        events.clear()
        finishCallback = null
        try {
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        } catch (e: Exception) {
        }
    }

    // ───────────────────────── الحركة ─────────────────────────

    private fun schedule(delaySec: Float, action: () -> Unit) {
        events.add(Event(clock + delaySec, action))
    }

    private fun ensureRunning() {
        if (running) return
        running = true
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun stepAll(dt: Float) {
        clock += dt

        if (events.isNotEmpty()) {
            val due = events.filter { it.at <= clock }
            if (due.isNotEmpty()) {
                events.removeAll(due)
                for (e in due) e.action()
            }
        }

        appearIn.step(dt)
        appearOut.step(dt)
        engage.step(dt)
        lockMorph.step(dt)
        lift.step(dt)
        swing.step(dt)
        pulse.step(dt)
        fill.step(dt)
        glide.step(dt)

        sweepPhase = (sweepPhase + dt / 1.25f) % 1f
        dotsPhase = (dotsPhase + dt / 1.1f) % 1f

        // رمشة عفوية كل ~2.5 ثانية تُبقي الوجه "حيّاً"
        if (blinkT < 0f) {
            blinkCountdown -= dt
            if (blinkCountdown <= 0f) blinkT = 0f
        } else {
            blinkT += dt
            if (blinkT > 0.16f) {
                blinkT = -1f
                blinkCountdown = 2.2f + ((clock * 7.31f) % 1.6f)
            }
        }
    }

    /** يُرجع دالة الإنهاء إذا اكتمل الاختفاء (أو انتهت مهلة الأمان)، وإلا null. */
    private fun takeFinishIfDone(): (() -> Unit)? {
        if (!retracting) return null
        val cb = finishCallback ?: return null
        val settled = appearOut.get() < 0.02f && fill.get() < 0.02f
        val timedOut = (clock - retractStartClock) > 0.9f
        if (settled || timedOut) {
            finishCallback = null
            return cb
        }
        return null
    }

    private fun appearValue(): Float = if (retracting) appearOut.get() else appearIn.get()

    // ───────────────────────── الإحداثيات والقطع ─────────────────────────

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        rootWindowInsets?.let { updateCutouts(it) }
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        stopAnimations()
        super.onDetachedFromWindow()
    }

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        updateCutouts(insets)
        return super.onApplyWindowInsets(insets)
    }

    private fun updateCutouts(insets: WindowInsets) {
        val rects = insets.displayCutout?.boundingRects
        cutoutRects = if (rects == null) emptyArray() else Array(rects.size) { i ->
            val r = rects[i]
            intArrayOf(r.left, r.top, r.right, r.bottom)
        }
        recomputeAnchor()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val maxDim = maxOf(w, h).toFloat()
        fillPaint.shader = RadialGradient(
            w * 0.5f, h * 0.42f, maxDim * 0.78f,
            intArrayOf(0xFFFFFCF6.toInt(), 0xFFFFF4E4.toInt(), 0xFFFFEBD0.toInt()),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        recomputeAnchor()
    }

    private fun recomputeAnchor() {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) {
            anchor = null
            return
        }
        val manual = CapsuleGeometry.parseEdge(edgeMode, -1)
        val fromCut = CapsuleGeometry.fromCutouts(cutoutRects, w, h)
        anchor = when {
            manual >= 0 -> if (fromCut != null && fromCut.edge == manual) fromCut else CapsuleGeometry.centered(manual, w, h)
            fromCut != null -> fromCut
            else -> CapsuleGeometry.centered(CapsuleGeometry.legacyEdgeForRotation(surfaceRotation), w, h)
        }
    }

    // ───────────────────────── الرسم ─────────────────────────

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return

        // 1) إضاءة الظلام (أسفل)
        val fa = fill.get().coerceIn(0f, 1f)
        if (fa > 0.003f) {
            fillPaint.alpha = (255f * fa * fillStrength).toInt().coerceIn(0, 255)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        }

        // 2) الكبسولة (أعلى دائماً)
        if (capsuleEnabled) drawCapsule(canvas)
    }

    private fun drawCapsule(canvas: Canvas) {
        val a = anchor ?: return
        val p = appearValue()
        if (p <= 0.002f) return

        CapsuleGeometry.layout(geo, p, a.holeLen, a.holeDepth, density)

        canvas.save()
        val theta: Float
        when (a.edge) {
            CapsuleGeometry.EDGE_RIGHT -> {
                canvas.translate(width.toFloat(), a.along)
                canvas.rotate(90f)
                theta = 90f
            }
            CapsuleGeometry.EDGE_BOTTOM -> {
                canvas.translate(a.along, height.toFloat())
                canvas.rotate(180f)
                theta = 180f
            }
            CapsuleGeometry.EDGE_LEFT -> {
                canvas.translate(0f, a.along)
                canvas.rotate(-90f)
                theta = -90f
            }
            else -> {
                canvas.translate(a.along, 0f)
                theta = 0f
            }
        }

        // انزلاق الرفض اللطيف نحو الحافة (3dp)
        canvas.translate(0f, -glide.get() * 3f * density)

        // الحبّة
        pillSink.reset()
        CapsuleGeometry.pill(pillSink, geo, CapsuleGeometry.BEZEL_OVERLAP_PX)
        val pa = geo.pillAlpha.coerceIn(0f, 1f)
        pillFill.alpha = (255f * pa).toInt()
        canvas.drawPath(pillSink.path, pillFill)
        rimPaint.alpha = (42f * pa).toInt()
        canvas.drawPath(pillSink.path, rimPaint)

        val contentAlpha = geo.contentAlpha.coerceIn(0f, 1f)
        if (contentAlpha > 0.01f) {
            val morph = lockMorph.get().coerceIn(0f, 1f)

            // الأيقونة (تُعاكَس دوران الحافة لتبقى قائمة)
            canvas.save()
            canvas.translate(geo.glyphU, geo.glyphV)
            canvas.rotate(-theta)
            canvas.scale(geo.glyphR, geo.glyphR)
            drawFaceGlyph(canvas, contentAlpha * (1f - morph), morph)
            drawLockGlyph(canvas, contentAlpha * morph, morph)
            canvas.restore()

            // مؤشر المسح الأيمن: ثلاث نقاط تنبض ثم تتلاشى عند الرفض/النجاح
            val dotsAlpha = contentAlpha * (1f - morph * 0.9f)
            if (dotsAlpha > 0.01f) {
                val r = 1.7f * density
                for (i in -1..1) {
                    val ph = 0.5f + 0.5f * sin((dotsPhase * 2f * PI - i * 1.1f)).toFloat()
                    dotPaint.alpha = (255f * dotsAlpha * (0.30f + 0.55f * ph)).toInt().coerceIn(0, 255)
                    canvas.drawCircle(geo.dotsU + i * 6.2f * density, geo.glyphV, r, dotPaint)
                }
            }

            // نبضة النجاح: حلقة تتسع وتخبو حول الرمز
            val pv = pulse.get()
            if (pv > 0.01f && pv < 0.995f) {
                ringPaint.alpha = (255f * 0.38f * (1f - pv)).toInt().coerceIn(0, 255)
                canvas.drawCircle(geo.glyphU, geo.glyphV, geo.glyphR * (1.05f + 1.5f * pv), ringPaint)
            }
        }
        canvas.restore()
    }

    private fun drawFaceGlyph(canvas: Canvas, alpha: Float, morph: Float) {
        if (alpha <= 0.01f) return
        val inset = 0.02f + 0.12f * engage.get().coerceIn(0f, 1f)
        val blink = if (blinkT < 0f) 0f else sin(PI * blinkT / 0.16f).toFloat()

        bracketsSink.reset()
        GlyphPaths.brackets(bracketsSink, inset)
        eyesSink.reset()
        GlyphPaths.eyes(eyesSink, blink)
        noseSink.reset()
        GlyphPaths.nose(noseSink)
        mouthSink.reset()
        GlyphPaths.mouth(mouthSink)

        canvas.save()
        val sc = 1f - 0.2f * morph
        canvas.scale(sc, sc)

        glyphStroke.alpha = (255f * alpha * 0.66f).toInt().coerceIn(0, 255)
        canvas.drawPath(bracketsSink.path, glyphStroke)
        canvas.drawPath(eyesSink.path, glyphStroke)
        canvas.drawPath(noseSink.path, glyphStroke)
        canvas.drawPath(mouthSink.path, glyphStroke)

        // شريط المسح: إبراز متدرّج يمرّ فوق الملامح (shader + مصفوفة محلية، بلا تخصيص ذاكرة)
        val sweepY = (sin(sweepPhase * 2f * PI) * 0.8).toFloat()
        sweepMatrix.setTranslate(0f, sweepY)
        sweepShader.setLocalMatrix(sweepMatrix)
        highlightPaint.alpha = (255f * alpha).toInt().coerceIn(0, 255)
        canvas.drawPath(bracketsSink.path, highlightPaint)
        canvas.drawPath(eyesSink.path, highlightPaint)
        canvas.drawPath(noseSink.path, highlightPaint)
        canvas.drawPath(mouthSink.path, highlightPaint)
        canvas.restore()
    }

    private fun drawLockGlyph(canvas: Canvas, alpha: Float, morph: Float) {
        if (alpha <= 0.01f) return
        val openAmt = swing.get()
        shackleSink.reset()
        GlyphPaths.lockShackle(shackleSink, lift.get() + 0.05f * openAmt, 30f * openAmt)
        bodySink.reset()
        GlyphPaths.lockBody(bodySink)
        keyDotSink.reset()
        GlyphPaths.keyholeDot(keyDotSink)
        keyStemSink.reset()
        GlyphPaths.keyholeStem(keyStemSink)

        canvas.save()
        val sc = 0.8f + 0.2f * morph
        canvas.scale(sc, sc)
        val a255 = (255f * alpha).toInt().coerceIn(0, 255)
        glyphStroke.alpha = a255
        glyphFill.alpha = a255
        canvas.drawPath(shackleSink.path, glyphStroke)
        canvas.drawPath(bodySink.path, glyphFill)
        keyholeFill.alpha = a255
        keyholeStroke.alpha = a255
        canvas.drawPath(keyDotSink.path, keyholeFill)
        canvas.drawPath(keyStemSink.path, keyholeStroke)
        canvas.restore()
    }
}
