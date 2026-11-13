package io.github.ratr2.repack.dimension

import io.github.ratr2.repack.block.BlockDefinition
import org.bukkit.Chunk
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.Biome
import org.bukkit.generator.BiomeProvider
import org.bukkit.generator.BlockPopulator
import org.bukkit.generator.ChunkGenerator
import org.bukkit.generator.LimitedRegion
import org.bukkit.generator.WorldInfo
import java.util.Collections
import java.util.Random
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What generation costs, for `/repack dimension stats`. */
object DimensionStats {
    val chunks = AtomicLong()
    val terrainNanos = AtomicLong()
    val populateNanos = AtomicLong()
    val customPlaced = AtomicLong()
    val adoptNanos = AtomicLong()
    val adoptChunks = AtomicLong()
    val adoptedBlocks = AtomicLong()
    val maxAdoptNanos = AtomicLong()

    fun reset() {
        listOf(chunks, terrainNanos, populateNanos, customPlaced, adoptNanos, adoptChunks, adoptedBlocks, maxAdoptNanos).forEach { it.set(0) }
    }
}

/**
 * Makes the chunks of a Repack dimension. It runs on the server's generator threads, so it never touches the plugin's block data: custom blocks are written as
 * their proxy material and noted in [pending], and the main thread registers them (see [Dimensions]) once the chunk is loaded.
 */
class DimensionGenerator(val definition: DimensionDefinition, private val lookup: (String) -> BlockDefinition?) : ChunkGenerator() {

    class Pending(val x: Int, val y: Int, val z: Int, val id: String, val yaw: Int)

    /** Custom blocks written into chunks that the main thread has not registered yet, by chunk key. */
    val pending = ConcurrentHashMap<Long, MutableList<Pending>>()

    private class Built(val terrain: Noise, val caves: Noise?, val biome: Noise)

    @Volatile private var built: Built? = null

    private fun built(seed: Long): Built {
        built?.let { return it }
        val made = Built(definition.terrain.build(seed), definition.caves?.noise?.build(seed + 1), definition.biomeNoise.build(seed + 2))
        built = made
        return made
    }

    private fun note(chunkX: Int, chunkZ: Int, x: Int, y: Int, z: Int, id: String, yaw: Int = 0) {
        pending.computeIfAbsent(Chunk.getChunkKey(chunkX, chunkZ)) { Collections.synchronizedList(ArrayList()) }.add(Pending(x, y, z, id, yaw))
        DimensionStats.customPlaced.incrementAndGet()
    }

    /** The material to write for a spec, and whether it is a custom block (whose id then goes to [pending]). Null when a custom id has no definition. */
    private fun materialOf(spec: BlockSpec): Material? = when (spec) {
        is BlockSpec.Vanilla -> spec.material
        is BlockSpec.Custom -> lookup(spec.id)?.proxyBlock
    }

    private fun hash(x: Int, y: Int, z: Int, salt: Long): Double {
        var h = salt * -0x61c8864680b583ebL xor (x * 0x632BE5ABL) xor (y * 0x85157AF5L) xor (z * 0x6C8E9CF5L)
        h = (h xor (h ushr 30)) * -0x40a7b892e31b1a47L
        h = (h xor (h ushr 27)) * -0x6b2fb644ecceee15L
        h = h xor (h ushr 31)
        return (h ushr 11).toDouble() / (1L shl 53).toDouble()
    }

    private fun heightAt(noise: Noise, x: Int, z: Int): Int = when (definition.base) {
        DimensionDefinition.Base.FLAT -> definition.baseHeight.toInt()
        else -> (definition.baseHeight + noise.at(x.toDouble(), z.toDouble()) * definition.amplitude).toInt()
    }

    override fun shouldGenerateNoise() = definition.base == DimensionDefinition.Base.VANILLA
    override fun shouldGenerateSurface() = definition.base == DimensionDefinition.Base.VANILLA
    override fun shouldGenerateBedrock() = definition.base == DimensionDefinition.Base.VANILLA
    override fun shouldGenerateCaves() = definition.base == DimensionDefinition.Base.VANILLA
    override fun shouldGenerateDecorations() = definition.base == DimensionDefinition.Base.VANILLA
    override fun shouldGenerateMobs() = definition.base == DimensionDefinition.Base.VANILLA
    override fun shouldGenerateStructures() = definition.base == DimensionDefinition.Base.VANILLA

