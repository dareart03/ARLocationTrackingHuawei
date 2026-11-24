package com.example.imagetracking.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.os.Bundle
import android.os.Looper
import java.util.UUID
import android.location.LocationManager as SystemLocationManager

/**
 * Модуль геозон. Ничего не знает про AR и камеру:
 *  - хранит список POI (SQLite, см. [PoiStorage]);
 *  - получает координаты от GPS (стандартный android.location — работает и на Huawei без Google-сервисов);
 *  - ставит метку по текущему положению ([placeHere]);
 *  - ищет N ближайших в радиусе видимости ([nearest]);
 *  - шлёт [ZoneListener] события входа в зону и выхода из неё.
 *
 * Все вызовы и события — в главном потоке.
 */
class LocationManager(context: Context, private val storage: PoiStorage) : LocationListener {

    private val system =
        context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as SystemLocationManager

    private val pois = ArrayList<Poi>()
    private val tracker = ZoneTracker()
    private var lastReal: GeoFix? = null

    /** Получатель событий зон (AR-модуль). */
    var zoneListener: ZoneListener? = null

    /** Вызывается после каждого нового положения / изменения списка меток — для обновления UI. */
    var onUpdate: (() -> Unit)? = null

    var current: GeoFix? = null
        private set

    var fakeMode = false
        private set

    val activePoi: Poi? get() = tracker.active

    val poiList: List<Poi> get() = pois.toList()

    /** Загрузить метки из базы. */
    fun load() {
        pois.clear()
        pois.addAll(storage.loadAll())
        evaluate()
    }

    /** Запустить GPS. Возвращает текст ошибки или null, если всё в порядке. */
    @SuppressLint("MissingPermission")
    fun start(): String? {
        stop()
        val provider = when {
            system.isProviderEnabled(SystemLocationManager.GPS_PROVIDER) -> SystemLocationManager.GPS_PROVIDER
            system.isProviderEnabled(SystemLocationManager.NETWORK_PROVIDER) -> SystemLocationManager.NETWORK_PROVIDER
            else -> return "Геолокация выключена — включите GPS в настройках (или эмуляцию GPS)"
        }
        return try {
            system.requestLocationUpdates(provider, UPDATE_INTERVAL_MS, UPDATE_DISTANCE_M, this, Looper.getMainLooper())
            system.getLastKnownLocation(provider)?.let {
                if (System.currentTimeMillis() - it.time < MAX_LAST_KNOWN_AGE_MS) handleReal(it)
            }
            null
        } catch (e: SecurityException) {
            "Нет разрешения на точную геолокацию"
        } catch (e: Exception) {
            "Не удалось запустить геолокацию: ${e.javaClass.simpleName}"
        }
    }

    fun stop() {
        try {
            system.removeUpdates(this)
        } catch (e: SecurityException) {
            // разрешение отозвали — слушать уже нечего
        }
    }

    /** Остановить GPS и закрыть базу (при уничтожении Activity). */
    fun close() {
        stop()
        storage.close()
    }

    // ---- метки ----

    /** «Поставить метку здесь»: новая POI по текущему положению, сохраняется в базу. null — нет координат. */
    fun placeHere(name: String, radius: Float, contentId: String): Poi? {
        val fix = current ?: return null
        val poi = Poi(UUID.randomUUID().toString(), name, fix.lat, fix.lon, radius, contentId)
        pois.add(poi)
        storage.insert(poi)
        // мы стоим ровно в центре новой метки — она сразу становится активной, даже если рядом старая зона
        tracker.switchTo(poi, zoneListener)
        onUpdate?.invoke()
        return poi
    }

    /** Сменить модель у метки, в зоне которой мы стоим (сохраняется в базу). null — мы не в зоне. */
    fun setActiveContent(contentId: String): Poi? {
        val cur = tracker.active ?: return null
        val updated = cur.copy(contentId = contentId)
        val i = pois.indexOfFirst { it.id == cur.id }
        if (i >= 0) pois[i] = updated
        storage.insert(updated)
        tracker.replaceActive(updated)
        zoneListener?.onPoiChanged(updated)
        onUpdate?.invoke()
        return updated
    }

    /**
     * Удалить одну метку (из списка и из базы). Если пользователь был в её зоне, придёт событие выхода
     * (а если он при этом стоит ещё и в другой зоне, сразу — вход в неё).
     */
    fun deletePoi(id: String) {
        if (!pois.removeAll { it.id == id }) return
        storage.delete(id)
        evaluate()
    }

    fun clearPois() {
        pois.clear()
        storage.deleteAll()
        evaluate()
    }

    /** N ближайших меток в пределах [maxDistance] метров от текущего положения. */
    fun nearest(n: Int = NEAREST_COUNT, maxDistance: Double = VISIBILITY_RADIUS_M): List<PoiDistance> {
        val fix = current ?: return emptyList()
        return GeoMath.nearest(pois, fix.lat, fix.lon, n, maxDistance)
    }

    // ---- эмуляция GPS (для проверки без прогулки) ----

    fun setFakeMode(on: Boolean) {
        if (fakeMode == on) return
        fakeMode = on
        if (on) {
            val base = current
            apply(GeoFix(base?.lat ?: DEFAULT_LAT, base?.lon ?: DEFAULT_LON, 0f, true))
        } else {
            current = lastReal
            evaluate()
        }
    }

    fun moveFake(northM: Double, eastM: Double) {
        if (!fakeMode) return
        val c = current ?: return
        val (lat, lon) = GeoMath.offset(c.lat, c.lon, northM, eastM)
        apply(GeoFix(lat, lon, 0f, true))
    }

    // ---- LocationListener ----

    override fun onLocationChanged(location: Location) = handleReal(location)

    override fun onProviderEnabled(provider: String) {}

    override fun onProviderDisabled(provider: String) {}

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    // ---- внутреннее ----

    private fun handleReal(loc: Location) {
        val fix = GeoFix(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else 0f, false)
        lastReal = fix
        if (!fakeMode) apply(fix)
    }

    private fun apply(fix: GeoFix) {
        current = fix
        evaluate()
    }

    private fun evaluate() {
        val fix = current
        if (fix == null) {
            tracker.reset(zoneListener)
        } else if (fix.accuracy <= MAX_ACCURACY_M) {
            // слишком неточные координаты не двигают зоны — иначе вход/выход случаются «из воздуха»
            tracker.update(pois, fix.lat, fix.lon, zoneListener)
        }
        onUpdate?.invoke()
    }

    companion object {
        const val NEAREST_COUNT = 3
        const val VISIBILITY_RADIUS_M = 200.0
        const val MAX_ACCURACY_M = 50f

        private const val UPDATE_INTERVAL_MS = 1000L
        private const val UPDATE_DISTANCE_M = 1f
        private const val MAX_LAST_KNOWN_AGE_MS = 60_000L

        // центр Москвы — стартовая точка эмуляции, если реального GPS-фикса ещё нет
        private const val DEFAULT_LAT = 55.7558
        private const val DEFAULT_LON = 37.6173
    }
}