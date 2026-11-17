package com.example.imagetracking.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** POI и расстояние до неё в метрах. */
class PoiDistance(val poi: Poi, val distance: Double)

object GeoMath {
    private const val EARTH_RADIUS_M = 6_371_000.0
    private const val METERS_PER_DEGREE_LAT = 111_320.0

    /** Расстояние между двумя точками на сфере по формуле Haversine, в метрах. */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val s1 = sin(dLat / 2)
        val s2 = sin(dLon / 2)
        val a = (s1 * s1 + cos(p1) * cos(p2) * s2 * s2).coerceIn(0.0, 1.0)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /**
     * N ближайших меток в радиусе видимости: считаем расстояние до всех,
     * отсекаем всё дальше [maxDistance], сортируем по возрастанию, берём первые [n].
     */
    fun nearest(pois: List<Poi>, lat: Double, lon: Double, n: Int, maxDistance: Double): List<PoiDistance> =
        pois.map { PoiDistance(it, haversine(lat, lon, it.lat, it.lon)) }
            .filter { it.distance <= maxDistance }
            .sortedBy { it.distance }
            .take(n)

    /** Сдвиг точки на [northM] м к северу и [eastM] м к востоку (для эмуляции GPS). */
    fun offset(lat: Double, lon: Double, northM: Double, eastM: Double): Pair<Double, Double> {
        val newLat = lat + northM / METERS_PER_DEGREE_LAT
        val newLon = lon + eastM / (METERS_PER_DEGREE_LAT * cos(Math.toRadians(lat)))
        return Pair(newLat, newLon)
    }
}
