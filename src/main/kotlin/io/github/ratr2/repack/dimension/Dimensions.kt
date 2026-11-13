package io.github.ratr2.repack.dimension

import io.github.ratr2.repack.Repack
import org.bukkit.Bukkit
import org.bukkit.Chunk
import org.bukkit.World
import org.bukkit.WorldCreator
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.generator.ChunkGenerator
import java.io.File

/**
 * Dimensions are extra worlds Repack generates, from `dimensions/<name>.yml` or from the API ([define]). The generator runs off the main thread; here the main
 * thread picks up what it noted: each tick it registers the custom blocks of chunks that have loaded, within a time budget.
 */
class Dimensions(private val plugin: Repack) {

    private val generators = LinkedHashMap<String, DimensionGenerator>()
    val folder: File get() = File(plugin.dataFolder, "dimensions")

    val names: Set<String> get() = generators.keys

    fun definition(name: String): DimensionDefinition? = generators[name]?.definition

    fun generatorFor(name: String): ChunkGenerator? = generators[name]

    /** True when [world] is a dimension that does not send displays of blocks with no open side. */
    fun hidesBuried(world: World): Boolean = generators.values.any { it.definition.world == world.name && it.definition.hideBuried }

    /** The dimension's own `render-distance`, or null when [world] is not one or the file sets none. */
    fun renderDistanceOf(world: World): Int? = generators.values.firstOrNull { it.definition.world == world.name }?.definition?.renderDistance

    fun start() {
        plugin.server.scheduler.runTaskTimer(plugin, Runnable(::drain), 1L, 1L)
    }

    /** Reads the files and makes the worlds. Problems come back as text. A dimension that is already running keeps its generator until a restart. */
    fun load(): List<String> {
        val problems = ArrayList<String>()
        folder.mkdirs()
        val files = folder.listFiles { f -> f.isFile && f.extension in setOf("yml", "yaml") && !f.name.startsWith("_") }.orEmpty().sortedBy { it.name }
        for (file in files) problems += define(file.nameWithoutExtension.lowercase(), YamlConfiguration.loadConfiguration(file))
        return problems
    }

    /** Defines a dimension from a config section (a file's, or one built in code) and creates its world. Returns the problems found; an empty list means it is running. */
    fun define(name: String, yaml: ConfigurationSection): List<String> {
        if (name in generators) return listOf("$name: already defined; restart the server to change a dimension that exists.")
        val result = DimensionParser.parse(name, yaml)
        val definition = result.definition ?: return result.problems
        val problems = result.problems.toMutableList()
        definition.customIds.filter { plugin.blocks.definitionOf(it) == null }.forEach { problems += "$name: there is no custom block '$it'; it is left out." }
        val generator = DimensionGenerator(definition, plugin.blocks::definitionOf)
        generators[name] = generator
        if (yaml.getBoolean("enabled", true)) {
            val world = create(definition, generator)
            if (world == null) problems += "$name: the world ${definition.world} could not be created."
        }
        return problems
    }

    private fun create(definition: DimensionDefinition, generator: DimensionGenerator): World? {
        Bukkit.getWorld(definition.world)?.let { return it }
        val creator = WorldCreator(definition.world).environment(definition.environment).generator(generator)
        definition.seed?.let { creator.seed(it) }
        return creator.createWorld()
    }

    private fun drain() {
        if (generators.isEmpty()) return
        val deadline = System.nanoTime() + BUDGET_NANOS
        for (generator in generators.values) {
            if (generator.pending.isEmpty()) continue
            val world = Bukkit.getWorld(generator.definition.world) ?: continue
            for (key in generator.pending.keys.toList()) {
                if (System.nanoTime() > deadline) return
                val x = key.toInt()
                val z = (key shr 32).toInt()
                if (!world.isChunkLoaded(x, z)) continue
                val list = generator.pending.remove(key) ?: continue
                val start = System.nanoTime()
                val added = plugin.blocks.adoptAll(world.getChunkAt(x, z), synchronized(list) { list.toList() }.map { Triple(it.x, it.y, it.z) to (it.id to it.yaw) })
                val took = System.nanoTime() - start
                DimensionStats.adoptNanos.addAndGet(took)
                DimensionStats.adoptChunks.incrementAndGet()
                DimensionStats.adoptedBlocks.addAndGet(added.toLong())
                DimensionStats.maxAdoptNanos.accumulateAndGet(took, ::maxOf)
            }
        }
    }

    private companion object {
        const val BUDGET_NANOS = 4_000_000L
    }
}
