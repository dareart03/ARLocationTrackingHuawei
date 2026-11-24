package com.example.imagetracking.ar

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.view.MotionEvent
import com.example.imagetracking.CameraBackground
import com.example.imagetracking.Gl
import com.example.imagetracking.ObjLoader
import com.example.imagetracking.ObjModel
import com.huawei.hiar.ARAnchor
import com.huawei.hiar.ARFrame
import com.huawei.hiar.ARPlane
import com.huawei.hiar.ARSession
import com.huawei.hiar.ARTrackable
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.hypot

/**
 * AR-рендерер: World Tracking (поиск плоскостей) + размещение объектов по тапу.
 *
 * Про GPS ничего не знает — управляется только состоянием [PlacementController]:
 *  - вне зоны (IDLE) плоскости не рисуются, а тап блокируется;
 *  - в зоне плоскости подсвечиваются, тап по плоскости создаёт ARAnchor с контентом активной метки;
 *  - уже поставленные объекты ([placed]) при выходе из зоны НЕ удаляются, они продолжают рисоваться.
 *
 * В отличие от Image Tracking здесь позы заданы в мировых координатах, поэтому матрица вида —
 * настоящая матрица камеры (camera.getViewMatrix), а не единичная.
 * Вся логика — в GL-потоке; тапы приходят через очередь.
 */
