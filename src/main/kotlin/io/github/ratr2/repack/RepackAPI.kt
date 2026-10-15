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

    @JvmStatic
    fun ripple(location: Location, rgb: Int, radius: Double, ticks: Int): Boolean = io.github.ratr2.repack.distortion.DistortionAPI.spawn(location, rgb, radius, ticks)

    @JvmStatic
    fun activeRipples(): Int = io.github.ratr2.repack.distortion.DistortionAPI.activeRipples()

    @JvmStatic
    fun maxRipples(): Int = io.github.ratr2.repack.distortion.DistortionAPI.maxRipples()

    @JvmStatic
    fun item(id: String, amount: Int): ItemStack? = onMain(null) { api -> api.stackFor(id, amount) }

    /**
     * Adds a block type with the default settings that shows model, a file in the models folder without its extension.
     * Lasts until the next reload. Returns an error message, or null when it worked.
     */
    @JvmStatic
    fun register(id: String, model: String): String? = onMain("Repack is not running.") { it.registerBlock(id, model) }

    /** Removes a block type until the next reload. Blocks already placed stay in the world but show nothing. */
    @JvmStatic
    fun unregister(id: String): Boolean = onMain(false) { it.unregisterBlock(id) }

    /** Reads the block files again. Returns the errors found. */
    @JvmStatic
    fun reload(): List<String> = onMain(emptyList()) { it.reloadDefinitions() }

    @JvmStatic
    fun edit(id: String): BlockBuilder = onMain(BlockBuilder(null, "Repack is not running.")) { api ->
        val draft = api.gui.editor.open(id)
        BlockBuilder(draft, if (draft == null) "There is no block with the id '$id'." else null)
    }

    @JvmStatic
    fun create(id: String, model: String): BlockBuilder = onMain(BlockBuilder(null, "Repack is not running.")) { api ->
        api.gui.editor.create(id, model).fold(
            onSuccess = { BlockBuilder(it, null) },
            onFailure = { BlockBuilder(null, it.message ?: "Could not make the block.") },
        )
    }

    @JvmStatic
    fun ids(): Set<String> = plugin?.blocks?.types ?: emptySet()
}
