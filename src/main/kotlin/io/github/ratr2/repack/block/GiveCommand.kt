package io.github.ratr2.repack.block

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.RootCommandNode
import io.github.ratr2.repack.Repack
import com.destroystokyo.paper.event.brigadier.AsyncPlayerSendCommandsEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.server.ServerCommandEvent

/**
 * Lets `/give @s repack:table 2` work. Vanilla /give doesn't know our ids, so it is answered here, and each player's command
 * tree gets a `repack:<id>` entry so the client suggests it.
 */
class GiveCommand(private val plugin: Repack) : Listener {

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onPlayerCommand(event: PlayerCommandPreprocessEvent) {
        if (handle(event.player, event.message.removePrefix("/"))) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    fun onServerCommand(event: ServerCommandEvent) {
        if (handle(event.sender, event.command.removePrefix("/"))) event.isCancelled = true
    }

    private fun handle(sender: CommandSender, line: String): Boolean {
        val parts = line.trim().split(Regex("\\s+"))
        if (parts.size !in 3..4) return false
        if (!parts[0].equals("give", true) && !parts[0].equals("minecraft:give", true)) return false
        val id = parts[2].lowercase().removePrefix("$NAMESPACE:").takeIf { parts[2].lowercase().startsWith("$NAMESPACE:") } ?: return false
        if (plugin.stackFor(id) == null) return false
        if (!sender.hasPermission("minecraft.command.give")) return false

        val amount = if (parts.size == 4) parts[3].toIntOrNull() else 1
        if (amount == null || amount < 1) {
            sender.sendMessage(Component.text("The count must be a whole number of 1 or more.", NamedTextColor.RED))
            return true
        }
        val targets = try {
            Bukkit.selectEntities(sender, parts[1]).filterIsInstance<Player>()
        } catch (e: IllegalArgumentException) {
            sender.sendMessage(Component.text("Unknown player or selector '${parts[1]}'.", NamedTextColor.RED))
            return true
        }
        if (targets.isEmpty()) {
            sender.sendMessage(Component.text("No player was found.", NamedTextColor.RED))
            return true
        }
        val given = amount.coerceAtMost(MAX_TOTAL)
        for (player in targets) {
            var left = given
            while (left > 0) {
                val stack = minOf(left, 64)
                player.inventory.addItem(plugin.stackFor(id, stack)!!).values.forEach { player.world.dropItemNaturally(player.location, it) }
                left -= stack
            }
        }
        val who = if (targets.size == 1) targets.first().name else "${targets.size} players"
        sender.sendMessage(Component.text("Gave $given [$id] to $who" + if (given < amount) " (the most it gives at once)" else "", NamedTextColor.WHITE))
        return true
    }

    /** Adds `repack:<id> [count]` to /give in the tree the client gets, so it suggests the ids and does not mark them as errors. */
    @EventHandler
    fun onCommandTree(event: AsyncPlayerSendCommandsEvent<*>) {
        addTo(event.commandNode)
    }

    @Suppress("UNCHECKED_CAST")
    private fun addTo(root: RootCommandNode<*>) {
        val ids = plugin.giveIds()
        if (ids.isEmpty()) return
        for (name in listOf("give", "minecraft:give")) {
            val give = root.getChild(name) ?: continue
            val targets = give.children.firstOrNull() as? CommandNode<Any?> ?: continue
            for (id in ids) {
                val literal = "$NAMESPACE:$id"
                if (targets.getChild(literal) != null) continue
                val count = RequiredArgumentBuilder.argument<Any?, Int>("count", IntegerArgumentType.integer(1)).executes { Command.SINGLE_SUCCESS }
                targets.addChild(LiteralArgumentBuilder.literal<Any?>(literal).executes { Command.SINGLE_SUCCESS }.then(count).build())
            }
        }
    }

    companion object {
        const val NAMESPACE = "repack"
        private const val MAX_TOTAL = 2304
    }
}
