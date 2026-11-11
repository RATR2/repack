package io.github.ratr2.repack.distortion

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.RepackCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.FinePositionResolver
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Location
import org.bukkit.entity.Player

/**
 * ```
 * /repack distortion spawn <hex> <radius> <ticks> [x y z]
 * /repack distortion debug [on|off]
 * ```
 * Also registered as `/distortion`, which is what the plugin was called before it joined Repack.
 */
class DistortionCommand(private val plugin: Repack) {

    private fun allowed(source: CommandSourceStack, permission: String) =
        source.sender.hasPermission(permission) || source.sender.hasPermission(RepackCommand.ADMIN)

    fun tree(name: String): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal(name)
        .requires { allowed(it, SPAWN) || allowed(it, DEBUG) || allowed(it, SHAKE) || allowed(it, IMPACT) }
        .then(shakeTree())
        .then(impactTree())
        .then(
            Commands.literal("spawn")
                .requires { allowed(it, SPAWN) }
                .then(
                    Commands.argument("hex", StringArgumentType.string())
                        .then(
                            Commands.argument("radius", DoubleArgumentType.doubleArg(0.01, RippleManager.MAX_RADIUS))
                                .then(
                                    Commands.argument("ticks", IntegerArgumentType.integer(1))
                                        .executes { spawn(it, it.source.location) }
                                        .then(
                                            Commands.argument("pos", ArgumentTypes.finePosition(false))
                                                .executes {
                                                    val pos = it.getArgument("pos", FinePositionResolver::class.java).resolve(it.source)
                                                    spawn(it, pos.toLocation(it.source.location.world))
                                                }
                                        )
                                )
                        )
                )
        )
        .then(
            Commands.literal("debug")
                .requires { allowed(it, DEBUG) && it.sender is Player }
                .executes { debug(it, null) }
                .then(Commands.literal("on").executes { debug(it, true) })
                .then(Commands.literal("off").executes { debug(it, false) })
        )

