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
import io.github.ratr2.repack.config.ConfigMigrator
import io.github.ratr2.repack.config.ConfigVersions
import io.github.ratr2.repack.display.DisplayManager
import io.github.ratr2.repack.model.LibraryResult
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
        migrateConfig()
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
        pendingBuild = config.getBoolean("workflow.build-on-change", true)
        server.scheduler.runTaskTimer(this, Runnable { watch() }, WATCH_TICKS, WATCH_TICKS)
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

    /** What the models and blocks folders looked like when the types were last read. */
    private var lastStamp = emptyMap<String, String>()

    /** Size and time of each file in the models and blocks folders, so a change shows up as a different entry. */
    private fun stamp(): Map<String, String> {
        val stamps = HashMap<String, String>()
        for (folder in listOf(modelsFolder, blocksFolder)) {
            for (file in folder.listFiles { f -> f.isFile && !f.name.startsWith("_") }.orEmpty()) {
                stamps["${folder.name}/${file.name}"] = "${file.length()}:${file.lastModified()}"
            }
        }
        return stamps
    }

    /** The block ids a changed, added or removed file affects: a block file is one id, a model is every block that shows it. */
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
     * Reads the block types again when files in the models or blocks folders were added, changed or removed.
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

    /** Types read from the block files, before runtime changes. */
    private var fileDefinitions: Map<String, BlockDefinition> = emptyMap()

    /** Types added through the API since the last reload. */
    private val registered = LinkedHashMap<String, BlockDefinition>()

    /** File types taken away through the API, until the next reload. */
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
        ConfigMigrator.migrate(File(dataFolder, "config.yml"), ConfigVersions.of(shippedConfig), emptyList(), defaults)?.let(logger::info)
        reloadConfig()
    }

    /** Brings every settings file in `blocks/` up to date, so the parser only ever sees the current layout. */
    private fun migrateBlockFiles() {
        val files = blocksFolder.listFiles { f -> f.isFile && f.extension == "yml" && !f.name.startsWith("_") }.orEmpty()
        for (file in files.sortedBy { it.name }) ConfigMigrator.migrate(file, BlockTemplate.version)?.let(logger::info)
    }

    /**
     * Reads the block files again, which also undoes registerBlock and unregisterBlock. With only, just those block ids are
     * read and refreshed, and every other block is left exactly as it is.
     */
    fun reloadDefinitions(only: Set<String>? = null): List<String> {
        migrateBlockFiles()
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
        val previous = blocks.definitions
        blocks.definitions = (fileDefinitions + registered).filterKeys { it !in unregistered }
        blocks.proxyChanged(previous, blocks.definitions).takeIf { it.isNotEmpty() }?.let {
            logger.info("The proxy block of ${it.sorted().joinToString()} changed: placed blocks of those types follow it.")
        }
        registerRecipes(only)
        if (::display.isInitialized && server.pluginManager.isPluginEnabled(this)) {
            display.refreshAll(only)
            server.onlinePlayers.forEach { it.updateCommands() }
        }
    }

    private val recipeKeys = HashMap<String, NamespacedKey>()

    /** Registers the crafting recipe of every block that has one (or of only those ids), replacing the ones from before. */
    private fun registerRecipes(only: Set<String>? = null) {
        val stale = recipeKeys.keys.filter { only == null || it in only }
        stale.forEach { server.removeRecipe(recipeKeys.remove(it)!!) }
        for (definition in blocks.definitions.values) {
            if (only != null && definition.id !in only) continue
            val options = definition.recipe ?: continue
            val key = NamespacedKey(this, "block_${definition.id}")
            try {
                val recipe = ShapedRecipe(key, items.create(definition, options.amount))
                recipe.shape(*options.shape.toTypedArray())
                val used = options.shape.joinToString("").toSet() - ' '
                for ((char, name) in options.ingredients) if (char in used) recipe.setIngredient(char, Material.matchMaterial(name)!!)
                options.group?.let { recipe.group = it }
                options.category?.let { recipe.category = CraftingBookCategory.valueOf(it.uppercase()) }
                server.addRecipe(recipe)
            } catch (e: RuntimeException) {
                logger.severe("${definition.id}: the recipe could not be registered: ${e.message}")
                continue
            }
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

    /**
     * Adds a block type with the default settings that shows model (a file in the models folder, without extension). It lasts
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

    /** Takes a block type away until the next reload. Placed blocks stay in the world but show nothing. False if there was none. */
    fun unregisterBlock(id: String): Boolean {
        if (id !in blocks.definitions) return false
        registered.remove(id)
        if (id in fileDefinitions) unregistered += id
        applyDefinitions()
        return true
    }

    private var building = false

    /**
     * What /repack reload and a saved file do: [rebuild], and when it worked and the pack is different from the published one, publish it and send it to
     * everyone online. The config's `workflow.send-after-build` can turn the sending off, which leaves it to /repack push.
     */
    fun rebuildAndSend(force: Boolean = false, done: (BuildReport) -> Unit) {
        rebuild(force) { report ->
            if (!report.rejected && report.entries.isNotEmpty() && config.getBoolean("workflow.send-after-build", true)) {
                if (report.entries == store.published) {
                    report.unchanged = true
                } else {
                    store.publish()
                    report.sent = sender.send(server.onlinePlayers.toList())
                }
            }
            done(report)
        }
    }

    /** A file changed and the pack is rebuilt once no more changes come in, so saving a model several times builds it once. */
    private var pendingBuild = false

    private fun watch() {
        if (refreshIfChanged() && config.getBoolean("workflow.build-on-change", true)) {
            // Wait for the next check: if the file changes again in the meantime the build waits once more.
            pendingBuild = true
            return
        }
        if (pendingBuild && !building && stamp() == lastStamp) {
            pendingBuild = false
            rebuildAndSend { report ->
                val sent = report.sent?.let { ", sent to $it player(s)" }.orEmpty()
                when {
                    report.rejected -> logger.warning("The pack was not rebuilt: ${report.errors.size} error(s), see above. The published pack is unchanged.")
                    report.unchanged -> logger.info("Models changed but the pack came out the same.")
                    else -> logger.info("Rebuilt the pack after a file change: ${report.entries.entries.joinToString { (v, e) -> "${v.id} ${e.sha1.take(8)}" }}$sent.")
                }
            }
        }
    }

    fun rebuild(force: Boolean = false, done: (BuildReport) -> Unit) {
        if (building) {
            done(BuildReport(emptyMap(), emptyList(), emptyList(), listOf("A build is already running."), rejected = true))
            return
        }
        building = true
        val folder = modelsFolder
        server.scheduler.runTaskAsynchronously(this, Runnable {
            var library: LibraryResult? = null
            var zips: Map<PackVariant, ByteArray>? = null
            var failure: String? = null
            try {
                library = ModelLibrary.build(folder)
                // A model that failed to convert is missing from the pack, so a pack built around it could replace a working one.
                if (library.errors.isEmpty() || force) {
                    zips = PackVariant.entries.associateWith { variant ->
                        val files = HashMap(library.files.getValue(variant))
                        files["pack.mcmeta"] = PackBuilder.meta(variant)
                        files["pack.png"] = PackBuilder.icon()
                        PackBuilder.zip(files)
                    }
                }
            } catch (e: Throwable) {
                failure = "The build failed: ${e.javaClass.simpleName}: ${e.message}"
            }
            server.scheduler.runTask(this, Runnable {
                building = false
                if (failure != null || library == null) {
                    logger.severe(failure ?: "The build failed.")
                    done(BuildReport(emptyMap(), emptyList(), emptyList(), listOf(failure ?: "The build failed."), rejected = true))
                    return@Runnable
                }
                library.warnings.forEach { logger.warning(it) }
                library.errors.forEach { logger.severe(it) }
                val definitionErrors = reloadDefinitions()
                if (zips == null) {
                    done(BuildReport(emptyMap(), library.models, library.warnings, library.errors + definitionErrors, rejected = true))
                    return@Runnable
                }
                val entries = LinkedHashMap<PackVariant, PackEntry>()
                for ((variant, zip) in zips) entries[variant] = store.add(variant, zip)
                done(BuildReport(entries, library.models, library.warnings, library.errors + definitionErrors))
            })
        })
    }
}

private val ID = Regex("[a-z0-9_-]+")

/** How often the models and blocks folders are checked for changes: */
private const val WATCH_TICKS = 100L /** every 5 seconds. */

/** [rejected]: model errors stopped the build, so the previous built pack is still the one in the store. */
class BuildReport(
    val entries: Map<PackVariant, PackEntry>,
    val models: List<String>,
    val warnings: List<String>,
    val errors: List<String>,
    val rejected: Boolean = false,
) {
    /** How many players were sent the new pack, or null when it was not sent. */
    var sent: Int? = null

    /** True when the build came out the same as the pack already published, so there was nothing to send. */
    var unchanged = false
}
