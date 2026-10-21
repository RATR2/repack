package io.github.ratr2.repack.gui

import com.google.gson.JsonArray
import com.google.gson.JsonObject

object GuiPack {

    fun files(): Map<String, ByteArray> {
        val files = HashMap<String, ByteArray>()
        val providers = JsonArray()
        providers.add(JsonObject().apply {
            addProperty("type", "space")
            add("advances", JsonObject().apply { addProperty(GuiAssets.SHIFT_LEFT.toString(), -(8 + GuiAssets.PAD)) })
        })
        var screens = 0
        for (name in GuiAssets.SCREENS) {
            val png = GuiAssets.screenPng(name) ?: continue
            val (_, height) = GuiAssets.sizeOf(png)
            files["assets/repack/textures/gui/screens/$name.png"] = png
            providers.add(JsonObject().apply {
                addProperty("type", "bitmap")
                addProperty("file", "repack:gui/screens/$name.png")
                // The title is drawn 13 pixels below the top of the screen, so an ascent of 13 puts a picture's top at the top;
                // pictures reach PAD pixels above and left of the screen, so it is 13 + PAD.
                addProperty("ascent", 13 + GuiAssets.PAD)
                addProperty("height", height)
                add("chars", JsonArray().apply { add(GuiAssets.glyph(name).toString()) })
            })
            screens++
        }
        if (screens > 0) files["assets/repack/font/gui.json"] = JsonObject().apply { add("providers", providers) }.toString().toByteArray(Charsets.UTF_8)

        for (name in GuiAssets.ICONS) {
            val png = GuiAssets.iconPng(name) ?: continue
            files["assets/repack/textures/item/gui/$name.png"] = png
            GuiAssets.iconMeta(name)?.let { files["assets/repack/textures/item/gui/$name.png.mcmeta"] = it }
            files["assets/repack/models/gui/$name.json"] = JsonObject().apply {
                addProperty("parent", "minecraft:item/generated")
                add("textures", JsonObject().apply { addProperty("layer0", "repack:item/gui/$name") })
            }.toString().toByteArray(Charsets.UTF_8)
            files["assets/repack/items/gui/$name.json"] = JsonObject().apply {
                add("model", JsonObject().apply {
                    addProperty("type", "minecraft:model")
                    addProperty("model", "repack:gui/$name")
                })
            }.toString().toByteArray(Charsets.UTF_8)
        }
        return files
    }
}
