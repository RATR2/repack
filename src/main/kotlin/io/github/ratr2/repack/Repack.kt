package io.github.ratr2.repack

import io.github.ratr2.repack.block.BlockDefinition
import io.github.ratr2.repack.block.BlockDefinitions
import io.github.ratr2.repack.block.BlockEvents
import io.github.ratr2.repack.block.BlockItems
import io.github.ratr2.repack.block.Loot
import io.github.ratr2.repack.block.PlacedBlocks
import io.github.ratr2.repack.display.DisplayManager
import io.github.ratr2.repack.model.ModelLibrary
import io.github.ratr2.repack.pack.PackBuilder
import io.github.ratr2.repack.pack.PackEntry
import io.github.ratr2.repack.pack.PackSender
import io.github.ratr2.repack.pack.SharedHost
import io.github.ratr2.repack.pack.PackStore
import io.github.ratr2.repack.protect.Protections
import io.github.ratr2.repack.skript.SkriptHook
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.configuration.file.YamlConfiguration
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
    private lateinit var display: DisplayManager

    override fun onEnable() {
        saveDefaultConfig()
        saveResource("blocks/_example.yml", true)
        store = PackStore(dataFolder)
        host = SharedHost(this, "repack", 8086) { name -> store.file(name.removeSuffix(".zip"))?.readBytes() }.also { it.start() }
        sender = PackSender(this, store, host)
        server.pluginManager.registerEvents(sender, this)

        blocks = PlacedBlocks(this)
        items = BlockItems(this)
        display = DisplayManager(blocks)
        blocks.listeners += display
        reloadDefinitions()
        server.pluginManager.registerEvents(blocks, this)
        server.pluginManager.registerEvents(display, this)
        BlockEvents(this, blocks, items, Loot(items), Protections.detect(this)).also {
            server.pluginManager.registerEvents(it, this)
            it.start()
        }
        blocks.start()
        RepackAPI.plugin = this
        if (store.published == null) logger.info("No pack published yet: run /repack build, then /repack push, to send players the pack.")
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

    /** The file names, sizes and times in the models and blocks folders, so a change shows up as a different string. */
    private fun stamp(): String =
        listOf(modelsFolder, blocksFolder).joinToString("|") { folder ->
            folder.listFiles { f -> f.isFile && !f.name.startsWith("_") }.orEmpty().sortedBy { it.name }
                .joinToString(",") { "${it.name}:${it.length()}:${it.lastModified()}" }
        }

    /**
     * Reads the block types again when files in the models or blocks folders were added, changed or removed. This only changes
     * which blocks exist; the pack still needs /repack build and /repack push. Returns true when it reloaded.
     */
    fun refreshIfChanged(): Boolean {
        val now = stamp()
        if (now == lastStamp) return false
        lastStamp = now
        reloadDefinitions()
        logger.info("Model or block files changed: ${blocks.types.size} block type(s) loaded.")
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

    /** Reads the block files again, which also undoes [registerBlock] and [unregisterBlock]. */
    fun reloadDefinitions(): List<String> {
        val loaded = BlockDefinitions.load(blocksFolder, ModelLibrary.ids(modelsFolder))
        loaded.errors.forEach { logger.severe(it) }
        fileDefinitions = loaded.definitions
        registered.clear()
        unregistered.clear()
        applyDefinitions()
        return loaded.errors
    }

    /** Placed blocks keep working; types that are gone stay in the world but show nothing. */
    private fun applyDefinitions() {
        blocks.definitions = (fileDefinitions + registered).filterKeys { it !in unregistered }
        if (::display.isInitialized && server.pluginManager.isPluginEnabled(this)) display.refreshAll()
    }

    /**
     * Adds a block type with the default settings that shows [model] (a file in the models folder, without extension). It lasts
     * until the next reload. Returns an error message, or null when it worked.
     */
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

    /** Regenerates the pack from the .bbmodel files in the models folder. Does not change the published pack. */
    fun rebuild(): BuildReport {
        val library = ModelLibrary.build(modelsFolder)
        val files = HashMap(library.files)
        files["pack.mcmeta"] = PackBuilder.meta("Repack custom blocks")
        library.warnings.forEach { logger.warning(it) }
        library.errors.forEach { logger.severe(it) }
        val definitionErrors = reloadDefinitions()
        return BuildReport(store.add(PackBuilder.zip(files)), library.models, library.warnings, library.errors + definitionErrors)
    }
}

private val ID = Regex("[a-z0-9_-]+")

/** How often the models and blocks folders are checked for changes: every 5 seconds. */
private const val WATCH_TICKS = 100L

class BuildReport(val entry: PackEntry, val models: List<String>, val warnings: List<String>, val errors: List<String>)