    override fun generateNoise(info: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, data: ChunkData) {
        if (definition.base == DimensionDefinition.Base.VANILLA || definition.base == DimensionDefinition.Base.VOID) return
        val start = System.nanoTime()
        val noise = built(definition.seed ?: info.seed)
        val minY = info.minHeight
        val maxY = info.maxHeight
        // Each layer's material is resolved once; custom ones keep their id for the pending list.
        for (lx in 0 until 16) for (lz in 0 until 16) {
            val x = chunkX * 16 + lx
            val z = chunkZ * 16 + lz
            val top = heightAt(noise.terrain, x, z).coerceIn(minY, maxY - 1)
            for (y in minY..top) {
                if (definition.bedrock && y == minY) {
                    data.setBlock(lx, y, lz, Material.BEDROCK)
                    continue
                }
                if (noise.caves != null && definition.caves != null && y >= definition.caves.minY && y <= definition.caves.maxY && y < top - 2 &&
                    noise.caves.at(x.toDouble(), y.toDouble(), z.toDouble()) > definition.caves.threshold) continue
                val spec = layerSpec(top - y, x, y, z)
                val material = materialOf(spec) ?: continue
                data.setBlock(lx, y, lz, material)
                if (spec is BlockSpec.Custom) note(chunkX, chunkZ, x, y, z, spec.id)
            }
            if (!definition.fluid.isAir) {
                for (y in (top + 1)..minOf(definition.seaLevel, maxY - 1)) data.setBlock(lx, y, lz, definition.fluid)
            }
        }
        DimensionStats.terrainNanos.addAndGet(System.nanoTime() - start)
        DimensionStats.chunks.incrementAndGet()
    }

    private fun layerSpec(depthFromTop: Int, x: Int, y: Int, z: Int): BlockSpec {
        var left = depthFromTop
        for (layer in definition.layers) {
            val depth = layer.depth
            if (depth == null || left < depth) return layer.palette.pick(if (layer.palette.isSingle) 0.0 else hash(x, y, z, 11))
            left -= depth
        }
        return definition.layers.last().palette.pick(if (definition.layers.last().palette.isSingle) 0.0 else hash(x, y, z, 11))
    }

    override fun getDefaultPopulators(world: World): List<BlockPopulator> =
        if (definition.features.isEmpty() && definition.structures.isEmpty()) emptyList() else listOf(Populator())

    override fun getFixedSpawnLocation(world: World, random: Random): Location? {
        if (definition.base == DimensionDefinition.Base.VANILLA) return null
        val noise = built(definition.seed ?: world.seed)
        val y = if (definition.base == DimensionDefinition.Base.VOID) 64 else heightAt(noise.terrain, 0, 0).coerceIn(world.minHeight, world.maxHeight - 2) + 1
        return Location(world, 0.5, y.toDouble(), 0.5)
    }

    override fun getDefaultBiomeProvider(info: WorldInfo): BiomeProvider? {
        if (definition.biomes.isEmpty()) return null
        val list = definition.biomes.mapNotNull { name ->
            val key = org.bukkit.NamespacedKey.fromString(if (':' in name) name.lowercase() else "minecraft:${name.lowercase()}")
            key?.let { org.bukkit.Registry.BIOME.get(it) }
        }
        if (list.isEmpty()) return null
        val noise = built(definition.seed ?: info.seed).biome
        return object : BiomeProvider() {
            override fun getBiome(info: WorldInfo, x: Int, y: Int, z: Int): Biome {
                if (list.size == 1) return list[0]
                val v = (noise.at(x.toDouble(), z.toDouble()) + 1.0) / 2.0
                return list[(v * list.size).toInt().coerceIn(0, list.size - 1)]
            }

            override fun getBiomes(info: WorldInfo): List<Biome> = list
        }
    }

