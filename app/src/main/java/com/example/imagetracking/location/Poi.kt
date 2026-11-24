package com.example.imagetracking.location

/**
 * Точка интереса (POI) — геозона с привязанным контентом.
 *
 * @param radius    радиус зоны в метрах
 * @param contentId ключ контента, который AR-модуль поставит в этой зоне (для LocationManager — просто строка)
 */
data class Poi(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radius: Float,
    val contentId: String
)

/** Текущая геопозиция: широта, долгота, точность (м) и признак эмуляции. */
data class GeoFix(
    val lat: Double,
    val lon: Double,
    val accuracy: Float,
    val fake: Boolean
)

/** Единственный канал связи с AR-модулем: события входа в зону и выхода из неё. */
interface ZoneListener {
    fun onZoneEntered(poi: Poi)
    fun onZoneExited(poi: Poi)

    /** У метки, в зоне которой мы стоим, сменился контент (зона та же). */
    fun onPoiChanged(poi: Poi) {}
}