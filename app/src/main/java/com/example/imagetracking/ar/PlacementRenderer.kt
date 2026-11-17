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
    }

    private class Placed(val anchor: ARAnchor, val contentId: String)

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
    private var rocket: ObjModel? = null
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
            callbacks.onPlacedCountChanged(0)
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
        rocket = try {
            ObjLoader.load(context, "rocket.obj")
        } catch (e: Exception) {
            Log.e(TAG, "model load failed: $e")
            callbacks.onMessage("Не удалось загрузить модель: ${e.javaClass.simpleName}")
            null
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
            // камера потеряла отслеживание: тапы игнорируем, объекты остаются в списке
            queue.clear()
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

        drawScene(planes)
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
                placed.add(Placed(hit.createAnchor(), poi.contentId))
                callbacks.onPlacedCountChanged(placed.size)
                callbacks.onMessage("Поставлено: «${poi.name}»")
                return
            }
        }
        callbacks.onMessage("Здесь нет плоскости — тапните по подсвеченной поверхности")
    }

    // ===================== отрисовка =====================

    private fun drawScene(planes: List<ARPlane>) {
        val m = rocket
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
            if (state != ARTrackable.TrackingState.TRACKING || m == null) continue
            val tint = ContentCatalog.byId(p.contentId).tint
            p.anchor.getPose().toMatrix(model, 0)
            Matrix.scaleM(model, 0, OBJECT_SIZE_M, OBJECT_SIZE_M, OBJECT_SIZE_M)
            draw(m, tint[0], tint[1], tint[2], 1f)
        }
        if (countChanged) callbacks.onPlacedCountChanged(placed.size)

        // полупрозрачная подсветка найденных плоскостей — только в зоне
        if (planes.isNotEmpty()) {
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)
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

        /** Высота ракеты в метрах. */
        const val OBJECT_SIZE_M = 0.15f

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
