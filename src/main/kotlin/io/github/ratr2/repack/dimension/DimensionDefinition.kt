package io.github.ratr2.repack.dimension

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.configuration.ConfigurationSection

/** What a generated block is: a vanilla material, or a Repack custom block by id (written `repack:id`). */
sealed interface BlockSpec {
    data class Vanilla(val material: Material) : BlockSpec
    data class Custom(val id: String) : BlockSpec
}

/** Weighted blocks: one is picked per position, from a number in 0..1 that depends on the position, so the same seed gives the same world. */
class Palette(private val entries: List<Pair<BlockSpec, Double>>) {
    private val total = entries.sumOf { it.second }

    fun pick(u: Double): BlockSpec {
        var left = u * total
        for ((spec, weight) in entries) {
            left -= weight
            if (left < 0) return spec
        }
        return entries.last().first
    }

    val specs: List<BlockSpec> get() = entries.map { it.first }
    val isSingle: Boolean get() = entries.size == 1
}

class NoiseSpec(
    val type: Noise.Type = Noise.Type.PERLIN,
    val fractal: Noise.Fractal = Noise.Fractal.FBM,
    val frequency: Double = 0.01,
    val octaves: Int = 4,
    val lacunarity: Double = 2.0,
    val gain: Double = 0.5,
    val offset: Long = 0,
) {
    fun build(seed: Long) = Noise(type, fractal, frequency, octaves, lacunarity, gain, seed + offset)
}

class Layer(val palette: Palette, val depth: Int?)

class CaveSpec(val noise: NoiseSpec, val threshold: Double, val minY: Int, val maxY: Int)

class Feature(
    val name: String, val palette: Palette, val replace: Set<Material>?, val count: Int, val chance: Double, val size: Int, val minY: Int, val maxY: Int,
)

/** A small building from layers of text: `layers` go from the bottom up, each is a list of rows (along z) of characters (along x), and `palette` says what each character is. */
class Structure(
    val name: String, val width: Int, val depth: Int, val height: Int, val cells: Array<Array<Array<BlockSpec?>>>,
    val chance: Double, val count: Int, val rotate: Boolean, val offsetY: Int, val onSurface: Boolean, val minY: Int, val maxY: Int,
)

class DimensionDefinition(
    val name: String,
    val world: String,
    val environment: World.Environment,
    val seed: Long?,
    val base: Base,
    val seaLevel: Int,
    val baseHeight: Double,
    val amplitude: Double,
    val terrain: NoiseSpec,
    val layers: List<Layer>,
    val fluid: Material,
    val bedrock: Boolean,
    val caves: CaveSpec?,
    val biomes: List<String>,
    val biomeNoise: NoiseSpec,
    val features: List<Feature>,
    val structures: List<Structure>,
    val hideBuried: Boolean,
    val renderDistance: Int?,
) {
    /** VANILLA keeps the game's own generation and only adds features and structures on top; NOISE makes the terrain from noise; VOID and FLAT are empty and flat. */
    enum class Base { VANILLA, NOISE, VOID, FLAT }

    val customIds: Set<String> by lazy {
        val all = HashSet<BlockSpec>()
        layers.forEach { all += it.palette.specs }
        features.forEach { all += it.palette.specs }
        structures.forEach { s -> s.cells.forEach { a -> a.forEach { b -> b.forEach { if (it != null) all += it } } } }
        all.filterIsInstance<BlockSpec.Custom>().map { it.id }.toSet()
    }
}

object DimensionParser {

    const val MAX_STRUCTURE = 16
    private const val MAX_COUNT = 256

    class Result(val definition: DimensionDefinition?, val problems: List<String>)

