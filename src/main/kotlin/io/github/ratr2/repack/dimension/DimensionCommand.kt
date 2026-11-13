package io.github.ratr2.repack.dimension

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.RepackCommand
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player

/**
 * ```
 * /repack dimension list                what is defined and whether its world is loaded
 * /repack dimension load                reads new files in dimensions/ and creates their worlds
 * /repack dimension tp <name>           teleports you to a dimension's or any world's spawn (overworld is the main world)
 * /repack dimension back               returns you to where you were before the last tp to another world
 * /repack dimension stats [reset]       generation and block registration costs, and the tick time
 * ```
 */
class DimensionCommand(private val plugin: Repack) {

    fun tree(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("dimension")
        .requires { it.sender.hasPermission(RepackCommand.ADMIN) }
        .then(Commands.literal("list").executes(::list))
        .then(Commands.literal("load").executes(::load))
        .then(
            Commands.literal("tp").then(
                Commands.argument("name", StringArgumentType.word())
                    .suggests { _, b -> (plugin.dimensions.names + Bukkit.getWorlds().map { it.name } + "overworld").filter { it.lowercase().startsWith(b.remainingLowerCase) }.distinct().forEach(b::suggest); b.buildFuture() }
                    .executes(::tp)
            )
        )
        .then(Commands.literal("back").executes(::back))
        .then(
            Commands.literal("pregen").then(
                Commands.argument("name", StringArgumentType.word())
                    .suggests { _, b -> plugin.dimensions.names.filter { it.startsWith(b.remainingLowerCase) }.forEach(b::suggest); b.buildFuture() }
                    .then(Commands.argument("radius", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 64)).executes(::pregen))
            )
        )
        .then(Commands.literal("release").executes(::release))
        .then(Commands.literal("stats").executes { stats(it, false) }.then(Commands.literal("reset").executes { stats(it, true) }))

    private fun say(context: CommandContext<CommandSourceStack>, text: String, color: NamedTextColor = NamedTextColor.GRAY) = context.source.sender.sendMessage(Component.text(text, color))

    private fun list(context: CommandContext<CommandSourceStack>): Int {
        if (plugin.dimensions.names.isEmpty()) say(context, "No dimensions. Add a file to plugins/Repack/dimensions/ (see _example.yml) and run /repack dimension load.")
        for (name in plugin.dimensions.names) {
            val definition = plugin.dimensions.definition(name) ?: continue
            val world = Bukkit.getWorld(definition.world)
            say(context, "$name: ${definition.base.name.lowercase()}, world ${definition.world} ${if (world != null) "(loaded, ${world.loadedChunks.size} chunks)" else "(not loaded)"}")
        }
        return Command.SINGLE_SUCCESS
    }

    private fun load(context: CommandContext<CommandSourceStack>): Int {
        val problems = plugin.dimensions.load()
        problems.forEach { say(context, it, NamedTextColor.YELLOW) }
        say(context, "Done. ${plugin.dimensions.names.size} dimension(s).", NamedTextColor.GREEN)
        return Command.SINGLE_SUCCESS
    }

    private val before = HashMap<java.util.UUID, org.bukkit.Location>()

    /** A dimension by name, any loaded world by name, or `overworld` for the server's main world. */
    private fun worldNamed(name: String): org.bukkit.World? {
        plugin.dimensions.definition(name)?.let { return Bukkit.getWorld(it.world) }
        Bukkit.getWorld(name)?.let { return it }
        return if (name == "overworld" || name == "main") Bukkit.getWorlds().firstOrNull() else null
    }

    private fun tp(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.sender as? Player ?: return say(context, "Players only.", NamedTextColor.RED).let { 0 }
        val world = worldNamed(StringArgumentType.getString(context, "name").lowercase()) ?: return say(context, "No such dimension or world. /repack dimension list shows them; overworld is the main world.", NamedTextColor.RED).let { 0 }
        // Coming from a different world, remember where, so /repack dimension back returns to it.
        if (player.world != world) before[player.uniqueId] = player.location
        player.teleportAsync(world.spawnLocation)
        return Command.SINGLE_SUCCESS
    }

    private fun back(context: CommandContext<CommandSourceStack>): Int {
        val player = context.source.sender as? Player ?: return say(context, "Players only.", NamedTextColor.RED).let { 0 }
        val at = before.remove(player.uniqueId) ?: Bukkit.getWorlds().firstOrNull()?.spawnLocation ?: return say(context, "Nowhere to go back to.", NamedTextColor.RED).let { 0 }
        player.teleportAsync(at)
        return Command.SINGLE_SUCCESS
    }

    private val held = ArrayList<Triple<org.bukkit.World, Int, Int>>()

    /** Generates and keeps loaded a square of chunks around the dimension's spawn, with no player, to measure generation. */
    private fun pregen(context: CommandContext<CommandSourceStack>): Int {
        val definition = plugin.dimensions.definition(StringArgumentType.getString(context, "name").lowercase()) ?: return say(context, "No such dimension.", NamedTextColor.RED).let { 0 }
        val world = Bukkit.getWorld(definition.world) ?: return say(context, "Its world is not loaded.", NamedTextColor.RED).let { 0 }
        val radius = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "radius")
        val start = System.nanoTime()
        var left = (2 * radius + 1) * (2 * radius + 1)
        say(context, "Generating $left chunks...")
        for (x in -radius..radius) for (z in -radius..radius) {
            world.getChunkAtAsync(x, z).thenAccept {
                // The chunk is loaded by now, so holding it costs nothing; asking before would load it on the main thread.
                world.addPluginChunkTicket(x, z, plugin)
                synchronized(held) { held += Triple(world, x, z) }
                if (--left == 0) plugin.server.scheduler.runTask(plugin, Runnable { say(context, "Generated in ${(System.nanoTime() - start) / 1_000_000} ms. /repack dimension stats shows the costs.", NamedTextColor.GREEN) })
            }
        }
        return Command.SINGLE_SUCCESS
    }

    private fun release(context: CommandContext<CommandSourceStack>): Int {
        held.forEach { (world, x, z) -> world.removePluginChunkTicket(x, z, plugin) }
        say(context, "Released ${held.size} chunks.", NamedTextColor.GREEN)
        held.clear()
        return Command.SINGLE_SUCCESS
    }

    private fun stats(context: CommandContext<CommandSourceStack>, reset: Boolean): Int {
        if (reset) {
            DimensionStats.reset()
            say(context, "Reset.", NamedTextColor.GREEN)
            return Command.SINGLE_SUCCESS
        }
        val s = DimensionStats
        fun ms(n: Long) = "%.2f".format(n / 1e6)
        val chunks = s.chunks.get().coerceAtLeast(1)
        val adopted = s.adoptChunks.get().coerceAtLeast(1)
        say(context, "Terrain: ${s.chunks.get()} chunks, ${ms(s.terrainNanos.get() / chunks)} ms each (off the main thread)")
        say(context, "Features and structures: ${ms(s.populateNanos.get() / chunks)} ms per chunk (off the main thread)")
        say(context, "Custom blocks written: ${s.customPlaced.get()}")
        say(context, "Registered on the main thread: ${s.adoptedBlocks.get()} blocks in ${s.adoptChunks.get()} chunks, ${ms(s.adoptNanos.get() / adopted)} ms per chunk, worst ${ms(s.maxAdoptNanos.get())} ms")
        say(context, "Tick: ${"%.2f".format(Bukkit.getAverageTickTime())} ms average, TPS ${Bukkit.getTPS().joinToString { "%.1f".format(it) }}")
        return Command.SINGLE_SUCCESS
    }
}