class PlacementRenderer(
    private val context: Context,
    private val controller: PlacementController,
    private val callbacks: Callbacks
) : GLSurfaceView.Renderer {

    interface Callbacks {
        /** Короткое сообщение (Toast). Вызывается из GL-потока. */
        fun onMessage(text: String)

        /** Изменилось число поставленных объектов. Вызывается из GL-потока. */
        fun onPlacedCountChanged(count: Int)

        /** Камера потеряла/восстановила отслеживание. Вызывается из GL-потока. */
        fun onTrackingChanged(tracking: Boolean)

        /** Изменилось, что показывать поверх AR: сообщение о настройке, рамка, стрелка, кнопки. Из GL-потока. */
        fun onGuideChanged(state: GuideState)
    }

    /**
     * Поставленный объект: якорь в мире + то, что пользователь с ним сделал кнопками.
     * [number] — порядковый номер объекта («Объект №3»), по нему его узнаёт пользователь; номера не переиспользуются.
     * Размер, поворот и цвет у каждого объекта свои.
     */
    private class Placed(val anchor: ARAnchor, val contentId: String, val name: String, val number: Int) {
        var scale = 1f
        var yawDeg = 0f
        var colorIdx = 0
    }

    @Volatile var session: ARSession? = null
    @Volatile var displayRotation = 0
    @Volatile var touchSlop = 24f
    @Volatile private var geometryDirty = true

    private val queue = ConcurrentLinkedQueue<() -> Unit>()
    private val background = CameraBackground()

    private var program = 0
    private var aPos = 0
    private var aNormal = 0
    private var aColor = 0
    private var uMvp = 0
    private var uModel = 0
    private var uTint = 0
    private var uLight = 0
    /** Загруженные модели по id из [ContentCatalog]. */
    private val models = HashMap<String, ObjModel>()
    private lateinit var quad: ObjModel

    private var width = 1
    private var height = 1
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val vm = FloatArray(16)

    private var currentFrame: ARFrame? = null
    private var lastTracking: Boolean? = null

    /** Поставленные объекты. Трогаем только из GL-потока. */
    private val placed = ArrayList<Placed>()

    /**
     * Выбранный объект: кнопки (размер, поворот, цвет, удалить) действуют на него. Выбор «липкий» —
     * сохраняется, пока объект в кадре; сменить его можно кнопкой переключения. Если объект ушёл из кадра,
     * выбирается ближайший к центру экрана из видимых.
     */
    private var focused: Placed? = null

    private var nextNumber = 1

    /** Объекты и их проекции на экран из последнего кадра — нужны кнопке переключения. */
    private var liveObjects: List<Placed> = emptyList()
    private var liveProj: List<GuideMath.Proj> = emptyList()

    private val tmp = FloatArray(16)
    private var lastGuide: GuideState? = null
    private var lastGuideAt = 0L

    // распознавание тапа (UI-поток)
    private var tapCandidate = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    // ===================== UI-поток =====================

    fun onDisplayChanged() {
        geometryDirty = true
    }

    /** Убрать все поставленные объекты (для повторных проверок). */
    fun clearPlaced() {
        queue.add {
            placed.forEach { it.anchor.detach() }
            placed.clear()
            focused = null
            nextNumber = 1
            callbacks.onPlacedCountChanged(0)
        }
    }

    // ---- кнопки взаимодействия: действуют на выбранный (focused) объект ----

    /** Переключиться на следующий объект из тех, что сейчас в кадре (по кругу, по порядку номеров). */
    fun switchFocus() {
        queue.add {
            val cur = focused?.let { liveObjects.indexOf(it) }?.takeIf { it >= 0 }
            val next = GuideMath.nextInView(liveProj, cur)
            if (next != null) {
                val candidate = liveObjects[next]
                if (placed.contains(candidate)) focused = candidate
            }
        }
    }

    fun scaleFocused(factor: Float) = withFocused { it.scale = (it.scale * factor).coerceIn(MIN_SCALE, MAX_SCALE) }

    fun rotateFocused() = withFocused { it.yawDeg = (it.yawDeg + ROTATE_STEP_DEG) % 360f }

    /** Следующий цвет из палитры — только у выбранного объекта. */
    fun recolorFocused() = withFocused { it.colorIdx = (it.colorIdx + 1) % ContentCatalog.colors.size }

    fun deleteFocused() = withFocused {
        it.anchor.detach()
        placed.remove(it)
        if (focused === it) focused = null
        callbacks.onPlacedCountChanged(placed.size)
    }

    private fun withFocused(action: (Placed) -> Unit) {
        queue.add {
            val f = focused
            if (f != null && placed.contains(f)) action(f)
        }
    }

    /** Тап = короткое касание одним пальцем без заметного сдвига. */
    fun onTouchEvent(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tapCandidate = true
                downX = e.x
                downY = e.y
                downTime = e.eventTime
            }
            MotionEvent.ACTION_POINTER_DOWN -> tapCandidate = false
            MotionEvent.ACTION_MOVE ->
                if (tapCandidate && hypot(e.x - downX, e.y - downY) > touchSlop) tapCandidate = false
            MotionEvent.ACTION_UP -> {
                if (tapCandidate && e.eventTime - downTime < TAP_MAX_MS) {
                    // MotionEvent переиспользуется системой — отдаём GL-потоку копию
                    val copy = MotionEvent.obtain(e)
                    queue.add {
                        try {
                            handleTap(copy)
                        } finally {
                            copy.recycle()
                        }
                    }
                }
                tapCandidate = false
            }
            MotionEvent.ACTION_CANCEL -> tapCandidate = false
        }
    }

    // ===================== GLSurfaceView.Renderer =====================

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1f)
        background.init()
        program = Gl.program(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aColor = GLES20.glGetAttribLocation(program, "aColor")
        uMvp = GLES20.glGetUniformLocation(program, "uMVP")
        uModel = GLES20.glGetUniformLocation(program, "uModel")
        uTint = GLES20.glGetUniformLocation(program, "uTint")
        uLight = GLES20.glGetUniformLocation(program, "uLight")
        // все модели из каталога; если какая-то не загрузилась, остальные продолжают работать
        models.clear()
        for (item in ContentCatalog.items) {
            try {
                models[item.id] = ObjLoader.load(context, item.asset)
            } catch (e: Exception) {
                Log.e(TAG, "model '${item.asset}' load failed: $e")
                callbacks.onMessage("Не удалось загрузить модель «${item.title}» (${item.asset}): ${e.javaClass.simpleName}")
            }
        }
        // единичный квадрат в плоскости XZ — подсветка найденной поверхности
        val q = floatArrayOf(
            -0.5f, 0f, -0.5f, 0f, 1f, 0f, 1f, 1f, 1f,
            0.5f, 0f, -0.5f, 0f, 1f, 0f, 1f, 1f, 1f,
            0.5f, 0f, 0.5f, 0f, 1f, 0f, 1f, 1f, 1f,
            -0.5f, 0f, -0.5f, 0f, 1f, 0f, 1f, 1f, 1f,
            0.5f, 0f, 0.5f, 0f, 1f, 0f, 1f, 1f, 1f,
            -0.5f, 0f, 0.5f, 0f, 1f, 0f, 1f, 1f, 1f
        )
        quad = ObjModel(q)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        width = w
        height = h
        geometryDirty = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (geometryDirty) {
            s.setDisplayGeometry(displayRotation, width, height)
            geometryDirty = false
        }
        s.setCameraTextureName(background.textureId)
        val frame: ARFrame = try {
            s.update()
        } catch (e: Exception) {
            Log.w(TAG, "session.update failed: $e")
            return
        }
        currentFrame = frame
        background.draw(frame)

        val camera = frame.getCamera()
        val tracking = camera.getTrackingState() == ARTrackable.TrackingState.TRACKING
        if (tracking != lastTracking) {
            lastTracking = tracking
            callbacks.onTrackingChanged(tracking)
        }
        if (!tracking) {
            // камера (ещё) не отслеживает мир: идёт настройка AR / поиск якорей. Тапы игнорируем,
            // объекты остаются в списке, поверх AR показывается сообщение о процессе поиска
            queue.clear()
            focused = null
            publishGuide(GuideState(configuring = true))
            return
        }
        camera.getProjectionMatrix(proj, 0, 0.1f, 100f)
        camera.getViewMatrix(view, 0)

        // surface tracking «включён» только в зоне: вне зоны плоскости не используются вообще
        val planes = if (controller.mode == PlacementController.Mode.IDLE) emptyList() else visiblePlanes(s)
        controller.onPlanesDetected(planes.isNotEmpty())

        while (true) {
            val task = queue.poll() ?: break
            try {
                task()
            } catch (e: Exception) {
                Log.w(TAG, "task failed: $e")
            }
        }

        publishGuide(updateGuide())
        drawScene(planes)
    }

    // ===================== вспомогательный интерфейс =====================

    /**
     * Каждый кадр проецируем центры всех объектов на экран:
     *  - есть объект в кадре → он «найден»: рамка и стрелка скрыты, показываются кнопки;
     *  - объектов в кадре нет, но они есть → стрелка в сторону ближайшего по повороту;
     *  - в зоне ищем поверхность и объекта в кадре нет → рамка-видоискатель.
     */
    private fun updateGuide(): GuideState {
        val live = ArrayList<Placed>()
        val projections = ArrayList<GuideMath.Proj>()
        for (p in placed) {
            if (p.anchor.getTrackingState() != ARTrackable.TrackingState.TRACKING) continue
            p.anchor.getPose().toMatrix(tmp, 0)
            // центр объекта: основание + полвысоты вдоль «вверх» якоря (второй столбец матрицы позы)
            val h = 0.5f * ContentCatalog.byId(p.contentId).heightM * p.scale
            projections.add(GuideMath.project(view, proj, tmp[12] + tmp[4] * h, tmp[13] + tmp[5] * h, tmp[14] + tmp[6] * h))
            live.add(p)
        }
        liveObjects = live
        liveProj = projections

        // выбранный объект остаётся выбранным, пока в кадре; иначе — ближайший к центру экрана
        val keep = focused?.let { live.indexOf(it) }?.takeIf { it >= 0 }
        val decision = GuideMath.decide(projections, keep)
        val idx = decision.focusIndex
        val found = if (idx != null) live[idx] else null
        focused = found

        // переключатель нужен, только если в кадре есть ещё хотя бы один объект
        val next = if (idx != null && projections.count { it.inView } >= 2) GuideMath.nextInView(projections, idx) else null

        return GuideState(
            configuring = false,
            showFrame = found == null && controller.mode == PlacementController.Mode.SEARCHING,
            arrowAngleDeg = decision.arrowAngleDeg,
            focusedLabel = found?.let {
                "Объект №${it.number}\n${ContentCatalog.byId(it.contentId).title}\n«${it.name}»\nцвет: ${ContentCatalog.colorOf(it.colorIdx).name}"
            },
            switchLabel = next?.let { "→ Объект №${live[it].number}" }
        )
    }

    /** Отправляет состояние в UI, только если оно изменилось; частые мелкие изменения угла стрелки — не чаще 20 раз/с. */
    private fun publishGuide(state: GuideState) {
        val last = lastGuide
        if (last == state) return
        val now = System.currentTimeMillis()
        val onlyAngleChanged = last != null &&
                last.configuring == state.configuring &&
                last.showFrame == state.showFrame &&
                last.focusedLabel == state.focusedLabel &&
                last.switchLabel == state.switchLabel &&
                (last.arrowAngleDeg == null) == (state.arrowAngleDeg == null)
        if (onlyAngleChanged && now - lastGuideAt < GUIDE_MIN_INTERVAL_MS) return
        lastGuide = state
        lastGuideAt = now
        callbacks.onGuideChanged(state)
    }

    // ===================== плоскости и размещение =====================

    /** Отслеживаемые плоскости без «поглощённых» более крупными. */
    private fun visiblePlanes(s: ARSession): List<ARPlane> =
        s.getAllTrackables(ARPlane::class.java).filter {
            it.getTrackingState() == ARTrackable.TrackingState.TRACKING && it.getSubsumedBy() == null
        }

    private fun handleTap(e: MotionEvent) {
        val frame = currentFrame ?: return

        // вне зоны размещение заблокировано
        val poi = controller.activePoi
        if (poi == null) {
            callbacks.onMessage("Вне зоны: размещение заблокировано")
            return
        }
        if (controller.mode != PlacementController.Mode.READY) {
            callbacks.onMessage("Сначала наведите камеру на поверхность")
            return
        }

        for (hit in frame.hitTest(e)) {
            val trackable = hit.getTrackable()
            if (trackable is ARPlane && trackable.isPoseInPolygon(hit.getHitPose())) {
                val obj = Placed(hit.createAnchor(), poi.contentId, poi.name, nextNumber++)
                placed.add(obj)
                focused = obj // только что поставленный объект сразу выбран — им можно сразу управлять
                callbacks.onPlacedCountChanged(placed.size)
                callbacks.onMessage("Поставлено: объект №${obj.number} («${poi.name}»)")
                return
            }
        }
        callbacks.onMessage("Здесь нет плоскости — тапните по подсвеченной поверхности")
    }

    // ===================== отрисовка =====================

    private fun drawScene(planes: List<ARPlane>) {
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glUseProgram(program)
        GLES20.glUniform3f(uLight, 0.2f, 0.6f, 1f)

        // поставленные объекты рисуются всегда — и в зоне, и после выхода из неё
        var countChanged = false
        val it = placed.iterator()
        while (it.hasNext()) {
            val p = it.next()
            val state = p.anchor.getTrackingState()
            if (state == ARTrackable.TrackingState.STOPPED) {
                p.anchor.detach()
                it.remove()
                countChanged = true
                continue
            }
            if (state != ARTrackable.TrackingState.TRACKING) continue
            // модель объекта — по id из метки; не загрузилась (или id устарел) — объект пропускаем
            val item = ContentCatalog.byId(p.contentId)
            val mesh = models[item.id] ?: continue
            val tint = ContentCatalog.colorOf(p.colorIdx).rgb
            p.anchor.getPose().toMatrix(model, 0)
            Matrix.rotateM(model, 0, p.yawDeg, 0f, 1f, 0f) // поворот кнопкой — вокруг вертикали объекта
            val size = item.heightM * p.scale
            Matrix.scaleM(model, 0, size, size, size)
            draw(mesh, tint[0], tint[1], tint[2], 1f)
        }
        if (countChanged) callbacks.onPlacedCountChanged(placed.size)

        val selected = focused?.takeIf { it.anchor.getTrackingState() == ARTrackable.TrackingState.TRACKING }

        // полупрозрачные накладки: подсветка найденных плоскостей (только в зоне) и «пятно» под выбранным объектом
        if (planes.isNotEmpty() || selected != null) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)
            if (selected != null) {
                selected.anchor.getPose().toMatrix(model, 0)
                Matrix.translateM(model, 0, 0f, 0.002f, 0f)
                val spot = ContentCatalog.byId(selected.contentId).heightM * selected.scale * SELECTION_SPOT_FACTOR
                Matrix.scaleM(model, 0, spot, 1f, spot)
                draw(quad, 1f, 0.85f, 0.1f, 0.4f)
            }
            for (plane in planes) {
                val ex = plane.getExtentX()
                val ez = plane.getExtentZ()
                if (ex < 0.01f || ez < 0.01f) continue
                plane.getCenterPose().toMatrix(model, 0)
                Matrix.translateM(model, 0, 0f, 0.001f, 0f)
                Matrix.scaleM(model, 0, ex, 1f, ez)
                draw(quad, 0.1f, 0.9f, 0.8f, 0.25f)
            }
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
    }

    private fun draw(mesh: ObjModel, r: Float, g: Float, b: Float, a: Float) {
        Matrix.multiplyMM(vm, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, proj, 0, vm, 0)
        GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
        GLES20.glUniform4f(uTint, r, g, b, a)
        val stride = ObjModel.FLOATS * 4
        mesh.buffer.position(0)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, mesh.buffer)
        mesh.buffer.position(3)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, stride, mesh.buffer)
        mesh.buffer.position(6)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, stride, mesh.buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glDisableVertexAttribArray(aColor)
    }

    private companion object {
        const val TAG = "PlacementRenderer"
        const val TAP_MAX_MS = 500L

        /** Пределы масштаба и шаг поворота для кнопок взаимодействия. */
        const val MIN_SCALE = 0.4f
        const val MAX_SCALE = 3f
        const val ROTATE_STEP_DEG = 30f

        /** Размер жёлтого «пятна» под выбранным объектом относительно его высоты. */
        const val SELECTION_SPOT_FACTOR = 1.8f

        /** Не чаще этого интервала шлём в UI изменения одного лишь угла стрелки. */
        const val GUIDE_MIN_INTERVAL_MS = 50L

        const val VERTEX = """
            uniform mat4 uMVP;
            uniform mat4 uModel;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            attribute vec3 aColor;
            varying vec3 vN;
            varying vec3 vColor;
            void main() {
                vN = (uModel * vec4(aNormal, 0.0)).xyz;
                vColor = aColor;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """
        const val FRAGMENT = """
            precision mediump float;
            uniform vec4 uTint;
            uniform vec3 uLight;
            varying vec3 vN;
            varying vec3 vColor;
            void main() {
                float d = max(dot(normalize(vN), normalize(uLight)), 0.0);
                float l = 0.35 + 0.65 * d;
                gl_FragColor = vec4(vColor * uTint.rgb * l, uTint.a);
            }
        """
    }
}