    fun parse(name: String, yaml: ConfigurationSection): Result {
        val problems = ArrayList<String>()
        fun bad(message: String) { problems += "$name: $message" }

        val base = enumOf<DimensionDefinition.Base>(yaml.getString("base") ?: "vanilla") ?: return Result(null, listOf("$name: base is vanilla, noise, void or flat."))
        val environment = when ((yaml.getString("environment") ?: "normal").lowercase()) {
            "normal" -> World.Environment.NORMAL
            "nether" -> World.Environment.NETHER
            "end", "the_end" -> World.Environment.THE_END
            else -> { bad("environment is normal, nether or end."); World.Environment.NORMAL }
        }
        val seaLevel = yaml.getInt("sea-level", 62).coerceIn(-64, 300)
        val fluid = Material.matchMaterial(yaml.getString("fluid") ?: "water")?.takeIf { it == Material.WATER || it == Material.LAVA || it.isAir } ?: Material.WATER
        val terrainSection = yaml.getConfigurationSection("terrain")
        val layers = ArrayList<Layer>()
        for (raw in yaml.getMapList("layers")) {
            val section = sectionOf(raw)
            val palette = palette(section, "layers", ::bad) ?: continue
            val depth = if (section.contains("depth")) section.getInt("depth").coerceIn(1, 400) else null
            layers += Layer(palette, depth)
        }
        if (base == DimensionDefinition.Base.NOISE || base == DimensionDefinition.Base.FLAT) {
            if (layers.isEmpty()) {
                layers += Layer(Palette(listOf(BlockSpec.Vanilla(Material.GRASS_BLOCK) to 1.0)), 1)
                layers += Layer(Palette(listOf(BlockSpec.Vanilla(Material.DIRT) to 1.0)), 3)
                layers += Layer(Palette(listOf(BlockSpec.Vanilla(Material.STONE) to 1.0)), null)
            }
        }
        val caves = yaml.getConfigurationSection("caves")?.let {
            CaveSpec(noise(it.getConfigurationSection("noise"), ::bad, 0.035), it.getDouble("threshold", 0.55).coerceIn(-1.0, 1.0), it.getInt("min-y", 5), it.getInt("max-y", 60))
        }
        val biomes = yaml.getStringList("biomes").ifEmpty { listOfNotNull(yaml.getString("biome")) }
        val features = yaml.getMapList("features").mapNotNull { feature(sectionOf(it), ::bad) }
        val structures = yaml.getMapList("structures").mapNotNull { structure(sectionOf(it), ::bad) }
        val definition = DimensionDefinition(
            name = name,
            world = (yaml.getString("world") ?: name).lowercase().replace(Regex("[^a-z0-9_.-]"), "_"),
            environment = environment,
            seed = if (yaml.contains("seed")) yaml.getString("seed")!!.let { it.toLongOrNull() ?: it.hashCode().toLong() } else null,
            base = base,
            seaLevel = seaLevel,
            baseHeight = yaml.getDouble("base-height", 64.0).coerceIn(-64.0, 300.0),
            amplitude = yaml.getDouble("amplitude", 24.0).coerceIn(0.0, 300.0),
            terrain = noise(terrainSection?.getConfigurationSection("noise") ?: terrainSection, ::bad, 0.01),
            layers = layers,
            fluid = fluid,
            bedrock = yaml.getBoolean("bedrock", true),
            caves = caves,
            biomes = biomes,
            biomeNoise = noise(yaml.getConfigurationSection("biome-noise"), ::bad, 0.004),
            features = features,
            structures = structures,
            hideBuried = yaml.getBoolean("hide-buried", true),
            renderDistance = if (yaml.contains("render-distance")) yaml.getInt("render-distance").coerceIn(0, 512) else null,
        )
        return Result(definition, problems)
    }

    private inline fun <reified T : Enum<T>> enumOf(text: String): T? = enumValues<T>().firstOrNull { it.name.equals(text.replace('-', '_'), true) }

    private fun sectionOf(raw: Map<*, *>): ConfigurationSection {
        val section = org.bukkit.configuration.MemoryConfiguration()
        fun put(into: ConfigurationSection, map: Map<*, *>) {
            for ((k, v) in map) {
                val key = k.toString()
                if (v is Map<*, *>) put(into.createSection(key), v) else into.set(key, v)
            }
        }
        put(section, raw)
        return section
    }

