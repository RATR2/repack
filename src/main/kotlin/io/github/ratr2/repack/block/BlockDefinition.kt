package io.github.ratr2.repack.block

import org.bukkit.Material
import java.util.Random

/** vanilla: the proxy block's own mining speed applies. custom: `breaking.time` applies instead. Drops always come from `drops`. */
enum class ProxyMode { VANILLA, CUSTOM }

/** What a piston does when it would push or pull the block. */
enum class PistonBehavior { BLOCK, DESTROY }

enum class ToolType(val suffix: String?) {
    ANY(null),
    PICKAXE("_PICKAXE"),
    AXE("_AXE"),
    SHOVEL("_SHOVEL"),
    HOE("_HOE"),
    SWORD("_SWORD"),
    SHEARS("SHEARS"),
}

/** Minimum tool material; golden counts as wooden, as in vanilla. */
enum class ToolTier(val prefix: String) {
    WOODEN("WOODEN_"),
    STONE("STONE_"),
    IRON("IRON_"),
    DIAMOND("DIAMOND_"),
    NETHERITE("NETHERITE_"),
}

class AmountRange(val min: Int, val max: Int) {
    fun roll(random: Random): Int = if (max <= min) min else min + random.nextInt(max - min + 1)

    companion object {
        /** Accepts `3` and `1-3`. */
        fun parse(text: String): AmountRange? {
            val parts = text.trim().split('-')
            val min = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: return null
            val max = if (parts.size > 1) parts[1].trim().toIntOrNull() ?: return null else min
            return if (min < 0 || max < min) null else AmountRange(min, max)
        }
    }
}

sealed interface Drop {
    val chance: Double
    val amount: AmountRange
}

/** A vanilla item. The material is looked up on first use, because Material needs a running server. */
class ItemDrop(val materialName: String, override val amount: AmountRange, override val chance: Double) : Drop {
    val material: Material by lazy { Material.matchMaterial(materialName)!! }
}

/** The custom block's own item, so it can be picked up and placed again. */
class SelfDrop(override val amount: AmountRange, override val chance: Double) : Drop

class Breaking(
    /** Seconds to break with a suitable tool. */
    val time: Double,
    val tool: ToolType,
    val tier: ToolTier?,
    /** When true, breaking without the right tool gives no drops (the block still breaks). */
    val dropsNeedTool: Boolean,
    val xp: Int,
)

/**
 * Which of the model's Display tab settings the placed block uses. Blockbench's Display tab sets how the model looks in the hand,
 * in the inventory, on the ground, in an item frame and on a head; those always come along with the model. This picks the one the
 * block itself is drawn with. NONE draws the model as it is, with none of them.
 */
enum class DisplayContext(val value: Byte) {
    NONE(0),
    THIRDPERSON_LEFTHAND(1),
    THIRDPERSON_RIGHTHAND(2),
    FIRSTPERSON_LEFTHAND(3),
    FIRSTPERSON_RIGHTHAND(4),
    HEAD(5),
    GUI(6),
    GROUND(7),
    FIXED(8),
}

class DisplayOptions(
    val offsetX: Double,
    val offsetY: Double,
    val offsetZ: Double,
    val scaleX: Float,
    val scaleY: Float,
    val scaleZ: Float,
    /** Multiplier on the client's display entity view distance (1.0 is 64 blocks). */
    val viewRange: Float,
    /** Fixed light level 0 to 15 for the model, or null to light it like the world around it. */
    val brightness: Int?,
    /** Turn the model to face the player who places it, in 90 degree steps. */
    val rotateToPlacer: Boolean,
    val context: DisplayContext,
)

class ItemOptions(
    val baseName: String,
    val name: String?,
    val lore: List<String>,
    val glint: Boolean,
    /** A different .bbmodel for the item than the block's, or null to show the block's model. */
    val model: String? = null,
) {
    val base: Material by lazy { Material.matchMaterial(baseName)!! }
}

/** A shaped crafting recipe for the block's item. shape has up to three rows of up to three characters, a space is empty. */
class RecipeOptions(
    val shape: List<String>,
    /** Material name for each character used in shape. */
    val ingredients: Map<Char, String>,
    val amount: Int,
    val group: String?,
    /** building, redstone, equipment or misc. */
    val category: String?,
)

/** Everything one custom block type can be configured with. See the example file the plugin writes to `blocks/_example.yml`. */
class BlockDefinition(
    val id: String,
    /** The .bbmodel file (without extension) this block shows. */
    val model: String,
    val proxyBlockName: String,
    val proxyMode: ProxyMode,
    /** When false (the default) right-clicking does not do what the proxy block normally does, such as opening a smithing table. */
    val vanillaInteraction: Boolean,
    val breaking: Breaking,
    val drops: List<Drop>,
    val explosionResistant: Boolean,
    val explosionDrops: Boolean,
    val piston: PistonBehavior,
    val display: DisplayOptions,
    val item: ItemOptions,
    val recipe: RecipeOptions?,
) {
    val proxyBlock: Material by lazy { Material.matchMaterial(proxyBlockName)!! }
}
