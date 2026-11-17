package com.example.imagetracking.ar

/**
 * Контент, который можно привязать к метке. Метка хранит только id (строку),
 * а здесь id превращается в то, что рисует AR (одна модель ракеты, разные цвета).
 */
object ContentCatalog {

    class Item(val id: String, val title: String, val tint: FloatArray)

    val items: List<Item> = listOf(
        Item("rocket_white", "Ракета (белая)", floatArrayOf(1f, 1f, 1f)),
        Item("rocket_yellow", "Ракета (жёлтая)", floatArrayOf(1f, 0.85f, 0.35f)),
        Item("rocket_green", "Ракета (зелёная)", floatArrayOf(0.55f, 1f, 0.6f)),
        Item("rocket_pink", "Ракета (розовая)", floatArrayOf(1f, 0.6f, 0.9f))
    )

    fun byId(id: String): Item = items.firstOrNull { it.id == id } ?: items[0]

    fun next(current: Item): Item = items[(items.indexOf(current) + 1) % items.size]
}
