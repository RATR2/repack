package io.github.ratr2.repack

import io.github.ratr2.repack.block.BlockDefinition
import io.github.ratr2.repack.block.BlockDefinitions
import io.github.ratr2.repack.block.BlockEvents
import io.github.ratr2.repack.block.BlockItems
import io.github.ratr2.repack.block.BlockMenu
import io.github.ratr2.repack.block.GiveCommand
import io.github.ratr2.repack.block.PickBlock
import io.github.ratr2.repack.block.BlockTemplate
import io.github.ratr2.repack.block.Loot
import io.github.ratr2.repack.block.PlacedBlocks
import io.github.ratr2.repack.display.DisplayManager
import io.github.ratr2.repack.model.ModelLibrary
import io.github.ratr2.repack.pack.PackBuilder
import io.github.ratr2.repack.pack.PackVariant
import io.github.ratr2.repack.pack.PackEntry
import io.github.ratr2.repack.pack.PackSender
import io.github.ratr2.repack.pack.SharedHost
import io.github.ratr2.repack.pack.PackStore
import io.github.ratr2.repack.protect.Protections
import io.github.ratr2.repack.skript.SkriptHook
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.recipe.CraftingBookCategory
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class Repack : JavaPlugin() {

    lateinit var store: PackStore
        private set
    lateinit var sender: PackSender
        private set
    lateinit var blocks: PlacedBlocks
        private set
    lateinit var items: BlockItems
        private set
    lateinit var host: SharedHost
        private set
    lateinit var menu: BlockMenu
        private set
    private lateinit var display: DisplayManager

    override fun onEnable() {
        saveDefaultConfig()
        saveResource("blocks/_example.yml", true)
        installDefaults()
        store = PackStore(dataFolder)
        host = SharedHost(this, "repack", 8086) { name -> store.file(name.removeSuffix(".zip"))?.readBytes() }.also { it.start() }
        val versions = ClientVersions(this)
        sender = PackSender(this, store, host, versions)
        if (versions.hasViaBackwards) logger.warning("ViaBackwards found: clients older than 1.21.4 will not be able to see Repack blocks.")
        server.pluginManager.registerEvents(sender, this)

        blocks = PlacedBlocks(this)
        items = BlockItems(this)
        display = DisplayManager(blocks)
        blocks.listeners += display
        reloadDefinitions()
        server.pluginManager.registerEvents(blocks, this)
        server.pluginManager.registerEvents(display, this)
        menu = BlockMenu(this)
        server.pluginManager.registerEvents(menu, this)
        server.pluginManager.registerEvents(GiveCommand(this), this)
        server.pluginManager.registerEvents(PickBlock(this), this)
        BlockEvents(this, blocks, items, Loot(items), Protections.detect(this)).also {
            server.pluginManager.registerEvents(it, this)
            it.start()
        }
        blocks.start()
        RepackAPI.plugin = this
        if (!store.hasPublished) logger.info("No pack published yet: run /repack build, then /repack push, to send players the pack.")
        lastStamp = stamp()
        server.scheduler.runTaskTimer(this, Runnable { refreshIfChanged() }, WATCH_TICKS, WATCH_TICKS)
        if (server.pluginManager.isPluginEnabled("Skript")) {
            try {
                SkriptHook.register(this)
            } catch (e: Throwable) {
                logger.severe("Could not add Skript syntax, the rest of Repack still works: $e")
            }
        }

        val command = RepackCommand(this)
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(command.node(), "Build and push the custom block resource pack")
        }
    }

    override fun onDisable() {
        RepackAPI.plugin = null
        recipeKeys.values.forEach { server.removeRecipe(it) }
        if (::host.isInitialized) host.stop()
    }

    val modelsFolder: File
        get() = File(dataFolder, "models").apply { mkdirs() }

    val blocksFolder: File
        get() = File(dataFolder, "blocks").apply { mkdirs() }

    val itemsFolder: File
        get() = File(dataFolder, "items").apply { mkdirs() }

    val skyboxesFolder: File
        get() = File(dataFolder, "skyboxes").apply { mkdirs() }

    private val backupsFolder: File
        get() = File(dataFolder, "backups")

    private var lastStamp = emptyMap<String, String>()

    private fun stamp(): Map<String, String> {
        val stamps = HashMap<String, String>()
        for (folder in listOf(modelsFolder, blocksFolder)) {
            for (file in folder.listFiles { f -> f.isFile && !f.name.startsWith("_") }.orEmpty()) {
                stamps["${folder.name}/${file.name}"] = "${file.length()}:${file.lastModified()}"
            }
        }
        return stamps
    }

    private fun affectedIds(before: Map<String, String>, after: Map<String, String>): Set<String> {
        val ids = HashSet<String>()
        for (key in (before.keys + after.keys).filter { before[it] != after[it] }) {
            val folder = key.substringBefore('/')
            val name = key.substringAfter('/').substringBeforeLast('.').lowercase()
            ids += name
            if (folder == modelsFolder.name) {
                ids += blocks.definitions.values.filter { it.model == name || it.item.model == name }.map { it.id }
            }
        }
        return ids
    }

    /**
     * Reads the block types again when files in the models or blocks folders were added, changed or removed. This only changes
     * which blocks exist; the pack still needs /repack build and /repack push. Returns true when it reloaded.
     */
    fun refreshIfChanged(): Boolean {
        val now = stamp()
        if (now == lastStamp) return false
        val ids = affectedIds(lastStamp, now)
        reloadDefinitions(ids)
        lastStamp = stamp()
        logger.info("Model or block files changed: reloaded ${ids.sorted().joinToString()} (${blocks.types.size} block type(s) in all).")
        return true
    }

    private var fileItems: Map<String, io.github.ratr2.repack.item.ItemDefinition> = emptyMap()

    private val registeredItems = LinkedHashMap<String, io.github.ratr2.repack.item.ItemDefinition>()
    private val unregisteredItems = HashSet<String>()

    fun reloadItems(): List<String> {
        val loaded = io.github.ratr2.repack.item.ItemDefinitions.load(itemsFolder, ModelLibrary.ids(modelsFolder))
        loaded.errors.forEach { logger.severe(it) }
        fileItems = loaded.definitions
        registeredItems.clear()
        unregisteredItems.clear()
        applyItems()
        return loaded.errors
    }

    private fun applyItems() {
        customItems.definitions = (fileItems + registeredItems).filterKeys { it !in unregisteredItems }
        if (::blocks.isInitialized) registerRecipes()
        if (server.pluginManager.isPluginEnabled(this)) server.onlinePlayers.forEach { it.updateCommands() }
    }

    private fun modelsInUse(): Set<String> = fileItems.values.mapNotNullTo(HashSet()) { it.model }

    fun registerItem(id: String, base: String, model: String?): String? {
        if (!ID.matches(id)) return "Item ids use only a-z, 0-9, _ and -."
        if (id in customItems.definitions) return "An item with the id '$id' already exists."
        val cleaned = model?.takeIf { it.isNotBlank() }?.let(ModelLibrary::cleanId)
        if (cleaned != null && cleaned !in ModelLibrary.ids(modelsFolder)) return "There is no model file '$cleaned.bbmodel'."
        val yaml = YamlConfiguration().apply { set("base", base); if (cleaned != null) set("model", cleaned) }
        registeredItems[id] = try {
            io.github.ratr2.repack.item.ItemDefinitions.parse(id, yaml)
        } catch (e: io.github.ratr2.repack.block.DefinitionException) {
            return e.message
        }
        unregisteredItems.remove(id)
        applyItems()
        return null
    }

    fun unregisterItem(id: String): Boolean {
        if (id !in customItems.definitions) return false
        registeredItems.remove(id)
        if (id in fileItems) unregisteredItems += id
        applyItems()
        return true
    }

    fun stackFor(id: String, amount: Int = 1): ItemStack? =
        blocks.definitionOf(id)?.let { items.create(it, amount) } ?: customItems.definitionOf(id)?.let { customItems.create(it, amount) }

    fun giveIds(): List<String> = (blocks.types.sorted() + customItems.ids.sorted()).distinct()

    private var fileDefinitions: Map<String, BlockDefinition> = emptyMap()

    private val registered = LinkedHashMap<String, BlockDefinition>()

    private val unregistered = HashSet<String>()

    /** Writes `blocks/<id>.yml`, with every option at its default, for each model that has no settings file yet. */
    private fun createMissingBlockFiles() {
        if (!config.getBoolean("create-block-files", true)) return
        for (id in ModelLibrary.ids(modelsFolder).sorted()) {
            val file = File(blocksFolder, "$id.yml")
            if (file.exists()) continue
            file.writeText(BlockTemplate.render(id))
            logger.info("Created blocks/$id.yml: edit it to change how the block behaves.")
        }
    }

    /** Brings `config.yml` up to date, then reads it again. */
    private fun migrateConfig() {
        val shippedConfig = getResource("config.yml")?.reader()?.use { it.readText() } ?: return
        val defaults = YamlConfiguration().apply { loadFromString(shippedConfig) }
        ConfigMigrator.gatherBackups(dataFolder, backupsFolder)
        ConfigMigrator.gatherBackups(blocksFolder, File(backupsFolder, "blocks"))
        ConfigMigrator.migrate(File(dataFolder, "config.yml"), ConfigVersions.of(shippedConfig), CONFIG_MIGRATIONS, defaults, backupsFolder)?.let(logger::info)
        reloadConfig()
    }

    /** Brings every settings file in `blocks/` up to date, so the parser only ever sees the current layout. */
    private fun migrateBlockFiles() {
        val files = blocksFolder.listFiles { f -> f.isFile && f.extension == "yml" && !f.name.startsWith("_") }.orEmpty()
        for (file in files.sortedBy { it.name }) ConfigMigrator.migrate(file, BlockTemplate.version, backups = File(backupsFolder, "blocks"))?.let(logger::info)
    }

    fun reloadDefinitions(only: Set<String>? = null): List<String> {
        createMissingBlockFiles()
        val loaded = BlockDefinitions.load(blocksFolder, ModelLibrary.ids(modelsFolder), only)
        loaded.errors.forEach { logger.severe(it) }
        if (only == null) {
            fileDefinitions = loaded.definitions
            registered.clear()
            unregistered.clear()
        } else {
            fileDefinitions = fileDefinitions.filterKeys { it !in only } + loaded.definitions
            registered.keys.removeAll(only)
            unregistered.removeAll(only)
        }
        applyDefinitions(only)
        return loaded.errors
    }

    /** Placed blocks keep working; types that are gone stay in the world but show nothing. */
    private fun applyDefinitions(only: Set<String>? = null) {
        blocks.definitions = (fileDefinitions + registered).filterKeys { it !in unregistered }
        registerRecipes(only)
        if (::display.isInitialized && server.pluginManager.isPluginEnabled(this)) {
            display.refreshAll(only)
            server.onlinePlayers.forEach { it.updateCommands() }
        }
    }

    private val recipeKeys = HashMap<String, NamespacedKey>()

    private fun registerRecipes(only: Set<String>? = null) {
        val stale = recipeKeys.keys.filter { only == null || it in only }
        stale.forEach { server.removeRecipe(recipeKeys.remove(it)!!) }
        for (definition in blocks.definitions.values) {
            if (only != null && definition.id !in only) continue
            val options = definition.recipe ?: continue
            val key = NamespacedKey(this, "block_${definition.id}")
            val recipe = ShapedRecipe(key, items.create(definition, options.amount))
            recipe.shape(*options.shape.toTypedArray())
            val used = options.shape.joinToString("").toSet()
            for ((char, name) in options.ingredients) if (char in used) recipe.setIngredient(char, Material.matchMaterial(name)!!)
            options.group?.let { recipe.group = it }
            options.category?.let { recipe.category = CraftingBookCategory.valueOf(it.uppercase()) }
            server.addRecipe(recipe)
            recipeKeys[definition.id] = key
        }
        if (recipeKeys.isNotEmpty() || stale.isNotEmpty()) server.updateRecipes()
    }

    /** The first time Repack runs it adds the table that comes with it. Deleting those files afterwards keeps them deleted. */
    private fun installDefaults() {
        val marker = File(dataFolder, ".defaults-installed")
        if (marker.exists() || !config.getBoolean("install-defaults", true)) return
        for (name in listOf("models/table.bbmodel", "blocks/table.yml")) {
            if (!File(dataFolder, name).exists()) saveResource(name, false)
        }
        marker.writeText("The bundled table was installed. Delete this file to install it again.\n")
        logger.info("Installed the bundled table: models/table.bbmodel and blocks/table.yml.")
    }

    fun registerBlock(id: String, model: String): String? {
        if (!ID.matches(id)) return "Block ids use only a-z, 0-9, _ and -."
        if (model !in ModelLibrary.ids(modelsFolder)) return "There is no model file '$model.bbmodel'."
        if (id in blocks.definitions) return "A block with the id '$id' already exists."
        registered[id] = BlockDefinitions.parse(id, YamlConfiguration().apply { set("model", model) })
        unregistered.remove(id)
        applyDefinitions()
        return null
    }

    fun unregisterBlock(id: String): Boolean {
        if (id !in blocks.definitions) return false
        registered.remove(id)
        if (id in fileDefinitions) unregistered += id
        applyDefinitions()
        return true
    }

    /** Regenerates every version of the pack from the .bbmodel files in the models folder. Does not change what is published. */
    fun rebuild(): BuildReport {
        val library = ModelLibrary.build(modelsFolder)
        library.warnings.forEach { logger.warning(it) }
        library.errors.forEach { logger.severe(it) }
        val definitionErrors = reloadDefinitions()
        val entries = LinkedHashMap<PackVariant, PackEntry>()
        for (variant in PackVariant.entries) {
            val files = HashMap(library.files.getValue(variant))
            files["pack.mcmeta"] = PackBuilder.meta(variant)
            files["pack.png"] = PackBuilder.icon()
            entries[variant] = store.add(variant, PackBuilder.zip(files))
        }
        return BuildReport(entries, library.models, library.warnings, library.errors + definitionErrors)
    }
}

private val ID = Regex("[a-z0-9_-]+")

/** How often the models and blocks folders are checked for changes: every 5 seconds. */
private const val WATCH_TICKS = 100L

class BuildReport(val entries: Map<PackVariant, PackEntry>, val models: List<String>, val warnings: List<String>, val errors: List<String>)
