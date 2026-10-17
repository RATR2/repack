package io.github.ratr2.repack

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.block.Block
import org.bukkit.inventory.ItemStack
import java.util.concurrent.Callable
import java.util.concurrent.TimeUnit

/**
 * Static entry points for other plugins, callable directly or via reflection. From another thread a call waits for the main thread:
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

    /** Runs [action] on the main thread, waiting for it when called from another one, because the block data is not thread safe. */
    private fun <T> onMain(fallback: T, action: (Repack) -> T): T {
        val api = plugin ?: return fallback
        if (Bukkit.isPrimaryThread()) return action(api)
        return api.server.scheduler.callSyncMethod(api, Callable { action(api) }).get(10, TimeUnit.SECONDS)
    }

    /** Places the custom block id at location. False if the id is unknown, the spot is taken or the plugin is disabled. */
    @JvmStatic
    fun place(location: Location, id: String): Boolean = place(location, id, 0f)

    /** As above, facing yaw degrees for blocks set to rotate to the placer. */
    @JvmStatic
    fun place(location: Location, id: String, yaw: Float): Boolean = onMain(false) { it.blocks.place(location, id, yaw) }

    /** Removes the custom block at location; false if there was none. */
    @JvmStatic
    fun remove(location: Location): Boolean = onMain(false) { it.blocks.remove(location.block) }

    /** The id of the custom block at block, or null when it is not a custom block. */
    @JvmStatic
    fun idAt(block: Block): String? = onMain(null) { it.blocks.idAt(block) }

    /** True when block is a custom block, and, if id is given, one with that id. */
    @JvmStatic
    fun isCustomBlock(block: Block, id: String?): Boolean {
        val found = idAt(block) ?: return false
        return id == null || found == id
    }

    /** The item that places the custom block id, or null for an unknown id. */
    @JvmStatic
    fun item(id: String, amount: Int): ItemStack? = onMain(null) { api -> api.blocks.definitionOf(id)?.let { api.items.create(it, amount) } }

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

    /** Ids that can be placed right now. */
    @JvmStatic
    fun ids(): Set<String> = onMain(emptySet()) { it.blocks.types.toSet() }
}
