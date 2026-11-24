package com.example.imagetracking.location

/**
 * Определяет, в какой зоне находится пользователь, и шлёт события входа/выхода.
 *
 * Вход — когда расстояние до центра <= радиуса. Выход — когда расстояние > радиуса + [exitMargin]:
 * этот гистерезис не даёт событиям «дрожать» из-за шума GPS на границе зоны.
 * Если зон несколько, выбирается ближайшая из тех, в которых пользователь находится.
 */
class ZoneTracker(private val exitMargin: Double = EXIT_MARGIN_M) {

    var active: Poi? = null
        private set

    fun update(pois: List<Poi>, lat: Double, lon: Double, listener: ZoneListener?) {
        val cur = active
        if (cur != null) {
            // берём актуальную версию метки (её могли удалить)
            val fresh = pois.firstOrNull { it.id == cur.id }
            if (fresh != null &&
                GeoMath.haversine(lat, lon, fresh.lat, fresh.lon) <= fresh.radius + exitMargin
            ) return
            active = null
            listener?.onZoneExited(cur)
        }

        var best: Poi? = null
        var bestDist = Double.MAX_VALUE
        for (p in pois) {
            val d = GeoMath.haversine(lat, lon, p.lat, p.lon)
            if (d <= p.radius && d < bestDist) {
                best = p
                bestDist = d
            }
        }
        if (best != null) {
            active = best
            listener?.onZoneEntered(best)
        }
    }

    /**
     * Принудительно сделать зону активной (только что поставили метку «здесь»).
     * Без этого, стоя в старой зоне, новая метка с другим контентом так и не стала бы активной.
     */
    /** Подменить активную метку её обновлённой копией (например, сменился контент), без событий входа/выхода. */
    fun replaceActive(poi: Poi) {
        if (active?.id == poi.id) active = poi
    }

    fun switchTo(poi: Poi, listener: ZoneListener?) {
        val cur = active
        if (cur?.id == poi.id) return
        if (cur != null) {
            active = null
            listener?.onZoneExited(cur)
        }
        active = poi
        listener?.onZoneEntered(poi)
    }

    /** Позиция неизвестна (GPS пропал / эмуляция выключена) — считаем, что пользователь вне зон. */
    fun reset(listener: ZoneListener?) {
        val cur = active ?: return
        active = null
        listener?.onZoneExited(cur)
    }

    companion object {
        const val EXIT_MARGIN_M = 10.0
    }
}