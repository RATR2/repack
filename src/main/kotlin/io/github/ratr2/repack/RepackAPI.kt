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

    /** Runs [action] on the main thread, waiting for it when called from another one, because the block data is not thread safe. */
    internal fun <T> onMain(fallback: T, action: (Repack) -> T): T {
        val api = plugin ?: return fallback
        if (Bukkit.isPrimaryThread()) return action(api)
        return api.server.scheduler.callSyncMethod(api, Callable { action(api) }).get(10, TimeUnit.SECONDS)
    }

    @JvmStatic
    fun place(location: Location, id: String): Boolean = place(location, id, 0f)

    @JvmStatic
    fun place(location: Location, id: String, yaw: Float): Boolean = plugin?.blocks?.place(location, id, yaw) ?: false

    @JvmStatic
    fun remove(location: Location): Boolean = plugin?.blocks?.remove(location.block) ?: false

    @JvmStatic
    fun idAt(block: Block): String? = plugin?.blocks?.idAt(block)

    @JvmStatic
    fun isCustomBlock(block: Block, id: String?): Boolean {
        val found = idAt(block) ?: return false
        return id == null || found == id
    }

    /** The item that places the custom block id, or null for an unknown id. */
    @JvmStatic
    fun item(id: String, amount: Int): ItemStack? {
        val api = plugin ?: return null
        return api.blocks.definitionOf(id)?.let { api.items.create(it, amount) }
    }

    @JvmStatic
    fun register(id: String, model: String): String? = plugin?.registerBlock(id, model) ?: "Repack is not running."

    @JvmStatic
    fun unregister(id: String): Boolean = plugin?.unregisterBlock(id) ?: false

    @JvmStatic
    fun reload(): List<String> = plugin?.reloadDefinitions() ?: emptyList()

    @JvmStatic
    fun ids(): Set<String> = plugin?.blocks?.types ?: emptySet()
}
