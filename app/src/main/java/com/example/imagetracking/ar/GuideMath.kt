package com.example.imagetracking.ar

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Что показывать поверх AR-сессии. Рендерер считает это каждый кадр, MainActivity только отображает.
 *
 * @param configuring     камера ещё не отслеживает мир → «идёт настройка, поиск якорей»
 * @param showFrame       показать рамку-видоискатель (ищем поверхность, объект ещё не найден)
 * @param arrowAngleDeg   угол стрелки к объекту вне кадра (0° — вправо, 90° — вверх), null — стрелка не нужна
 * @param focusedLabel    подпись найденного объекта, null — объекта в кадре нет, кнопки скрыты
 * @param switchLabel     текст кнопки «→ Объект №N» (переключиться на следующий объект в кадре),
 *                        null — в кадре меньше двух объектов, переключать некуда, кнопка скрыта
 */
data class GuideState(
    val configuring: Boolean = true,
    val showFrame: Boolean = false,
    val arrowAngleDeg: Int? = null,
    val focusedLabel: String? = null,
    val switchLabel: String? = null
)

/** Чистая математика подсказок (без Android и OpenGL — её можно проверить обычными тестами). */
object GuideMath {

    /** Объект считается «в кадре», если его проекция внутри этой доли половины экрана. */
    const val VIEW_LIMIT = 0.92f

    /**
     * Положение объекта относительно экрана.
     * @param ndcX,ndcY  координаты на экране от -1 до 1 (только если объект перед камерой)
     * @param angleDeg   направление, куда надо повернуть камеру: 0° — вправо, 90° — вверх, 0..359
     * @param axisCos    косинус угла между осью камеры и направлением на объект (1 — ровно по центру, -1 — позади)
     */
    class Proj(
        val inView: Boolean,
        val ndcX: Float,
        val ndcY: Float,
        val angleDeg: Int,
        val axisCos: Float
    )

    class Decision(val focusIndex: Int?, val arrowAngleDeg: Int?)

    /**
     * Проецирует мировую точку на экран. Матрицы column-major, как в OpenGL (android.opengl.Matrix):
     * view — из мира в пространство камеры (камера смотрит вдоль -Z, X вправо, Y вверх), proj — перспективная.
     */
    fun project(view: FloatArray, proj: FloatArray, wx: Float, wy: Float, wz: Float): Proj {
        val ex = view[0] * wx + view[4] * wy + view[8] * wz + view[12]
        val ey = view[1] * wx + view[5] * wy + view[9] * wz + view[13]
        val ez = view[2] * wx + view[6] * wy + view[10] * wz + view[14]

        val cx = proj[0] * ex + proj[4] * ey + proj[8] * ez + proj[12]
        val cy = proj[1] * ex + proj[5] * ey + proj[9] * ez + proj[13]
        val cw = proj[3] * ex + proj[7] * ey + proj[11] * ez + proj[15]

        val inFront = cw > 1e-4f
        val nx = if (inFront) cx / cw else 0f
        val ny = if (inFront) cy / cw else 0f
        val inView = inFront && abs(nx) <= VIEW_LIMIT && abs(ny) <= VIEW_LIMIT

        // направление «поверни камеру сюда» — по положению объекта в плоскости экрана (X вправо, Y вверх)
        var angle = 0
        if (abs(ex) + abs(ey) > 1e-3f) {
            val deg = Math.toDegrees(atan2(ey.toDouble(), ex.toDouble())).toInt()
            angle = ((deg % 360) + 360) % 360
        }
        val len = sqrt(ex * ex + ey * ey + ez * ez)
        val axisCos = if (len > 1e-6f) -ez / len else -1f
        return Proj(inView, nx, ny, angle, axisCos)
    }

    /**
     * Выбор по всем объектам:
     *  - уже выбранный объект ([keepIndex]) остаётся выбранным, пока он в кадре — иначе пользователь
     *    не смог бы вручную переключиться на другой объект, стоящий рядом;
     *  - иначе «найден» тот из видимых, что ближе к центру экрана; стрелка не нужна;
     *  - ни одного в кадре → стрелка к тому, до которого поворачивать камеру меньше всего.
     */
    fun decide(items: List<Proj>, keepIndex: Int? = null): Decision {
        if (keepIndex != null && keepIndex in items.indices && items[keepIndex].inView) {
            return Decision(keepIndex, null)
        }
        var focus = -1
        var best = Float.MAX_VALUE
        for (i in items.indices) {
            val p = items[i]
            if (!p.inView) continue
            val d = p.ndcX * p.ndcX + p.ndcY * p.ndcY
            if (d < best) {
                best = d
                focus = i
            }
        }
        if (focus >= 0) return Decision(focus, null)

        var arrow: Int? = null
        var bestCos = -2f
        for (p in items) {
            if (p.axisCos > bestCos) {
                bestCos = p.axisCos
                arrow = p.angleDeg
            }
        }
        return Decision(null, arrow)
    }

    /**
     * Следующий по списку объект в кадре после [current] (по кругу). Если в кадре только один объект,
     * вернётся он же; если в кадре никого нет — null. [current] = null — начать с первого видимого.
     */
    fun nextInView(items: List<Proj>, current: Int?): Int? {
        val n = items.size
        if (n == 0) return null
        val start = current ?: -1
        for (step in 1..n) {
            val i = (start + step + n) % n
            if (items[i].inView) return i
        }
        return null
    }

    /**
     * Точка на границе прямоугольника, в которую упирается луч из его центра под углом [angleDeg].
     * Сюда рисуется стрелка, чтобы она «сидела» у края экрана в нужную сторону.
     */
    fun edgePoint(angleDeg: Int, left: Float, top: Float, right: Float, bottom: Float): Pair<Float, Float> {
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val rad = Math.toRadians(angleDeg.toDouble())
        val dx = cos(rad).toFloat()
        val dy = -sin(rad).toFloat() // на экране ось Y направлена вниз
        val tx = if (abs(dx) < 1e-6f) Float.MAX_VALUE else (right - left) / 2f / abs(dx)
        val ty = if (abs(dy) < 1e-6f) Float.MAX_VALUE else (bottom - top) / 2f / abs(dy)
        val t = min(tx, ty)
        return Pair(cx + dx * t, cy + dy * t)
    }
}
