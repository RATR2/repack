package io.github.ratr2.repack.edit

import org.bukkit.Material
import org.bukkit.inventory.ItemStack

/**
 * The same edits as the item builder screens, for other plugins and for Skript: change a custom item with chained calls, then save. Nothing is
 * written until save, which checks the item and loads it at once. If the item could not be opened or created, every call does nothing
 * and save says why.
 *
 * ```java
 * String error = RepackAPI.createItem("ruby").base(Material.EMERALD).name("&cRuby").lore("Shiny").save();
 * ```
 */
class ItemBuilder internal constructor(private val draft: ItemDraft?, private val failure: String?) {

    private inline fun edit(change: (ItemDraft) -> Unit): ItemBuilder {
        if (draft != null) change(draft)
        return this
    }

    fun set(path: String, value: Any?) = edit { it.set(path, value) }

    fun base(material: Material) = edit { it.set("base", material.name) }

    fun model(model: String?) = edit { it.set("model", model) }

    /** The name, with `&c` codes and `<#rrggbb>` colors; null to make it from the id. */
    fun name(name: String?) = edit { it.set("name", name) }

    fun lore(vararg lines: String) = edit { it.set("lore", lines.toList()) }

    fun glint(on: Boolean) = edit { it.set("glint", on) }

    fun exact(stack: ItemStack?) = edit { it.setStack(stack, it.overrideModel()) }

    fun overrideModel(on: Boolean) = edit { it.setStack(it.stack(), on) }

    /** Raw components in the syntax of /give, such as `[minecraft:custom_data={a:1}]`; null for none. */
    fun components(components: String?) = edit { it.set("components", components) }

    fun problem(): String? = failure ?: draft?.problem()

    fun save(): String? = failure ?: draft?.save()
}
