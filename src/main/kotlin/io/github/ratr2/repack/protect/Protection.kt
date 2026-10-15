package io.github.ratr2.repack.protect

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldguard.WorldGuard
import com.sk89q.worldguard.bukkit.WorldGuardPlugin
import com.sk89q.worldguard.protection.flags.Flags
import com.sk89q.worldguard.protection.flags.StateFlag
import io.github.ratr2.repack.Repack
import org.bukkit.block.Block
import org.bukkit.entity.Player

/**
 * Asks a protection plugin whether a player may do something to a block, for the places where Repack acts before a normal
 * Bukkit event exists (starting to mine a block, clicking one). Placing and finishing a break go through real Bukkit events, which
 * protection plugins already handle.
 */
interface Protection {
    fun canBreak(player: Player, block: Block): Boolean
    fun canInteract(player: Player, block: Block): Boolean
}

object NoProtection : Protection {
    override fun canBreak(player: Player, block: Block) = true
    override fun canInteract(player: Player, block: Block) = true
}

object Protections {

    fun detect(plugin: Repack): Protection {
        if (!plugin.server.pluginManager.isPluginEnabled("WorldGuard")) return NoProtection
        plugin.logger.info("WorldGuard found: custom blocks follow its block-break and interact flags.")
        return WorldGuardProtection(plugin)
    }
}

/** Kept in its own class so WorldGuard's classes are only loaded when the plugin is installed. */
private class WorldGuardProtection(private val plugin: Repack) : Protection {

    private var warned = false

    override fun canBreak(player: Player, block: Block) = test(player, block, Flags.BLOCK_BREAK)

    override fun canInteract(player: Player, block: Block) = test(player, block, Flags.INTERACT)

    /** Fails closed: if WorldGuard cannot be asked, the answer is no, so a broken integration never opens a protected area. */
    private fun test(player: Player, block: Block, flag: StateFlag): Boolean = try {
        val query = WorldGuard.getInstance().platform.regionContainer.createQuery()
        query.testBuild(BukkitAdapter.adapt(block.location), WorldGuardPlugin.inst().wrapPlayer(player), flag)
    } catch (e: Throwable) {
        if (!warned) {
            warned = true
            plugin.logger.warning("Could not ask WorldGuard about ${flag.name}, denying instead: $e")
        }
        false
    }
}
