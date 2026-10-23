package io.github.ratr2.repack.item

import com.google.gson.JsonObject
import io.github.ratr2.repack.model.ItemModelGenerator
import java.io.File
import java.io.IOException

/**
 * The pack files of items that have a picture of their own or their own display settings. A flat picture is a vanilla `item/generated`
 * (or `handheld`) model of that picture; display settings on a .bbmodel are a model that inherits the .bbmodel's and changes only the
 * display. Either way the item gets an item model definition `repack:custom/<id>`, which is what the item stack points at.
 */
object ItemAssets {

    private const val NS = ItemModelGenerator.NAMESPACE

    fun files(definitions: Collection<ItemDefinition>, folder: File, warnings: MutableList<String> = ArrayList()): Map<String, ByteArray> {
        val files = HashMap<String, ByteArray>()
        for (definition in definitions) {
            if (!definition.ownModel) continue
            val id = definition.id
            val model = JsonObject()
            if (definition.texture != null) {
                val source = File(folder, definition.texture)
                val png = try {
                    source.readBytes()
                } catch (e: IOException) {
                    warnings += "item $id: the texture '${definition.texture}' could not be read: ${e.message}"
                    continue
                }
                files["assets/$NS/textures/item/${ItemDefinition.OWN_FOLDER}/$id.png"] = png
                File(folder, "${definition.texture}.mcmeta").takeIf { it.isFile }?.let { meta ->
                    runCatching { meta.readBytes() }.getOrNull()?.let { files["assets/$NS/textures/item/${ItemDefinition.OWN_FOLDER}/$id.png.mcmeta"] = it }
                }
                model.addProperty("parent", "minecraft:item/${definition.parent}")
                model.add("textures", JsonObject().apply { addProperty("layer0", "$NS:item/${ItemDefinition.OWN_FOLDER}/$id") })
            } else {
                model.addProperty("parent", "$NS:block/${definition.model}")
            }
            val itemWarnings = ArrayList<String>()
            ItemModelGenerator.displayOf(definition.display, itemWarnings, shelf = false)?.let { model.add("display", it) }
            itemWarnings.mapTo(warnings) { "item $id: $it" }
            files["assets/$NS/models/item/${ItemDefinition.OWN_FOLDER}/$id.json"] = model.toString().toByteArray(Charsets.UTF_8)
            files["assets/$NS/items/${ItemDefinition.OWN_FOLDER}/$id.json"] = JsonObject().apply {
                add("model", JsonObject().apply {
                    addProperty("type", "minecraft:model")
                    addProperty("model", "$NS:item/${ItemDefinition.OWN_FOLDER}/$id")
                })
            }.toString().toByteArray(Charsets.UTF_8)
        }
        return files
    }
}
