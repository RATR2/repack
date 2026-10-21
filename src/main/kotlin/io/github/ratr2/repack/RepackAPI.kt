package io.github.ratr2.repack

import org.bukkit.Bukkit
import io.github.ratr2.repack.edit.BlockBuilder
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

    /** Runs action on the main thread, waiting for it when called from another one, because the block data is not thread safe. */
    internal fun <T> onMain(fallback: T, action: (Repack) -> T): T {
        val api = plugin ?: return fallback
        if (Bukkit.isPrimaryThread()) return action(api)
        return api.server.scheduler.callSyncMethod(api, Callable { action(api) }).get(10, TimeUnit.SECONDS)
    }

    @JvmStatic
    fun place(location: Location, id: String): Boolean = place(location, id, 0f)

    @JvmStatic
    fun place(location: Location, id: String, yaw: Float): Boolean = onMain(false) { it.blocks.place(location, id, yaw) }

    @JvmStatic
    fun remove(location: Location): Boolean = onMain(false) { it.blocks.remove(location.block) }

    @JvmStatic
    fun idAt(block: Block): String? = onMain(null) { it.blocks.idAt(block) }

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

    /** The item that places the custom block id, or null for an unknown id. */
    @JvmStatic
    fun item(id: String, amount: Int): ItemStack? = onMain(null) { api -> api.blocks.definitionOf(id)?.let { api.items.create(it, amount) } }

    @JvmStatic
    fun register(id: String, model: String): String? = onMain("Repack is not running.") { it.registerBlock(id, model) }

    @JvmStatic
    fun unregister(id: String): Boolean = onMain(false) { it.unregisterBlock(id) }

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
    fun ids(): Set<String> = onMain(emptySet()) { it.blocks.types.toSet() }
}
