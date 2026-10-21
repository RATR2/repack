package io.github.ratr2.repack.text

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer

object Colors {

    private val serializer = LegacyComponentSerializer.builder().character('&').hexColors().build()
    private val angled = Regex("<#([0-9a-fA-F]{6})>")

    @Volatile
    var effects: Map<String, String> = emptyMap()

    private val effect = Regex("<fx:([a-z0-9_-]+)>")

    fun parse(text: String): Component {
        val named = effect.replace(text) { match -> effects[match.groupValues[1]]?.let { "&#$it" } ?: match.value }
        return serializer.deserialize(angled.replace(named) { "&#${it.groupValues[1]}" })
    }

    fun plain(text: String): Component = parse(text).decoration(TextDecoration.ITALIC, false)
}
