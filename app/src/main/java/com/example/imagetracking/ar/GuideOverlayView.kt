package com.example.imagetracking.ar

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * Вспомогательная графика поверх AR-сессии (рисуется обычным Canvas, без картинок):
 *  - рамка-видоискатель по центру: «наведите сюда поверхность»;
 *  - стрелка у края экрана, указывающая, куда повернуть камеру, чтобы увидеть объект вне кадра.
 * Касания пропускает: view не кликабельна, поэтому тапы доходят до AR-поверхности.
 */
class GuideOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val dp = resources.displayMetrics.density
    private var showFrame = false
    private var arrowAngle: Int? = null

    private val frameShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(150, 0, 0, 0)
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 7 * dp
    }
    private val frameLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 4 * dp
    }
    private val arrowFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 214, 10)
    }
    private val arrowEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3 * dp
    }
    private val arrowPath = Path()

    /** [frame] — показать рамку; [angleDeg] — угол стрелки (0° вправо, 90° вверх) или null, если стрелка не нужна. */
    fun setGuide(frame: Boolean, angleDeg: Int?) {
        if (frame == showFrame && angleDeg == arrowAngle) return
        showFrame = frame
        arrowAngle = angleDeg
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (showFrame) drawFrame(canvas)
        val angle = arrowAngle
        if (angle != null) {
            drawArrow(canvas, angle)
            postInvalidateOnAnimation() // пока стрелка видна — пульсирует
        }
    }

    /** Рамка из четырёх уголков чуть выше центра экрана. */
    private fun drawFrame(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val side = min(w, h) * 0.62f
        val l = (w - side) / 2f
        val t = (h - side) / 2f - h * 0.04f
        val r = l + side
        val b = t + side
        val c = side * 0.2f
        for (paint in arrayOf(frameShadow, frameLine)) {
            canvas.drawLine(l, t, l + c, t, paint)
            canvas.drawLine(l, t, l, t + c, paint)
            canvas.drawLine(r, t, r - c, t, paint)
            canvas.drawLine(r, t, r, t + c, paint)
            canvas.drawLine(l, b, l + c, b, paint)
            canvas.drawLine(l, b, l, b - c, paint)
            canvas.drawLine(r, b, r - c, b, paint)
            canvas.drawLine(r, b, r, b - c, paint)
        }
    }

    /** Стрелка у края экрана (в зоне между строкой статуса сверху и кнопками снизу). */
    private fun drawArrow(canvas: Canvas, angleDeg: Int) {
        val w = width.toFloat()
        val h = height.toFloat()
        val left = ARROW_SIDE_INSET_DP * dp
        val right = w - left
        val top = ARROW_TOP_INSET_DP * dp
        val bottom = h - ARROW_BOTTOM_INSET_DP * dp
        if (right <= left || bottom <= top) return

        val (px, py) = GuideMath.edgePoint(angleDeg, left, top, right, bottom)
        val pulse = 1f + 0.12f * sin(System.currentTimeMillis() / 180.0).toFloat()
        val s = 28 * dp * pulse

        // стрелка «смотрит» вправо; поворачиваем на -угол, потому что на экране ось Y направлена вниз
        arrowPath.reset()
        arrowPath.moveTo(s, 0f)
        arrowPath.lineTo(-s * 0.7f, -s * 0.85f)
        arrowPath.lineTo(-s * 0.25f, 0f)
        arrowPath.lineTo(-s * 0.7f, s * 0.85f)
        arrowPath.close()

        canvas.save()
        canvas.translate(px, py)
        canvas.rotate(-angleDeg.toFloat())
        canvas.drawPath(arrowPath, arrowFill)
        canvas.drawPath(arrowPath, arrowEdge)
        canvas.restore()
    }

    private companion object {
        const val ARROW_SIDE_INSET_DP = 36f
        const val ARROW_TOP_INSET_DP = 230f
        const val ARROW_BOTTOM_INSET_DP = 170f
    }
}
