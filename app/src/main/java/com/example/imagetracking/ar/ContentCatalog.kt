package com.example.imagetracking.ar

/**
 * Что можно ставить в AR. Метка хранит только id типа контента (строку), а здесь id превращается
 * в то, что рисует AR.
 *
 * Тип контента (модель) и цвет — разные вещи:
 *  - тип выбирается кнопкой внизу и привязывается к метке (сейчас одна модель — «Ракета»);
 *  - цвет — свойство каждого поставленного объекта, меняется кнопкой «Цвет» у выбранного объекта.
 */
object ContentCatalog {

    class Item(val id: String, val title: String)

    class TintColor(val name: String, val rgb: FloatArray)

    val items: List<Item> = listOf(
        Item("rocket", "Ракета")
    )

    val colors: List<TintColor> = listOf(
        TintColor("белая", floatArrayOf(1f, 1f, 1f)),
        TintColor("жёлтая", floatArrayOf(1f, 0.85f, 0.35f)),
        TintColor("зелёная", floatArrayOf(0.55f, 1f, 0.6f)),
        TintColor("розовая", floatArrayOf(1f, 0.6f, 0.9f))
    )

    /** Неизвестный id (например, старая метка из базы с id вроде «rocket_yellow») — это просто ракета. */
    fun byId(id: String): Item = items.firstOrNull { it.id == id } ?: items[0]

    fun next(current: Item): Item = items[(items.indexOf(current) + 1) % items.size]

    fun colorOf(index: Int): TintColor = colors[((index % colors.size) + colors.size) % colors.size]
}
