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
        .requires { allowed(it, SPAWN) || allowed(it, DEBUG) || allowed(it, SHAKE) }
        .then(shakeTree())
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
    }
}
