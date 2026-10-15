package io.github.ratr2.repack

import com.mojang.brigadier.Command
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import io.github.ratr2.repack.pack.PackEntry
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor

/**
 * ```
 * /repack build               regenerates the pack zip; players keep getting the last pushed pack
 * /repack push [players]      makes the built pack the published one and sends it (everyone online by default)
 * /repack status
 * ```
 */
class RepackCommand(private val plugin: Repack) {

    fun node(): LiteralCommandNode<CommandSourceStack> = Commands.literal("repack")
        .requires { it.sender.hasPermission(ADMIN) }
        .then(Commands.literal("build").executes(::build))
        .then(
            Commands.literal("push")
                .executes { push(it, plugin.server.onlinePlayers.toList()) }
                .then(
                    Commands.argument("players", ArgumentTypes.players())
                        .executes {
                            val targets = it.getArgument("players", PlayerSelectorArgumentResolver::class.java).resolve(it.source)
                            push(it, targets)
                        }
                )
        )
        .then(Commands.literal("status").executes(::status))
        .build()

    private fun build(context: CommandContext<CommandSourceStack>): Int {
        val entry = plugin.rebuild()
        reply(context, "Built pack ${short(entry)} (${entry.size} bytes). Players still get the published pack until you run /repack push.", NamedTextColor.GREEN)
        return Command.SINGLE_SUCCESS
    }

    private fun push(context: CommandContext<CommandSourceStack>, targets: List<org.bukkit.entity.Player>): Int {
        val entry = plugin.store.publish()
        if (entry == null) {
            reply(context, "Nothing built yet. Run /repack build first.", NamedTextColor.RED)
            return 0
        }
        val sent = plugin.sender.send(targets)
        reply(context, "Published ${short(entry)} and sent it to $sent player(s).", NamedTextColor.GREEN)
        return Command.SINGLE_SUCCESS
    }

    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val built = plugin.store.built
        val published = plugin.store.published
        reply(context, "Built: ${built?.let(::short) ?: "none"}. Published: ${published?.let(::short) ?: "none"}.", NamedTextColor.YELLOW)
        if (built != null && built != published) {
            reply(context, "The built pack has not been pushed yet.", NamedTextColor.YELLOW)
        }
        return Command.SINGLE_SUCCESS
    }

    private fun short(entry: PackEntry) = entry.sha1.take(8)

    private fun reply(context: CommandContext<CommandSourceStack>, text: String, color: NamedTextColor) {
        context.source.sender.sendMessage(Component.text(text, color))
    }

    companion object {
        const val ADMIN = "repack.admin"
    }
}
