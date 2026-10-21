package io.github.ratr2.repack

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.FloatArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.LiteralCommandNode
import io.github.ratr2.repack.pack.PackEntry
import io.github.ratr2.repack.pack.PackEra
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
 * /repack build [force|fresh]   regenerates the pack zip (force: even if models failed; fresh: reconvert every model, skipping the cache); players keep the last pushed pack
 * /repack push [players]      makes the built pack the published one and sends it (everyone online by default)
 * /repack status
 * /repack place <id> [pos]    places a custom block (at your feet by default)
 * /repack remove [pos]        removes the custom block there
 * /repack list                lists the ids that can be placed
 * /repack menu              opens a screen with every block item to take
 * /repack builder [id]      the block builder: edit a block's settings, item and recipe in screens
 * /repack give <id> [amount] [players]   gives the item that places a custom block
 * /repack distortion spawn <hex> <radius> <ticks> [pos]   a ripple effect; debug [on|off] shows the debug view
 * /repack reload [blocks|config|host|all]   reads the block files, builds the pack and pushes it to everyone online (default); blocks, config and host do only that part; all also reloads the config and the pack server
 * ```
 */
class RepackCommand(private val plugin: Repack) {

    fun node(): LiteralCommandNode<CommandSourceStack> = Commands.literal("repack")
        .executes(::hub)
        .then(
            admin("build")
                .executes { build(it, force = false) }
                .then(Commands.literal("force").executes { build(it, force = true) })
                .then(Commands.literal("fresh").executes { build(it, force = false, fresh = true) })
        )
        .then(
            admin("push")
                .executes { push(it, plugin.server.onlinePlayers.toList()) }
                .then(
                    Commands.argument("players", ArgumentTypes.players())
                        .executes {
                            val targets = it.getArgument("players", PlayerSelectorArgumentResolver::class.java).resolve(it.source)
                            push(it, targets)
                        }
                )
        )
        .then(admin("status").executes(::status))
        .then(
            admin("place").then(
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
            admin("remove")
                .executes { remove(it, it.source.location) }
                .then(
                    Commands.argument("pos", ArgumentTypes.blockPosition())
                        .executes { remove(it, positionOf(it)) }
                )
        )
        .then(admin("list").executes(::list))
        .then(
            admin("animate").then(
                Commands.argument("animation", StringArgumentType.word())
                    .suggests(::suggestAnimations)
                    .executes { animate(it, null) }
                    .then(
                        Commands.argument("mode", StringArgumentType.word())
                            .suggests { _, builder -> listOf("loop", "once", "hold", "default").filter { it.startsWith(builder.remainingLowerCase) }.forEach(builder::suggest); builder.buildFuture() }
                            .executes { animate(it, null) }
                            .then(
                                Commands.argument("pos", ArgumentTypes.blockPosition())
                                    .executes { animate(it, positionOf(it)) }
                            )
                    )
            )
        )
        .then(
            admin("builder")
                .executes { builder(it, null) }
                .then(
                    Commands.argument("id", StringArgumentType.word())
                        .suggests(::suggestIds)
                        .executes { builder(it, StringArgumentType.getString(it, "id")) }
                )
        )
        .then(
            admin("menu").executes {
                val player = it.source.sender as? Player
                if (player == null) reply(it, "Only players can open the menu.", NamedTextColor.RED) else plugin.menu.open(player)
                Command.SINGLE_SUCCESS
            }
        )
        .then(
            admin("give").then(
                Commands.argument("id", StringArgumentType.word())
                    .suggests(::suggestGiveIds)
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
            admin("reload")
                .executes { reloadPack(it, config = false, host = false) }
                .then(Commands.literal("blocks").executes { reload(it, blocks = true, config = false, host = false) })
                .then(Commands.literal("config").executes { reload(it, blocks = false, config = true, host = false) })
                .then(Commands.literal("host").executes { reload(it, blocks = false, config = false, host = true) })
                .then(Commands.literal("all").executes { reloadPack(it, config = true, host = true) })
        )
        .then(io.github.ratr2.repack.distortion.DistortionCommand(plugin).shakeTree())
        .then(io.github.ratr2.repack.distortion.DistortionCommand(plugin).tree("distortion"))
        .build()

    private fun admin(name: String) = Commands.literal(name).requires { it.sender.hasPermission(ADMIN) }

    private fun hub(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.sender as? Player
        if (player == null) {
            reply(context, "Only players can open the settings. From the console: /repack build, push, status, reload.", NamedTextColor.RED)
            return 0
        }
        plugin.gui.open(player, io.github.ratr2.repack.gui.HubScreen(plugin.gui, player))
        return Command.SINGLE_SUCCESS
    }

    private fun suggestAnimations(context: CommandContext<CommandSourceStack>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val typed = builder.remainingLowerCase
        (plugin.modelClips.values.flatten().distinct() + "none").sorted().filter { it.startsWith(typed) }.forEach(builder::suggest)
        return builder.buildFuture()
    }

    private fun animate(context: CommandContext<CommandSourceStack>, at: Location?): Int {
        val location = at ?: (context.source.sender as? Player)?.getTargetBlockExact(8)?.location
        if (location == null) {
            reply(context, "Look at a custom block, or give its position.", NamedTextColor.RED)
            return 0
        }
        val name = StringArgumentType.getString(context, "animation").lowercase()
        val mode = runCatching { StringArgumentType.getString(context, "mode") }.getOrNull()
        val ok = RepackAPI.playAnimation(location, name.takeIf { it != "none" }, mode)
        if (ok) reply(context, if (name == "none") "Stopped." else "Playing $name.", NamedTextColor.GREEN)
        else reply(context, "There is no custom block with an animation '$name' there. Animations of the model: ${RepackAPI.idAt(location.block)?.let { RepackAPI.animations(it) }.orEmpty().joinToString().ifEmpty { "none" }}.", NamedTextColor.RED)
        return if (ok) Command.SINGLE_SUCCESS else 0
    }

    private fun positionOf(context: CommandContext<CommandSourceStack>): Location =
        context.getArgument("pos", BlockPositionResolver::class.java).resolve(context.source).toLocation(context.source.location.world)

    private fun suggestIds(context: CommandContext<CommandSourceStack>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val typed = builder.remainingLowerCase
        plugin.blocks.types.sorted().filter { it.startsWith(typed) }.forEach(builder::suggest)
        return builder.buildFuture()
    }

    private fun finePositionOf(context: CommandContext<CommandSourceStack>): Location =
        context.getArgument("pos", io.papermc.paper.command.brigadier.argument.resolvers.FinePositionResolver::class.java).resolve(context.source).toLocation(context.source.location.world)

    private fun suggestGiveIds(context: CommandContext<CommandSourceStack>, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val typed = builder.remainingLowerCase
        plugin.giveIds().filter { it.startsWith(typed) }.forEach(builder::suggest)
        return builder.buildFuture()
    }

    private fun build(context: CommandContext<CommandSourceStack>, force: Boolean, fresh: Boolean = false): Int {
        reply(context, "Building the pack...", NamedTextColor.GRAY)
        plugin.rebuild(force, fresh) { report -> showBuild(context, report) }
        return Command.SINGLE_SUCCESS
    }

    private fun showBuild(context: CommandContext<CommandSourceStack>, report: BuildReport) {
        if (report.rejected) {
            reply(
                context,
                "Build rejected: ${report.errors.size} error(s) while converting models, so the pack was not updated. " +
                    "Fix them, or run /repack build force to build without those models.",
                NamedTextColor.RED,
            )
        } else {
            val built = "Built ${report.entries.entries.joinToString { (variant, entry) -> "${variant.id} ${short(entry)}" }} from ${report.models.size} model(s). "
            val next = when {
                !plugin.sending -> plugin.exportedTo?.let { "Repack does not send it: it is in $it (repack.zip), for another plugin to add as an external zip." } ?: "Repack does not send it (resource-pack.send: false)."
                report.sent != null -> "Sent it to ${report.sent} player(s)."
                report.unchanged -> "It is the same as the pack last pushed, so nothing was sent. /repack push sends it anyway."
                else -> "Nobody was sent it. Run /repack push to send it to everyone online, or /repack push <player> to one."
            }
            reply(context, built + next, NamedTextColor.GREEN)
        }
        report.errors.take(MAX_LINES).forEach { reply(context, it, NamedTextColor.RED) }
        report.warnings.take(MAX_LINES).forEach { reply(context, it, NamedTextColor.YELLOW) }
        val hidden = report.errors.size + report.warnings.size - minOf(report.errors.size, MAX_LINES) - minOf(report.warnings.size, MAX_LINES)
        if (hidden > 0) reply(context, "$hidden more in the server log.", NamedTextColor.GRAY)
    }

    private fun push(context: CommandContext<CommandSourceStack>, targets: List<Player>): Int {
        if (!plugin.sending) {
            reply(context, "Repack does not send the pack (resource-pack.send: false). Another plugin sends it: add ${plugin.exportFolder()}${java.io.File.separator}repack.zip as its external zip.", NamedTextColor.RED)
            return 0
        }
        val published = plugin.store.publish()
        if (published.isEmpty()) {
            reply(context, "Nothing built yet. Run /repack build first.", NamedTextColor.RED)
            return 0
        }
        val sent = plugin.sender.send(targets)
        reply(context, "Published ${published.entries.joinToString { (variant, entry) -> "${variant.id} ${short(entry)}" }} and sent it to $sent player(s)." + if (plugin.config.getBoolean("resource-pack.invulnerable-while-loading", true)) " They cannot be hurt while their client reloads it." else "", NamedTextColor.GREEN)
        return Command.SINGLE_SUCCESS
    }

    private fun status(context: CommandContext<CommandSourceStack>): Int {
        val store = plugin.store
        for (variant in PackEra.entries) {
            val built = store.built[variant]
            val published = store.published[variant]
            val note = if (built != null && built != published) " (the built one has not been pushed yet)" else ""
            reply(context, "${variant.id} (${variant.label}): built ${built?.let(::short) ?: "none"}, published ${published?.let(::short) ?: "none"}$note", NamedTextColor.YELLOW)
        }
        if (plugin.sending) reply(context, "Pack server: ${plugin.host.description}.", NamedTextColor.YELLOW)
        else reply(context, "Not sending the pack (resource-pack.send: false); it is built into ${plugin.exportFolder()} for another plugin.", NamedTextColor.YELLOW)
        if (plugin.sending) plugin.exportFolder()?.let { reply(context, "Also written to $it.", NamedTextColor.YELLOW) }
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
        val itemIds = plugin.customItems.ids.sorted()
        if (itemIds.isNotEmpty()) reply(context, "Items: ${itemIds.joinToString(", ")}", NamedTextColor.AQUA)
        if (ids.isNotEmpty()) {
            reply(context, (if (itemIds.isEmpty()) "" else "Blocks: ") + ids.joinToString(", "), NamedTextColor.YELLOW)
            return Command.SINGLE_SUCCESS
        }
        if (itemIds.isNotEmpty()) return Command.SINGLE_SUCCESS
        val others = plugin.modelsFolder.listFiles { f -> f.isFile }.orEmpty().map { it.name }
        val hint = if (others.isEmpty()) "Put .bbmodel files in plugins/Repack/models." else "Found ${others.take(3).joinToString()} there, but only .bbmodel files count, directly in that folder."
        reply(context, "No blocks yet. $hint Make one in /repack > Block builder, or set auto-blocks: true in config.yml to make every model a block.", NamedTextColor.YELLOW)
        return Command.SINGLE_SUCCESS
    }

    private fun give(context: CommandContext<CommandSourceStack>, amount: Int, targets: List<Player>): Int {
        val id = StringArgumentType.getString(context, "id")
        if (plugin.stackFor(id) == null) {
            reply(context, "There is no block or item with the id '$id'.", NamedTextColor.RED)
            return 0
        }
        if (targets.isEmpty()) {
            reply(context, "Name a player to give it to.", NamedTextColor.RED)
            return 0
        }
        targets.forEach { player ->
            val left = player.inventory.addItem(plugin.stackFor(id, amount)!!)
            left.values.forEach { player.world.dropItemNaturally(player.location, it) }
        }
        reply(context, "Gave $amount x $id to ${targets.size} player(s).", NamedTextColor.GREEN)
        return Command.SINGLE_SUCCESS
    }

    private fun reloadPack(context: CommandContext<CommandSourceStack>, config: Boolean, host: Boolean): Int {
        if (config) plugin.reloadConfig()
        if (host && plugin.sending) {
            val error = plugin.host.restart()
            if (error != null) reply(context, error, NamedTextColor.RED) else reply(context, "Pack server ${plugin.host.description}.", NamedTextColor.GREEN)
        }
        reply(context, "Reloading and building the pack...", NamedTextColor.GRAY)
        plugin.rebuildAndPush { report -> showBuild(context, report) }
        return Command.SINGLE_SUCCESS
    }

    private fun reload(context: CommandContext<CommandSourceStack>, blocks: Boolean, config: Boolean, host: Boolean): Int {
        if (config) plugin.reloadConfig()
        if (blocks) {
            val errors = plugin.reloadDefinitions()
            errors.take(MAX_LINES).forEach { reply(context, it, NamedTextColor.RED) }
            reply(context, "Loaded ${plugin.blocks.types.size} block type(s)${if (errors.isEmpty()) "" else ", ${errors.size} with errors"}.", NamedTextColor.GREEN)
        }
        if (host && plugin.sending) {
            val error = plugin.host.restart()
            if (error != null) reply(context, error, NamedTextColor.RED) else reply(context, "Pack server ${plugin.host.description}.", NamedTextColor.GREEN)
        }
        if (config && !host) reply(context, "Config reloaded. Run /repack reload host to apply a changed port.", NamedTextColor.GREEN)
        return Command.SINGLE_SUCCESS
    }

    private fun builder(context: CommandContext<CommandSourceStack>, id: String?): Int {
        val player = context.source.sender as? Player
        if (player == null) {
            reply(context, "Only players can open the builder.", NamedTextColor.RED)
            return 0
        }
        if (id == null) {
            plugin.gui.open(player, io.github.ratr2.repack.gui.ListScreen(plugin.gui, player))
        } else if (plugin.gui.draftOf(player, id.lowercase()) == null) {
            reply(context, "There is no block with the id '$id'.", NamedTextColor.RED)
            return 0
        } else {
            plugin.gui.open(player, io.github.ratr2.repack.gui.OverviewScreen(plugin.gui, player, id.lowercase()))
        }
        return Command.SINGLE_SUCCESS
    }

    private fun short(entry: PackEntry) = entry.sha1.take(8)

    private fun reply(context: CommandContext<CommandSourceStack>, text: String, color: NamedTextColor) {
        context.source.sender.sendMessage(Component.text(text, color))
    }

    companion object {
        const val ADMIN = "repack.admin"
        const val SETTINGS = "repack.settings"
        private const val MAX_LINES = 5
    }
}
