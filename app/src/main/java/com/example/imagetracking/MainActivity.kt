package com.example.imagetracking

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.imagetracking.ar.ContentCatalog
import com.example.imagetracking.ar.GuideOverlayView
import com.example.imagetracking.ar.GuideState
import com.example.imagetracking.ar.PlacementController
import com.example.imagetracking.ar.PlacementRenderer
import com.example.imagetracking.location.LocationManager
import com.example.imagetracking.location.PoiStorage
import com.huawei.hiar.ARConfigBase
import com.huawei.hiar.AREnginesApk
import com.huawei.hiar.ARSession
import com.huawei.hiar.ARWorldTrackingConfig
import com.huawei.hiar.exceptions.ARCameraNotAvailableException
import com.huawei.hiar.exceptions.ARUnSupportedConfigurationException
import com.huawei.hiar.exceptions.ARUnavailableClientSdkTooOldException
import com.huawei.hiar.exceptions.ARUnavailableServiceApkTooOldException
import com.huawei.hiar.exceptions.ARUnavailableServiceNotInstalledException
import java.util.Locale

/**
 * Лабораторная 5AR: метки с привязкой к географическим координатам на HUAWEI AR Engine.
 *
 * Два независимых модуля, которые склеивает только эта Activity:
 *  - location/ — [LocationManager]: POI, GPS, Haversine, зоны. Не знает про AR.
 *  - ar/       — [PlacementController] + [PlacementRenderer]: поверхности и размещение. Не знает про GPS.
 * Связь — единственный интерфейс ZoneListener (вход в зону / выход из зоны).
 */
class MainActivity : AppCompatActivity(), PlacementRenderer.Callbacks, PlacementController.Listener {

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var tvStatus: TextView
    private lateinit var tvHint: TextView
    private lateinit var debugPanel: View
    private lateinit var guideOverlay: GuideOverlayView
    private lateinit var searchPanel: View
    private lateinit var interactionPanel: View
    private lateinit var tvFocused: TextView
    private lateinit var btnSwitch: Button
    private lateinit var btnContent: Button
    private lateinit var renderer: PlacementRenderer
    private lateinit var controller: PlacementController
    private lateinit var locationManager: LocationManager

    private var session: ARSession? = null
    private var installDialog: AlertDialog? = null
    private var permissionsAsked = false
    private var selectedContent = ContentCatalog.items[0]
    private var gpsError: String? = null

    // меняются из GL-потока, читаются в UI-потоке
    @Volatile private var placedCount = 0
    @Volatile private var cameraTracking = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        surfaceView = findViewById(R.id.surfaceView)
        tvStatus = findViewById(R.id.tvStatus)
        tvHint = findViewById(R.id.tvHint)
        debugPanel = findViewById(R.id.debugPanel)
        guideOverlay = findViewById(R.id.guideOverlay)
        searchPanel = findViewById(R.id.searchPanel)
        interactionPanel = findViewById(R.id.interactionPanel)
        tvFocused = findViewById(R.id.tvFocused)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnContent = findViewById(R.id.btnContent)

        // --- AR-модуль ---
        controller = PlacementController(this)
        renderer = PlacementRenderer(this, controller, this)
        renderer.touchSlop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()

        surfaceView.preserveEGLContextOnPause = true
        surfaceView.setEGLContextClientVersion(2)
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        surfaceView.setRenderer(renderer)
        surfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView.setOnTouchListener { _, e ->
            renderer.onTouchEvent(e)
            true
        }

        // --- модуль геозон; единственная связь с AR — события зон ---
        locationManager = LocationManager(this, PoiStorage(this))
        locationManager.zoneListener = controller
        locationManager.onUpdate = { refreshStatus() }
        locationManager.load()

