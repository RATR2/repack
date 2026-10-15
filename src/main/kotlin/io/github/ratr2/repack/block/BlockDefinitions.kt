package io.github.ratr2.repack.block

import org.bukkit.Material
import org.bukkit.configuration.ConfigurationSection
import org.bukkit.configuration.InvalidConfigurationException
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File

class DefinitionException(message: String) : Exception(message)

class LoadedDefinitions(val definitions: Map<String, BlockDefinition>, val errors: List<String>)

/** What the parser needs to know about materials, so tests can run without a server. */
interface MaterialRules {
    fun exists(name: String): Boolean
    fun isBlock(name: String): Boolean
    fun isItem(name: String): Boolean
}

object BukkitMaterials : MaterialRules {
    override fun exists(name: String) = Material.matchMaterial(name) != null
    override fun isBlock(name: String) = Material.matchMaterial(name)?.isBlock == true
    override fun isItem(name: String) = Material.matchMaterial(name)?.isItem == true
}

/**
 * Reads `blocks/<id>.yml`. A model with no file gets the defaults, so dropping a .bbmodel into `models/` is enough to have a
 * working block. Files starting with `_` are ignored (the plugin writes `_example.yml` there as documentation).
 */
object BlockDefinitions {

    /** Reads every block file, or just the ids in only. */
    fun load(blocksFolder: File, modelIds: Set<String>, only: Set<String>? = null): LoadedDefinitions {
        val definitions = LinkedHashMap<String, BlockDefinition>()
        val errors = ArrayList<String>()

        val files = blocksFolder.listFiles { f -> f.isFile && f.extension == "yml" && !f.name.startsWith("_") }.orEmpty().sortedBy { it.name }
        for (file in files) {
            val id = file.nameWithoutExtension.lowercase()
            if (only != null && id !in only) continue
            try {
                val yaml = YamlConfiguration().apply { load(file) }
                val definition = parse(id, yaml)
                if (definition.model !in modelIds) {
                    errors += "$id: model '${definition.model}' has no .bbmodel file in the models folder."
                    continue
                }
                val itemModel = definition.item.model
                if (itemModel != null && itemModel !in modelIds) {
                    errors += "$id: item.model '$itemModel' has no .bbmodel file in the models folder."
                    continue
                }
                definitions[id] = definition
            } catch (e: DefinitionException) {
                errors += "$id: ${e.message}"
            } catch (e: InvalidConfigurationException) {
                errors += "$id: not valid YAML (${e.message?.lineSequence()?.firstOrNull()})"
            }
        }
        for (model in modelIds.sorted()) {
            if (only != null && model !in only) continue
            if (model !in definitions && files.none { it.nameWithoutExtension.lowercase() == model }) {
                definitions[model] = parse(model, YamlConfiguration())
            }
        }
        return LoadedDefinitions(definitions, errors)
    }

    fun parse(id: String, section: ConfigurationSection, materials: MaterialRules = BukkitMaterials): BlockDefinition {
        val proxy = section.getConfigurationSection("proxy")
        val proxyBlock = material(proxy?.getString("block") ?: "BARRIER", "proxy.block", materials).also {
            if (!materials.isBlock(it)) throw DefinitionException("proxy.block $it is not a block.")
        }
        val mode = enumOf(proxy?.getString("mode") ?: "custom", ProxyMode::class.java, "proxy.mode")

        val breaking = section.getConfigurationSection("breaking")
        val time = breaking?.getDouble("time", 1.0) ?: 1.0
        if (time <= 0) throw DefinitionException("breaking.time must be above 0 seconds.")
        val breakingOptions = Breaking(
            time = time,
            tool = enumOf(breaking?.getString("tool") ?: "any", ToolType::class.java, "breaking.tool"),
            tier = breaking?.getString("tier")?.let { enumOf(it, ToolTier::class.java, "breaking.tier") },
            dropsNeedTool = breaking?.getBoolean("drops-need-tool", false) ?: false,
            xp = (breaking?.getInt("xp", 0) ?: 0).coerceAtLeast(0),
        )

        val explosion = section.getConfigurationSection("explosion")
        val display = section.getConfigurationSection("display")
        val offset = display?.getDoubleList("offset").orEmpty()
        if (offset.isNotEmpty() && offset.size != 3) throw DefinitionException("display.offset needs three numbers: x, y, z.")
        val scale = scaleOf(display)
        val brightness = display?.takeIf { it.contains("brightness") }?.getInt("brightness")
        if (brightness != null && brightness !in 0..15) throw DefinitionException("display.brightness must be 0 to 15.")

        val item = section.getConfigurationSection("item")
        val itemBase = material(item?.getString("base") ?: "PAPER", "item.base", materials).also {
            if (!materials.isItem(it)) throw DefinitionException("item.base $it is not an item.")
        }

        return BlockDefinition(
            id = id,
            model = section.getString("model")?.lowercase() ?: id,
            proxyBlockName = proxyBlock,
            proxyMode = mode,
            vanillaInteraction = proxy?.getBoolean("vanilla-interaction", false) ?: false,
            breaking = breakingOptions,
            drops = if (section.contains("drops")) drops(section.getMapList("drops"), materials) else listOf(SelfDrop(AmountRange(1, 1), 1.0)),
            explosionResistant = explosion?.getBoolean("resistant", false) ?: false,
            explosionDrops = explosion?.getBoolean("drops", false) ?: false,
            piston = enumOf(section.getString("piston") ?: "block", PistonBehavior::class.java, "piston"),
            display = DisplayOptions(
                offsetX = offset.getOrElse(0) { 0.0 },
                offsetY = offset.getOrElse(1) { 0.0 },
                offsetZ = offset.getOrElse(2) { 0.0 },
                scaleX = scale[0],
                scaleY = scale[1],
                scaleZ = scale[2],
                viewRange = display?.getDouble("view-range", 1.0)?.toFloat() ?: 1f,
                brightness = brightness,
                rotateToPlacer = display?.getBoolean("rotate-to-placer", false) ?: false,
                context = enumOf(display?.getString("context") ?: "none", DisplayContext::class.java, "display.context"),
            ),
            item = ItemOptions(
                baseName = itemBase,
                name = item?.getString("name"),
                lore = item?.getStringList("lore").orEmpty(),
                glint = item?.getBoolean("glint", false) ?: false,
                model = item?.getString("model")?.lowercase(),
            ),
            recipe = recipeOf(section.getConfigurationSection("recipe"), materials),
        )
    }

