package io.github.ratr2.repack

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.github.ratr2.repack.pack.PackEntry
import io.github.ratr2.repack.pack.PackVariant
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
 * /repack give <id> [amount] [players]   gives the item that places a custom block
 * /repack reload [blocks|config|host|all]   reads the block files (default), the config, or restarts the pack server on the new port
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
        .then(
            Commands.literal("give").then(
                Commands.argument("id", StringArgumentType.word())
                    .suggests(::suggestIds)
                    .executes { give(it, 1, listOfNotNull(it.source.sender as? Player)) }
                    .then(
                        Commands.argument("amount", IntegerArgumentType.integer(1, 64))
                            .executes { give(it, IntegerArgumentType.getInteger(it, "amount"), listOfNotNull(it.source.sender as? Player)) }
                            .then(
                                Commands.argument("players", ArgumentTypes.players())
                                    .executes {
                                        val targets = it.getArgument("players", PlayerSelectorArgumentResolver::class.java).resolve(it.source)
                                        give(it, IntegerArgumentType.getInteger(it, "amount"), targets)
                                    }
                            )
                    )
            )
        )
        .then(
            Commands.literal("reload")
                .executes { reload(it, blocks = true, config = false, host = false) }
                .then(Commands.literal("blocks").executes { reload(it, blocks = true, config = false, host = false) })
                .then(Commands.literal("config").executes { reload(it, blocks = false, config = true, host = false) })
                .then(Commands.literal("host").executes { reload(it, blocks = false, config = false, host = true) })
                .then(Commands.literal("all").executes { reload(it, blocks = true, config = true, host = true) })
        )
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
        reply(
            context,
            "Built ${report.entries.entries.joinToString { (variant, entry) -> "${variant.id} ${short(entry)}" }} from ${report.models.size} model(s). " +
                "Players still get the published packs until you run /repack push.",
            NamedTextColor.GREEN,
        )
        report.errors.take(MAX_LINES).forEach { reply(context, it, NamedTextColor.RED) }
        report.warnings.take(MAX_LINES).forEach { reply(context, it, NamedTextColor.YELLOW) }
        val hidden = report.errors.size + report.warnings.size - minOf(report.errors.size, MAX_LINES) - minOf(report.warnings.size, MAX_LINES)
        if (hidden > 0) reply(context, "$hidden more in the server log.", NamedTextColor.GRAY)
        return Command.SINGLE_SUCCESS
    }

    private fun push(context: CommandContext<CommandSourceStack>, targets: List<Player>): Int {
        val published = plugin.store.publish()
        if (published.isEmpty()) {
            reply(context, "Nothing built yet. Run /repack build first.", NamedTextColor.RED)
            return 0
        }
        val sent = plugin.sender.send(targets)
        reply(context, "Published ${published.entries.joinToString { (variant, entry) -> "${variant.id} ${short(entry)}" }} and sent it to $sent player(s).", NamedTextColor.GREEN)
        return Command.SINGLE_SUCCESS
    }

    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val store = plugin.store
        for (variant in PackVariant.entries) {
            val built = store.built[variant]
            val published = store.published[variant]
            val note = if (built != null && built != published) " (the built one has not been pushed yet)" else ""
            reply(context, "${variant.id} (${variant.label}): built ${built?.let(::short) ?: "none"}, published ${published?.let(::short) ?: "none"}$note", NamedTextColor.YELLOW)
        }
        reply(context, "Pack server: ${plugin.host.description}.", NamedTextColor.YELLOW)
        return Command.SINGLE_SUCCESS
    }

    private fun place(context: CommandContext<CommandSourceStack>, location: Location): Int {
        val id = StringArgumentType.getString(context, "id")
        val yaw = (context.source.sender as? Player)?.location?.yaw ?: 0f
        if (!plugin.blocks.place(location, id, yaw)) {
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
        plugin.refreshIfChanged()
        val ids = plugin.blocks.types.sorted()
        if (ids.isNotEmpty()) {
            reply(context, ids.joinToString(", "), NamedTextColor.YELLOW)
            return Command.SINGLE_SUCCESS
        }
        val others = plugin.modelsFolder.listFiles { f -> f.isFile }.orEmpty().map { it.name }
        val hint = if (others.isEmpty()) "Put .bbmodel files in plugins/Repack/models." else "Found ${others.take(3).joinToString()} there, but only .bbmodel files count, directly in that folder."
        reply(context, "No models yet. $hint", NamedTextColor.YELLOW)
        return Command.SINGLE_SUCCESS
    }

    private fun give(context: CommandContext<CommandSourceStack>, amount: Int, targets: List<Player>): Int {
        val id = StringArgumentType.getString(context, "id")
        val definition = plugin.blocks.definitionOf(id)
        if (definition == null) {
            reply(context, "There is no block with the id '$id'.", NamedTextColor.RED)
            return 0
        }
        if (targets.isEmpty()) {
            reply(context, "Name a player to give it to.", NamedTextColor.RED)
            return 0
        }
        targets.forEach { player ->
            val left = player.inventory.addItem(plugin.items.create(definition, amount))
            left.values.forEach { player.world.dropItemNaturally(player.location, it) }
        }
        reply(context, "Gave $amount x $id to ${targets.size} player(s).", NamedTextColor.GREEN)
        return Command.SINGLE_SUCCESS
    }

    private fun reload(context: CommandContext<CommandSourceStack>, blocks: Boolean, config: Boolean, host: Boolean): Int {
        if (config) plugin.reloadConfig()
        if (blocks) {
            val errors = plugin.reloadDefinitions()
            errors.take(MAX_LINES).forEach { reply(context, it, NamedTextColor.RED) }
            reply(context, "Loaded ${plugin.blocks.types.size} block type(s)${if (errors.isEmpty()) "" else ", ${errors.size} with errors"}.", NamedTextColor.GREEN)
        }
        if (host) {
            val error = plugin.host.restart()
            if (error != null) reply(context, error, NamedTextColor.RED) else reply(context, "Pack server ${plugin.host.description}.", NamedTextColor.GREEN)
        }
        if (config && !host) reply(context, "Config reloaded. Run /repack reload host to apply a changed port.", NamedTextColor.GREEN)
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
