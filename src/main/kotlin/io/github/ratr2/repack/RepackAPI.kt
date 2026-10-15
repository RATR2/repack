package io.github.ratr2.repack

import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack

/**
 * Static entry points for other plugins, callable directly or via reflection, on the main thread:
 *
 * ```java
 * Class<?> api = Bukkit.getPluginManager().getPlugin("repack").getClass().getClassLoader()
 *     .loadClass("io.github.ratr2.repack.RepackAPI");
 * boolean placed = (boolean) api.getMethod("place", Location.class, String.class).invoke(null, location, "table");
 * ```
 */
object RepackAPI {

    @Volatile
    internal var plugin: Repack? = null

    /** Places the custom block [id] at [location]. False if the id is unknown, the spot is taken or the plugin is disabled. */
    @JvmStatic
    fun place(location: Location, id: String): Boolean = place(location, id, 0f)

    /** As above, facing [yaw] degrees for blocks set to rotate to the placer. */
    @JvmStatic
    fun place(location: Location, id: String, yaw: Float): Boolean = plugin?.blocks?.place(location, id, yaw) ?: false

    /** Removes the custom block at [location]; false if there was none. */
    @JvmStatic
    fun remove(location: Location): Boolean = plugin?.blocks?.remove(location.block) ?: false

    /** The id of the custom block at [block], or null when it is not a custom block. */
    @JvmStatic
    fun idAt(block: Block): String? = plugin?.blocks?.idAt(block)

    /** True when [block] is a custom block, and, if [id] is given, one with that id. */
    @JvmStatic
    fun isCustomBlock(block: Block, id: String?): Boolean {
        val found = idAt(block) ?: return false
        return id == null || found == id
    }

    /** The item that places the custom block [id], or null for an unknown id. */
    @JvmStatic
    fun item(id: String, amount: Int): ItemStack? {
        val api = plugin ?: return null
        return api.blocks.definitionOf(id)?.let { api.items.create(it, amount) }
    }

    /**
     * Adds a block type with the default settings that shows [model], a file in the models folder without its extension.
     * Lasts until the next reload. Returns an error message, or null when it worked.
     */
    @JvmStatic
    fun register(id: String, model: String): String? = plugin?.registerBlock(id, model) ?: "Repack is not running."

    /** Removes a block type until the next reload. Blocks already placed stay in the world but show nothing. */
    @JvmStatic
    fun unregister(id: String): Boolean = plugin?.unregisterBlock(id) ?: false

    /** Reads the block files again. Returns the errors found. */
    @JvmStatic
    fun reload(): List<String> = plugin?.reloadDefinitions() ?: emptyList()

    /** Ids that can be placed right now. */
    @JvmStatic
    fun ids(): Set<String> = plugin?.blocks?.types ?: emptySet()
}