    private val CATEGORIES = setOf("building", "redstone", "equipment", "misc")

    private fun recipeOf(section: ConfigurationSection?, materials: MaterialRules): RecipeOptions? {
        section ?: return null
        val shape = section.getStringList("shape")
        if (shape.isEmpty() || shape.size > 3) throw DefinitionException("recipe.shape needs one to three rows.")
        if (shape.any { it.isEmpty() || it.length > 3 }) throw DefinitionException("recipe.shape rows need one to three characters.")
        if (shape.all { row -> row.all { it == ' ' } }) throw DefinitionException("recipe.shape has no ingredients in it.")
        val ingredients = LinkedHashMap<Char, String>()
        val given = section.getConfigurationSection("ingredients")
        for (key in given?.getKeys(false).orEmpty()) {
            if (key.length != 1) throw DefinitionException("recipe.ingredients key '$key' must be a single character.")
            val name = material(given!!.getString(key).orEmpty(), "recipe.ingredients.$key", materials)
            if (!materials.isItem(name)) throw DefinitionException("recipe.ingredients.$key $name is not an item.")
            ingredients[key[0]] = name
        }
        for (char in shape.joinToString("").toSet() - ' ') {
            if (char !in ingredients) throw DefinitionException("recipe.shape uses '$char' but recipe.ingredients has no '$char'.")
        }
        val amount = section.getInt("amount", 1)
        if (amount !in 1..64) throw DefinitionException("recipe.amount must be 1 to 64.")
        val category = section.getString("category")?.lowercase()
        if (category != null && category !in CATEGORIES) throw DefinitionException("recipe.category '$category' is not one of ${CATEGORIES.joinToString()}.")
        return RecipeOptions(shape, ingredients, amount, section.getString("group"), category)
    }

    /** `scale: 1.5` for all three axes, or `scale: [1.01, 1.15, 1.01]` for x, y and z. */
    private fun scaleOf(display: ConfigurationSection?): List<Float> {
        val raw = display?.get("scale") ?: return listOf(1f, 1f, 1f)
        val values = when (raw) {
            is Number -> List(3) { raw.toFloat() }
            is List<*> -> raw.map { (it as? Number)?.toFloat() ?: throw DefinitionException("display.scale needs numbers.") }
            else -> throw DefinitionException("display.scale is a number, or three numbers: x, y, z.")
        }
        if (values.size != 3) throw DefinitionException("display.scale needs one number, or three: x, y, z.")
        if (values.any { it <= 0f }) throw DefinitionException("display.scale must be above 0.")
        return values
    }

    private fun drops(entries: List<Map<*, *>>, materials: MaterialRules): List<Drop> = entries.mapIndexed { index, entry ->
        val where = "drops[$index]"
        val amount = (entry["amount"]?.toString() ?: "1").let { AmountRange.parse(it) ?: throw DefinitionException("$where.amount '$it' is not a number or a range like 1-3.") }
        val chance = (entry["chance"]?.toString() ?: "1").toDoubleOrNull()?.takeIf { it in 0.0..1.0 }
            ?: throw DefinitionException("$where.chance must be between 0 and 1.")
        when {
            entry["block"]?.toString().equals("self", ignoreCase = true) -> SelfDrop(amount, chance)
            entry["item"] != null -> ItemDrop(material(entry["item"].toString(), "$where.item", materials), amount, chance)
            else -> throw DefinitionException("$where needs either 'item: <MATERIAL>' or 'block: self'.")
        }
    }

    /** Returns the upper case material name, or throws if there is no such material. */
    private fun material(name: String, where: String, materials: MaterialRules): String {
        val clean = name.trim().uppercase()
        if (!materials.exists(clean)) throw DefinitionException("$where '$name' is not a material.")
        return clean
    }

    private fun <E : Enum<E>> enumOf(value: String, type: Class<E>, where: String): E =
        type.enumConstants.firstOrNull { it.name.equals(value.trim().replace('-', '_'), ignoreCase = true) }
            ?: throw DefinitionException("$where '$value' is not one of ${type.enumConstants.joinToString { it.name.lowercase() }}.")
}