    private inner class Populator : BlockPopulator() {
        override fun populate(info: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, region: LimitedRegion) {
            val start = System.nanoTime()
            for (feature in definition.features) vein(feature, info, random, chunkX, chunkZ, region)
            for (structure in definition.structures) build(structure, info, random, chunkX, chunkZ, region)
            DimensionStats.populateNanos.addAndGet(System.nanoTime() - start)
        }
    }

    private fun vein(feature: Feature, info: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, region: LimitedRegion) {
        val low = maxOf(feature.minY, info.minHeight + 1)
        val high = minOf(feature.maxY, info.maxHeight - 1)
        if (high < low) return
        repeat(feature.count) {
            if (random.nextDouble() >= feature.chance) return@repeat
            val cx = chunkX * 16 + random.nextInt(16)
            val cz = chunkZ * 16 + random.nextInt(16)
            val cy = low + random.nextInt(high - low + 1)
            val r = feature.size / 2.0 + 0.5
            val ri = Math.ceil(r).toInt()
            for (dx in -ri..ri) for (dy in -ri..ri) for (dz in -ri..ri) {
                val d = (dx * dx + dy * dy + dz * dz) / (r * r)
                if (d > 1.0 || random.nextDouble() < d * 0.5) continue
                val x = cx + dx
                val y = cy + dy
                val z = cz + dz
                // Only inside the chunk being made, so nothing is written where its custom block data could not be kept.
                if (x shr 4 != chunkX || z shr 4 != chunkZ || y < info.minHeight || y >= info.maxHeight || !region.isInRegion(x, y, z)) continue
                val at = region.getType(x, y, z)
                if (feature.replace != null) { if (at !in feature.replace) continue } else if (at.isAir || at == Material.BEDROCK || at == Material.WATER || at == Material.LAVA) continue
                val spec = feature.palette.pick(if (feature.palette.isSingle) 0.0 else random.nextDouble())
                val material = materialOf(spec) ?: continue
                region.setType(x, y, z, material)
                if (spec is BlockSpec.Custom) note(chunkX, chunkZ, x, y, z, spec.id)
            }
        }
    }

    private fun surface(region: LimitedRegion, info: WorldInfo, x: Int, z: Int): Int? {
        var y = info.maxHeight - 1
        while (y > info.minHeight) {
            val type = region.getType(x, y, z)
            if (!type.isAir && type != Material.WATER && type != Material.LAVA && !Tag.LEAVES.isTagged(type) && !Tag.LOGS.isTagged(type)) return y + 1
            y--
        }
        return null
    }

    private fun build(structure: Structure, info: WorldInfo, random: Random, chunkX: Int, chunkZ: Int, region: LimitedRegion) {
        repeat(structure.count) {
            if (random.nextDouble() >= structure.chance) return@repeat
            val turns = if (structure.rotate) random.nextInt(4) else 0
            val w = if (turns % 2 == 0) structure.width else structure.depth
            val d = if (turns % 2 == 0) structure.depth else structure.width
            val ox = chunkX * 16 + random.nextInt(16 - w + 1)
            val oz = chunkZ * 16 + random.nextInt(16 - d + 1)
            val oy = (if (structure.onSurface) surface(region, info, ox + w / 2, oz + d / 2) ?: return@repeat else structure.minY + random.nextInt(maxOf(1, structure.maxY - structure.minY + 1))) + structure.offsetY
            if (oy < info.minHeight || oy + structure.height > info.maxHeight) return@repeat
            for (y in 0 until structure.height) for (z in 0 until structure.depth) for (x in 0 until structure.width) {
                val spec = structure.cells[y][z][x] ?: continue
                // Turn the template a quarter turn at a time around its corner, then shift it back into place.
                val (rx, rz) = when (turns) {
                    0 -> x to z
                    1 -> (structure.depth - 1 - z) to x
                    2 -> (structure.width - 1 - x) to (structure.depth - 1 - z)
                    else -> z to (structure.width - 1 - x)
                }
                val wx = ox + rx
                val wz = oz + rz
                val wy = oy + y
                if (!region.isInRegion(wx, wy, wz)) continue
                val material = materialOf(spec) ?: continue
                region.setType(wx, wy, wz, material)
                if (spec is BlockSpec.Custom) note(chunkX, chunkZ, wx, wy, wz, spec.id, turns * 90)
            }
        }
    }
}
