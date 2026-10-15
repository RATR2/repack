package io.github.ratr2.repack

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.github.ratr2.repack.pack.PackEntry
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

/**
 * ```
 * /repack build               regenerates the pack zip; players keep getting the last pushed pack
 * /repack push [players]      makes the built pack the published one and sends it (everyone online by default)
 * /repack status
 * /repack place <id> [pos]    places a custom block (at your feet by default)
 * /repack remove [pos]        removes the custom block there
 * /repack list                lists the ids that can be placed
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
        .then(
            Commands.literal("place").then(
                Commands.argument("id", StringArgumentType.word())
                    .suggests(::suggestIds)
                    .executes { place(it, it.source.location) }
                    .then(
                        Commands.argument("pos", ArgumentTypes.blockPosition())
                            .executes { place(it, positionOf(it)) }
                    )
            )
        )
        .then(
            Commands.literal("remove")
                .executes { remove(it, it.source.location) }
                .then(
                    Commands.argument("pos", ArgumentTypes.blockPosition())
                        .executes { remove(it, positionOf(it)) }
                )
        )
        .then(Commands.literal("list").executes(::list))
        .build()

    private fun positionOf(context: CommandContext<CommandSourceStack>): Location =
        context.getArgument("pos", BlockPositionResolver::class.java).resolve(context.source).toLocation(context.source.location.world)

    private fun suggestIds(context: CommandContext<CommandSourceStack>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val typed = builder.remainingLowerCase
        plugin.blocks.types.sorted().filter { it.startsWith(typed) }.forEach(builder::suggest)
        return builder.buildFuture()
    }

    private fun build(context: CommandContext<CommandSourceStack>): Int {
        val report = plugin.rebuild()
        val entry = report.entry
        reply(
            context,
            "Built pack ${short(entry)} (${entry.size} bytes) from ${report.models.size} model(s). " +
                "Players still get the published pack until you run /repack push.",
            NamedTextColor.GREEN,
        )
        report.errors.take(MAX_LINES).forEach { reply(context, it, NamedTextColor.RED) }
        report.warnings.take(MAX_LINES).forEach { reply(context, it, NamedTextColor.YELLOW) }
        val hidden = report.errors.size + report.warnings.size - minOf(report.errors.size, MAX_LINES) - minOf(report.warnings.size, MAX_LINES)
        if (hidden > 0) reply(context, "$hidden more in the server log.", NamedTextColor.GRAY)
        return Command.SINGLE_SUCCESS
    }

    private fun push(context: CommandContext<CommandSourceStack>, targets: List<Player>): Int {
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

    private fun place(context: CommandContext<CommandSourceStack>, location: Location): Int {
        val id = StringArgumentType.getString(context, "id")
        if (!plugin.blocks.place(location, id)) {
            val reason = if (id !in plugin.blocks.types) "There is no model with the id '$id'." else "That spot is taken."
            reply(context, reason, NamedTextColor.RED)
            return 0
        }
        reply(context, "Placed $id at ${location.blockX} ${location.blockY} ${location.blockZ}.", NamedTextColor.GREEN)
        return Command.SINGLE_SUCCESS
    }

    private fun remove(context: CommandContext<CommandSourceStack>, location: Location): Int {
        if (!plugin.blocks.remove(location.block)) {
            reply(context, "There is no custom block at ${location.blockX} ${location.blockY} ${location.blockZ}.", NamedTextColor.RED)
            return 0
        }
        reply(context, "Removed it.", NamedTextColor.GREEN)
        return Command.SINGLE_SUCCESS
    }

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        val ids = plugin.blocks.types.sorted()
        reply(context, if (ids.isEmpty()) "No models yet. Put .bbmodel files in plugins/Repack/models." else ids.joinToString(", "), NamedTextColor.YELLOW)
        return Command.SINGLE_SUCCESS
    }

    private fun short(entry: PackEntry) = entry.sha1.take(8)

    private fun reply(context: CommandContext<CommandSourceStack>, text: String, color: NamedTextColor) {
        context.source.sender.sendMessage(Component.text(text, color))
    }

    companion object {
        const val ADMIN = "repack.admin"
        private const val MAX_LINES = 5
    }
}