        setupButtons()
        refreshStatus()
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnPlaceHere).setOnClickListener {
            val poi = locationManager.placeHere(nextPoiName(), DEFAULT_RADIUS_M, selectedContent.id)
            if (poi == null) {
                toast("Нет координат: дождитесь сигнала GPS или включите эмуляцию (⚙)")
            } else {
                toast("«${poi.name}» сохранена, радиус ${poi.radius.toInt()} м")
            }
        }

        // удаляется метка, в зоне которой вы сейчас находитесь; уже поставленные ракеты остаются
        findViewById<Button>(R.id.btnDeletePoi).setOnClickListener {
            val poi = locationManager.activePoi
            if (poi == null) {
                toast("Вы не в зоне метки: подойдите к метке, которую нужно удалить")
            } else {
                locationManager.deletePoi(poi.id)
                toast("Метка «${poi.name}» удалена")
            }
        }

        // кнопка выбора модели; цвет сюда не входит — он свой у каждого объекта (кнопка «Цвет»)
        updateContentButton()
        btnContent.setOnClickListener {
            val next = ContentCatalog.next(selectedContent)
            if (next === selectedContent) {
                toast("Пока доступна одна модель — «${selectedContent.title}». Цвет меняется кнопкой «Цвет» у выбранного объекта")
            } else {
                selectedContent = next
                updateContentButton()
            }
        }

        findViewById<Button>(R.id.btnDebug).setOnClickListener {
            debugPanel.visibility = if (debugPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        findViewById<CheckBox>(R.id.cbFake).setOnCheckedChangeListener { _, on ->
            locationManager.setFakeMode(on)
        }
        findViewById<Button>(R.id.btnNorth).setOnClickListener { locationManager.moveFake(FAKE_STEP_M, 0.0) }
        findViewById<Button>(R.id.btnSouth).setOnClickListener { locationManager.moveFake(-FAKE_STEP_M, 0.0) }
        findViewById<Button>(R.id.btnEast).setOnClickListener { locationManager.moveFake(0.0, FAKE_STEP_M) }
        findViewById<Button>(R.id.btnWest).setOnClickListener { locationManager.moveFake(0.0, -FAKE_STEP_M) }
        findViewById<Button>(R.id.btnClearObjects).setOnClickListener { renderer.clearPlaced() }
        findViewById<Button>(R.id.btnClearPois).setOnClickListener { locationManager.clearPois() }

        // кнопки взаимодействия с найденным объектом (панель появляется, когда объект в кадре)
        btnSwitch.setOnClickListener { renderer.switchFocus() }
        findViewById<Button>(R.id.btnScaleUp).setOnClickListener { renderer.scaleFocused(SCALE_STEP) }
        findViewById<Button>(R.id.btnScaleDown).setOnClickListener { renderer.scaleFocused(1f / SCALE_STEP) }
        findViewById<Button>(R.id.btnRotate).setOnClickListener { renderer.rotateFocused() }
        findViewById<Button>(R.id.btnRecolor).setOnClickListener { renderer.recolorFocused() }
        findViewById<Button>(R.id.btnDelete).setOnClickListener { renderer.deleteFocused() }
    }

    private fun updateContentButton() {
        btnContent.text = selectedContent.title
    }

    /** «Метка N» с номером на единицу больше максимального существующего — имена не повторяются после удаления. */
    private fun nextPoiName(): String {
        val maxN = locationManager.poiList
            .mapNotNull { Regex("Метка (\\d+)").matchEntire(it.name)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 0
        return "Метка ${maxN + 1}"
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return

        val cameraOk = granted(Manifest.permission.CAMERA)
        val locationOk = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!cameraOk || (!locationOk && !permissionsAsked)) {
            permissionsAsked = true
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                REQ_PERMISSIONS
            )
            return
        }

        // GPS не зависит от AR: запускаем сразу
        gpsError = if (locationOk) locationManager.start()
        else "Нет доступа к геолокации — работает только эмуляция GPS (⚙)"
        refreshStatus()

        if (!AREnginesApk.isAREngineApkReady(this)) {
            showInstallDialog()
            return
        }
        installDialog?.dismiss()
        installDialog = null

        if (session == null) {
            try {
                val s = ARSession(applicationContext)
                // World Tracking: поиск плоскостей (Surface Tracking) вместо Image Tracking
                val cfg = ARWorldTrackingConfig(s)
                cfg.setFocusMode(ARConfigBase.FocusMode.AUTO_FOCUS)
                s.configure(cfg)
                session = s
            } catch (e: Exception) {
                handleSessionError(e)
                return
            }
        }

        try {
            session?.resume()
        } catch (e: ARCameraNotAvailableException) {
            toast("Камера недоступна, перезапустите приложение")
            session = null
            return
        } catch (e: Exception) {
            handleSessionError(e)
            return
        }

        @Suppress("DEPRECATION")
        val rotation = windowManager.defaultDisplay.rotation
        renderer.displayRotation = rotation
        renderer.onDisplayChanged()
        renderer.session = session
        surfaceView.onResume()
    }

    override fun onPause() {
        super.onPause()
        locationManager.stop()
        if (session != null) {
            surfaceView.onPause()
            session?.pause()
        }
    }

    override fun onDestroy() {
        installDialog?.dismiss()
        session?.stop()
        session = null
        locationManager.close()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMISSIONS) return
        val cameraIdx = permissions.indexOf(Manifest.permission.CAMERA)
        if (cameraIdx < 0 || grantResults.getOrNull(cameraIdx) != PackageManager.PERMISSION_GRANTED) {
            toast("Нужен доступ к камере")
            finish()
        }
        // отказ в геолокации не фатален: останется эмуляция GPS; onResume вызовется сам
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun handleSessionError(e: Exception) {
        // настройка не состоится — не оставляем на экране вечное «идёт поиск якорей»
        searchPanel.visibility = View.GONE
        when (e) {
            is ARUnavailableServiceNotInstalledException,
            is ARUnavailableServiceApkTooOldException -> showInstallDialog()
            is ARUnavailableClientSdkTooOldException -> toast("Обновите SDK AR Engine в приложении")
            is ARUnSupportedConfigurationException ->
                toast("Это устройство не поддерживает отслеживание поверхностей в AR Engine")
            else -> toast("Ошибка AR Engine: ${e.javaClass.simpleName} ${e.message ?: ""}")
        }
    }

    private fun showInstallDialog() {
        if (installDialog?.isShowing == true) return
        installDialog = AlertDialog.Builder(this)
            .setCancelable(false)
            .setMessage("Для работы нужно приложение HUAWEI AR Engine. Установить или обновить из AppGallery?")
            .setNegativeButton("Отмена") { _, _ -> finish() }
            .setPositiveButton("Установить") { _, _ ->
                try {
                    val intent = Intent("com.huawei.appmarket.intent.action.AppDetail")
                    intent.putExtra("APP_PACKAGENAME", "com.huawei.arengine.service")
                    intent.setPackage("com.huawei.appmarket")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    toast("AppGallery не найден — установите AR Engine вручную")
                }
            }
            .show()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---- статус (UI-поток) ----

    private fun refreshStatus() {
        val sb = StringBuilder()
        val fix = locationManager.current
        if (fix == null) {
            sb.append("GPS: ").append(gpsError ?: "ожидание сигнала…")
        } else {
            sb.append(
                String.format(Locale.US, "GPS: %.6f, %.6f  ±%d м", fix.lat, fix.lon, Math.round(fix.accuracy))
            )
            if (fix.fake) sb.append("  [ЭМУЛЯЦИЯ]")
        }

        val active = locationManager.activePoi
        sb.append('\n').append(
            if (active != null) "Зона: «${active.name}», контент: ${ContentCatalog.byId(active.contentId).title}"
            else "Вне зоны"
        )
        if (!cameraTracking) sb.append("  |  камера: двигайте телефон, идёт поиск")

        val near = locationManager.nearest()
        if (near.isEmpty()) {
            sb.append("\nБлижайших меток (до ${LocationManager.VISIBILITY_RADIUS_M.toInt()} м) нет")
        } else {
            sb.append("\nБлижайшие (до ${LocationManager.VISIBILITY_RADIUS_M.toInt()} м):")
            for (pd in near) {
                val mark = if (pd.poi.id == active?.id) " ✓" else ""
                sb.append(
                    String.format(
                        Locale.US, "\n • %s — %.0f м (зона %d м)%s",
                        pd.poi.name, pd.distance, pd.poi.radius.toInt(), mark
                    )
                )
            }
        }
        sb.append("\nМеток: ${locationManager.poiList.size}  |  Объектов: $placedCount")
        tvStatus.text = sb.toString()
    }

    // ---- PlacementController.Listener (из любого потока) ----

    override fun onHintChanged(text: String?) {
        runOnUiThread {
            tvHint.text = text ?: ""
            tvHint.visibility = if (text == null) View.GONE else View.VISIBLE
        }
    }

    // ---- PlacementRenderer.Callbacks (из GL-потока) ----

    override fun onMessage(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_SHORT).show() }
    }

    override fun onPlacedCountChanged(count: Int) {
        placedCount = count
        runOnUiThread { refreshStatus() }
    }

    /**
     * Вспомогательный интерфейс поверх AR:
     *  - камера ещё настраивается → сообщение со спиннером «идёт поиск якорей»;
     *  - объект вне кадра → стрелка; ищем поверхность → рамка (обе исчезают, когда объект найден);
     *  - объект найден (в кадре) → панель кнопок взаимодействия.
     */
    override fun onGuideChanged(state: GuideState) {
        runOnUiThread {
            searchPanel.visibility = if (state.configuring) View.VISIBLE else View.GONE
            guideOverlay.setGuide(state.showFrame, state.arrowAngleDeg)
            val label = state.focusedLabel
            interactionPanel.visibility = if (label != null) View.VISIBLE else View.GONE
            if (label != null) tvFocused.text = label
            // переключатель «→ Объект №N» виден, только когда в кадре есть другой объект
            val switchLabel = state.switchLabel
            btnSwitch.visibility = if (switchLabel != null) View.VISIBLE else View.GONE
            if (switchLabel != null) btnSwitch.text = switchLabel
        }
    }

    override fun onTrackingChanged(tracking: Boolean) {
        cameraTracking = tracking
        runOnUiThread { refreshStatus() }
    }

    private companion object {
        const val REQ_PERMISSIONS = 1
        const val DEFAULT_RADIUS_M = 20f
        const val FAKE_STEP_M = 10.0
        const val SCALE_STEP = 1.25f
    }
}
