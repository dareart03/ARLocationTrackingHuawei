package com.example.imagetracking.ar

import com.example.imagetracking.location.Poi
import com.example.imagetracking.location.ZoneListener

/**
 * «Мозг» AR-режима размещения. Знает только события зон ([ZoneListener]) и ничего про GPS.
 *
 *  IDLE      — вне зоны: размещение заблокировано, плоскости не показываются;
 *  SEARCHING — вошли в зону: surface tracking активен, подсказка «наведите камеру на поверхность»;
 *  READY     — плоскость найдена: тап по ней ставит контент активной метки.
 *
 * События зон приходят из главного потока, найденные плоскости — из GL-потока, поэтому
 * изменения состояния синхронизированы, а читаемые поля — volatile.
 */
class PlacementController(
    private val listener: Listener,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ZoneListener {

    enum class Mode { IDLE, SEARCHING, READY }

    interface Listener {
        /** null — подсказку скрыть. Может вызываться из любого потока. */
        fun onHintChanged(text: String?)
    }

    @Volatile
    var mode = Mode.IDLE
        private set

    @Volatile
    var activePoi: Poi? = null
        private set

    private var enteredAt = 0L

    @Synchronized
    override fun onZoneEntered(poi: Poi) {
        activePoi = poi
        enteredAt = clock()
        mode = Mode.SEARCHING
        listener.onHintChanged(hint(poi, Mode.SEARCHING))
    }

    @Synchronized
    override fun onPoiChanged(poi: Poi) {
        if (activePoi?.id == poi.id) activePoi = poi
    }

    /** Выход из зоны: режим сбрасывается. Уже поставленные объекты контроллер не трогает. */
    @Synchronized
    override fun onZoneExited(poi: Poi) {
        if (activePoi?.id != poi.id) return
        activePoi = null
        mode = Mode.IDLE
        listener.onHintChanged(null)
    }

    /** AR-рендерер каждый кадр сообщает, есть ли найденные плоскости. */
    @Synchronized
    fun onPlanesDetected(found: Boolean) {
        val poi = activePoi ?: return
        when {
            found && mode == Mode.SEARCHING && clock() - enteredAt >= MIN_SEARCH_HINT_MS -> {
                mode = Mode.READY
                listener.onHintChanged(hint(poi, Mode.READY))
            }
            !found && mode == Mode.READY -> {
                mode = Mode.SEARCHING
                listener.onHintChanged(hint(poi, Mode.SEARCHING))
            }
        }
    }

    private fun hint(poi: Poi, mode: Mode): String = when (mode) {
        Mode.SEARCHING -> "Вы в зоне «${poi.name}»\nНаведите камеру на поверхность"
        Mode.READY -> "Тапните по поверхности, чтобы поставить объект «${poi.name}»"
        Mode.IDLE -> ""
    }

    companion object {
        /** Подсказка «наведите камеру…» показывается не короче этого времени, даже если плоскость уже известна. */
        const val MIN_SEARCH_HINT_MS = 1500L
    }
}