    fun shakeTree(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("shake")
        .requires { allowed(it, SHAKE) }
        .then(
            Commands.argument("players", ArgumentTypes.players())
                .then(
                    Commands.argument("strength", DoubleArgumentType.doubleArg(0.0, 1.0))
                        .then(
                            Commands.argument("ticks", IntegerArgumentType.integer(1, 12000))
                                .executes { shake(it, 0.5, 0.5, 0.3) }
                                .then(
                                    Commands.argument("rate", DoubleArgumentType.doubleArg(0.0, 1.0))
                                        .then(
                                            Commands.argument("balance", DoubleArgumentType.doubleArg(0.0, 1.0))
                                                .then(
                                                    Commands.argument("roll", DoubleArgumentType.doubleArg(0.0, 1.0))
                                                        .executes {
                                                            shake(it, DoubleArgumentType.getDouble(it, "rate"), DoubleArgumentType.getDouble(it, "balance"), DoubleArgumentType.getDouble(it, "roll"))
                                                        }
                                                )
                                        )
                                )
                        )
                )
        )

    fun impactTree(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("impact")
        .requires { allowed(it, IMPACT) }
        .then(
            Commands.literal("scene")
                .then(Commands.literal("list").executes {
                    val ids = ImpactScenes.ids
                    it.source.sender.sendMessage(Component.text(if (ids.isEmpty()) "No impact frame scenes. Put files in plugins/Repack/impacts/." else "Scenes: ${ids.joinToString()}", NamedTextColor.YELLOW))
                    Command.SINGLE_SUCCESS
                })
                .then(Commands.literal("reload").executes {
                    val errors = plugin.reloadImpactScenes()
                    errors.take(8).forEach { error -> it.source.sender.sendMessage(Component.text(error, NamedTextColor.RED)) }
                    it.source.sender.sendMessage(Component.text("Loaded ${ImpactScenes.ids.size} scene(s)${if (errors.isEmpty()) "" else ", ${errors.size} problem(s)"}.", NamedTextColor.GREEN))
                    Command.SINGLE_SUCCESS
                })
                .then(
                    Commands.argument("players", ArgumentTypes.players())
                        .then(
                            Commands.argument("name", StringArgumentType.word())
                                .suggests { _, builder ->
                                    ImpactScenes.ids.filter { it.startsWith(builder.remainingLowerCase) }.forEach(builder::suggest)
                                    builder.buildFuture()
                                }
                                .executes { scene(it, null, null) }
                                .then(
                                    Commands.argument("ticks", IntegerArgumentType.integer(1, ImpactManager.MAX_TICKS))
                                        .executes { scene(it, IntegerArgumentType.getInteger(it, "ticks"), null) }
                                        .then(
                                            Commands.argument("hex", StringArgumentType.word())
                                                .executes { scene(it, IntegerArgumentType.getInteger(it, "ticks"), StringArgumentType.getString(it, "hex")) }
                                        )
                                )
                        )
                )
        )
        .then(
            Commands.argument("players", ArgumentTypes.players())
                .then(
                    Commands.argument("ticks", IntegerArgumentType.integer(1, ImpactManager.MAX_TICKS))
                        .then(
                            Commands.argument("type", StringArgumentType.word())
                                .suggests { _, builder ->
                                    ImpactManager.Type.entries.map { it.id }.filter { it.startsWith(builder.remainingLowerCase) }.forEach(builder::suggest)
                                    builder.buildFuture()
                                }
                                .then(
                                    Commands.argument("size", DoubleArgumentType.doubleArg(ImpactManager.MIN_SIZE, ImpactManager.MAX_SIZE))
                                        .executes { impact(it, null, "ffffff") }
                                        .then(
                                            Commands.argument("pos", ArgumentTypes.finePosition(false))
                                                .executes { impact(it, it.getArgument("pos", FinePositionResolver::class.java).resolve(it.source), "ffffff") }
                                                .then(
                                                    Commands.argument("hex", StringArgumentType.word())
                                                        .executes {
                                                            impact(it, it.getArgument("pos", FinePositionResolver::class.java).resolve(it.source), StringArgumentType.getString(it, "hex"))
                                                        }
                                                        .then(
                                                            Commands.argument("angle", DoubleArgumentType.doubleArg(-360.0, 360.0))
                                                                .executes {
                                                                    impact(
                                                                        it, it.getArgument("pos", FinePositionResolver::class.java).resolve(it.source), StringArgumentType.getString(it, "hex"),
                                                                        DoubleArgumentType.getDouble(it, "angle"),
                                                                    )
                                                                }
                                                        )
                                                )
                                        )
                                )
                        )
                )
        )

    private fun scene(context: CommandContext<CommandSourceStack>, ticks: Int?, hex: String?): Int {
        val sender = context.source.sender
        if (DistortionAPI.impacts == null) {
            sender.sendMessage(Component.text("Impact frames are switched off (distortion.enabled in config.yml).", NamedTextColor.RED))
            return 0
        }
        val name = StringArgumentType.getString(context, "name")
        val scene = ImpactScenes.get(name)
        if (scene == null || scene.entries.isEmpty()) {
            sender.sendMessage(Component.text("There is no scene '$name' with focus points. /repack impact scene list names them.", NamedTextColor.RED))
            return 0
        }
        val rgb = if (hex == null) scene.rgb else hex.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)
        if (rgb == null) {
            sender.sendMessage(Component.text("Color must be 6 hex digits, e.g. ffffff.", NamedTextColor.RED))
            return 0
        }
        val targets = context.getArgument("players", io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver::class.java).resolve(context.source)
        val shown = targets.count { scene.play(it, ticks ?: scene.ticks, rgb) }
        sender.sendMessage(Component.text("Scene $name for $shown player${if (shown == 1) "" else "s"}." + if (targets.size > shown) " ${targets.size - shown} could not show it." else "", if (shown > 0) NamedTextColor.GREEN else NamedTextColor.YELLOW))
        return if (shown > 0) Command.SINGLE_SUCCESS else 0
    }

    /** Without a position the focus point is eight blocks ahead of each player's eyes. */
    private fun impact(context: CommandContext<CommandSourceStack>, position: io.papermc.paper.math.Position?, hex: String, angle: Double? = null): Int {
        val sender = context.source.sender
        val manager = DistortionAPI.impacts
        if (manager == null) {
            sender.sendMessage(Component.text("Impact frames are switched off (distortion.enabled in config.yml).", NamedTextColor.RED))
            return 0
        }
        val type = ImpactManager.Type.of(StringArgumentType.getString(context, "type"))
        if (type == null) {
            sender.sendMessage(Component.text("The type is one of ${ImpactManager.Type.entries.joinToString { it.id }}.", NamedTextColor.RED))
            return 0
        }
        val rgb = hex.removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)
        if (rgb == null) {
            sender.sendMessage(Component.text("Color must be 6 hex digits, e.g. ffffff.", NamedTextColor.RED))
            return 0
        }
        val targets = context.getArgument("players", io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver::class.java).resolve(context.source)
        val ticks = IntegerArgumentType.getInteger(context, "ticks")
        val size = DoubleArgumentType.getDouble(context, "size")
        val shown = targets.count { player ->
            val at = position?.let { Location(player.world, it.x(), it.y(), it.z()) } ?: player.eyeLocation.add(player.eyeLocation.direction.multiply(AHEAD))
            manager.start(player, ticks, rgb, listOf(ImpactManager.Focus(at, type, size, screenAngle = angle)))
        }
        val skipped = targets.size - shown
        sender.sendMessage(Component.text("Impact frame for $shown player${if (shown == 1) "" else "s"}." + if (skipped > 0) " $skipped could not show it (they need the pack and a 1.21.6 or newer client)." else "", if (shown > 0) NamedTextColor.GREEN else NamedTextColor.YELLOW))
        return if (shown > 0) Command.SINGLE_SUCCESS else 0
    }

