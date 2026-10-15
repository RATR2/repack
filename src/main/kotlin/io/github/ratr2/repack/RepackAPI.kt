package io.github.ratr2.repack

import org.bukkit.Location
import org.bukkit.block.Block

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
    fun place(location: Location, id: String): Boolean = plugin?.blocks?.place(location, id) ?: false

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

    /** Ids that can be placed right now. */
    @JvmStatic
    fun ids(): Set<String> = plugin?.blocks?.types ?: emptySet()
}