    fun noise(section: ConfigurationSection?, bad: (String) -> Unit, defaultFrequency: Double): NoiseSpec {
        if (section == null) return NoiseSpec(frequency = defaultFrequency)
        val type = enumOf<Noise.Type>(section.getString("type") ?: "perlin") ?: Noise.Type.PERLIN.also { bad("noise type is perlin, value or cellular.") }
        val fractal = enumOf<Noise.Fractal>(section.getString("fractal") ?: "fbm") ?: Noise.Fractal.FBM.also { bad("noise fractal is none, fbm, ridged or ping-pong.") }
        return NoiseSpec(
            type, fractal, section.getDouble("frequency", defaultFrequency).coerceIn(1.0e-5, 1.0), section.getInt("octaves", 4).coerceIn(1, 8),
            section.getDouble("lacunarity", 2.0).coerceIn(1.0, 4.0), section.getDouble("gain", 0.5).coerceIn(0.0, 1.0), section.getLong("seed-offset", 0),
        )
    }

    fun spec(text: String, bad: (String) -> Unit): BlockSpec? {
        val t = text.trim()
        val lower = t.lowercase()
        if (lower.startsWith("repack:") || lower.startsWith("custom:")) return BlockSpec.Custom(lower.substringAfter(':'))
        val material = Material.matchMaterial(t)
        if (material == null || !(material.isBlock || material.isAir)) {
            bad("'$text' is not a block.")
            return null
        }
        return BlockSpec.Vanilla(material)
    }

    private fun palette(section: ConfigurationSection, where: String, bad: (String) -> Unit): Palette? {
        val entries = ArrayList<Pair<BlockSpec, Double>>()
        section.getString("block")?.let { text -> spec(text, bad)?.let { entries += it to 1.0 } }
        section.getConfigurationSection("blocks")?.let { blocks ->
            for (key in blocks.getKeys(false)) {
                val weight = blocks.getDouble(key, 1.0)
                if (weight <= 0 || !weight.isFinite()) continue
                spec(key, bad)?.let { entries += it to weight }
            }
        }
        if (entries.isEmpty()) {
            bad("an entry in $where needs block: or blocks:.")
            return null
        }
        return Palette(entries)
    }

    private fun feature(section: ConfigurationSection, bad: (String) -> Unit): Feature? {
        val palette = palette(section, "features", bad) ?: return null
        val replace = section.getStringList("replace").mapNotNull { Material.matchMaterial(it) }.toSet().takeIf { section.contains("replace") }
        return Feature(
            section.getString("name") ?: "feature", palette, replace, section.getInt("count", 8).coerceIn(1, MAX_COUNT), section.getDouble("chance", 1.0).coerceIn(0.0, 1.0),
            section.getInt("size", 6).coerceIn(1, 15), section.getInt("min-y", -64), section.getInt("max-y", 320),
        )
    }

    private fun structure(section: ConfigurationSection, bad: (String) -> Unit): Structure? {
        val name = section.getString("name") ?: "structure"
        val legend = HashMap<Char, BlockSpec?>()
        section.getConfigurationSection("palette")?.let { p ->
            for (key in p.getKeys(false)) {
                if (key.length != 1) { bad("structure $name: palette keys are single characters, not '$key'."); continue }
                legend[key[0]] = spec(p.getString(key) ?: "", bad)
            }
        }
        val layers = section.getList("layers")?.map { layer -> (layer as? List<*>)?.map { it.toString() } ?: listOf(layer.toString()) }.orEmpty()
        if (layers.isEmpty()) { bad("structure $name has no layers."); return null }
        val depth = layers.maxOf { it.size }
        val width = layers.maxOf { l -> l.maxOfOrNull { it.length } ?: 0 }
        if (width > MAX_STRUCTURE || depth > MAX_STRUCTURE || width == 0) { bad("structure $name must fit in a chunk: at most $MAX_STRUCTURE by $MAX_STRUCTURE blocks."); return null }
        val cells = Array(layers.size) { y -> Array(depth) { z -> Array<BlockSpec?>(width) { x -> layers[y].getOrNull(z)?.getOrNull(x)?.let { legend[it] } } } }
        return Structure(
            name, width, depth, layers.size, cells, section.getDouble("chance", 0.05).coerceIn(0.0, 1.0), section.getInt("count", 1).coerceIn(1, 16), section.getBoolean("rotate", true),
            section.getInt("offset-y", 0), section.getBoolean("surface", true), section.getInt("min-y", -64), section.getInt("max-y", 320),
        )
    }
}