    private fun shake(context: CommandContext<CommandSourceStack>, rate: Double, balance: Double, roll: Double): Int {
        val sender = context.source.sender
        val manager = DistortionAPI.shakes
        if (manager == null) {
            sender.sendMessage(Component.text("Screen shake is switched off (distortion.enabled in config.yml).", NamedTextColor.RED))
            return 0
        }
        val targets = context.getArgument("players", io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver::class.java).resolve(context.source)
        val strength = DoubleArgumentType.getDouble(context, "strength")
        val ticks = IntegerArgumentType.getInteger(context, "ticks")
        val shaken = targets.count { manager.start(it, strength, ticks, rate, balance, roll) }
        val skipped = targets.size - shaken
        sender.sendMessage(Component.text("Shaking $shaken player${if (shaken == 1) "" else "s"}." + if (skipped > 0) " $skipped could not show it (they need the pack and a 1.21.6 or newer client)." else "", if (shaken > 0) NamedTextColor.GREEN else NamedTextColor.YELLOW))
        return if (shaken > 0) Command.SINGLE_SUCCESS else 0
    }

    private fun ripples(context: CommandContext<CommandSourceStack>): RippleManager? {
        val manager = DistortionAPI.manager
        if (manager == null) context.source.sender.sendMessage(Component.text("Distortion effects are switched off (distortion.enabled in config.yml).", NamedTextColor.RED))
        return manager
    }

    private fun debug(context: CommandContext<CommandSourceStack>, enabled: Boolean?): Int {
        val manager = ripples(context) ?: return 0
        val player = context.source.sender as Player
        val on = enabled ?: !manager.isDebugging(player)
        if (!manager.setDebug(player, on)) {
            player.sendMessage(Component.text("Debug view is only available on 26.3 and later clients.", NamedTextColor.RED))
            return 0
        }
        val state = if (on) "on: outlines + center crosses per ripple, raw data strip in the corner" else "off"
        player.sendMessage(Component.text("Distortion debug $state.", NamedTextColor.YELLOW))
        return Command.SINGLE_SUCCESS
    }

    private fun spawn(context: CommandContext<CommandSourceStack>, location: Location): Int {
        val manager = ripples(context) ?: return 0
        val sender = context.source.sender
        val rgb = StringArgumentType.getString(context, "hex").removePrefix("#").takeIf { it.length == 6 }?.toIntOrNull(16)
        if (rgb == null) {
            sender.sendMessage(Component.text("Color must be 6 hex digits, e.g. 55CCFF.", NamedTextColor.RED))
            return 0
        }
        val radius = DoubleArgumentType.getDouble(context, "radius")
        val ticks = IntegerArgumentType.getInteger(context, "ticks")
        if (!manager.spawn(location, rgb, radius, ticks)) {
            sender.sendMessage(Component.text("All ${RippleManager.SLOTS} ripple slots are in use.", NamedTextColor.RED))
            return 0
        }
        return Command.SINGLE_SUCCESS
    }

    companion object {
        const val SPAWN = "repack.distortion.spawn"
        const val DEBUG = "repack.distortion.debug"
        const val SHAKE = "repack.distortion.shake"
        const val IMPACT = "repack.distortion.impact"
        private const val AHEAD = 8.0
    }
}
