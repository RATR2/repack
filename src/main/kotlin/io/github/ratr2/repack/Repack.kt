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
import io.github.ratr2.repack.gui.Gui
import io.github.ratr2.repack.gui.GuiPack
import io.github.ratr2.repack.model.LibraryResult
import io.github.ratr2.repack.model.ModelCache
import io.github.ratr2.repack.model.ModelLibrary
import io.github.ratr2.repack.pack.LoadingHold
import io.github.ratr2.repack.pack.PackBuilder
import io.github.ratr2.repack.pack.EraAssets
import io.github.ratr2.repack.pack.PackEra
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
import org.bukkit.inventory.ItemStack
import org.bukkit.inventory.RecipeChoice
import org.bukkit.inventory.ShapedRecipe
import org.bukkit.inventory.recipe.CraftingBookCategory
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class Repack : JavaPlugin() {

    lateinit var store: PackStore
        private set
    lateinit var sender: PackSender
        private set
    private lateinit var hold: LoadingHold
    lateinit var gui: Gui
        private set
    lateinit var blocks: PlacedBlocks
        private set
    lateinit var items: BlockItems
        private set
    lateinit var host: SharedHost
        private set
    lateinit var menu: BlockMenu
        private set
    var ripples: io.github.ratr2.repack.distortion.RippleManager? = null
        private set
    val players: PlayerSettings by lazy { PlayerSettings(this) }
    private lateinit var display: DisplayManager

    override fun onEnable() {
        saveDefaultConfig()
        migrateConfig()
        saveResource("blocks/_example.yml", true)
        installDefaults()
        store = PackStore(dataFolder)
        host = SharedHost(this, "repack", 8086) { name -> store.file(name.removeSuffix(".zip"))?.readBytes() }.also { it.start() }
        val versions = ClientVersions(this)
        hold = LoadingHold(this)
        sender = PackSender(this, store, host, versions, hold)
        if (versions.hasViaBackwards) logger.warning("ViaBackwards found: clients older than 1.21.4 will not be able to see Repack blocks.")
        server.pluginManager.registerEvents(sender, this)
        server.pluginManager.registerEvents(hold, this)
        startRipples()
        serverVersionNotice()
        gui = Gui(this)
        server.pluginManager.registerEvents(gui, this)

        blocks = PlacedBlocks(this)
        items = BlockItems(this)
        display = DisplayManager(blocks)
        display.hidden = hold::isHeld
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
            event.registrar().register(io.github.ratr2.repack.distortion.DistortionCommand(this).tree("distortion").build(), "Spawn and debug distortion ripples")
        }
    }

    override fun onDisable() {
        RepackAPI.plugin = null
        io.github.ratr2.repack.distortion.DistortionAPI.manager = null
        ripples?.clear()
        recipeKeys.values.forEach { server.removeRecipe(it) }
        if (::hold.isInitialized) hold.endAll()
        if (::host.isInitialized) host.stop()
    }

    private fun startRipples() {
        if (!config.getBoolean("distortion.enabled", true)) return
        if (server.pluginManager.getPlugin("distortion") != null) {
            logger.warning("The Distortion plugin is installed too: Repack includes it now, so remove distortion.jar. Repack's own ripples are off until you do.")
            return
        }
        val manager = io.github.ratr2.repack.distortion.RippleManager(this, sender)
        sender.onLoaded = manager::onPackLoaded
        sender.onLost = manager::onPackLost
        server.pluginManager.registerEvents(manager, this)
        io.github.ratr2.repack.distortion.DistortionAPI.manager = manager
        ripples = manager
    }

    private fun serverVersionNotice() {
        if (!config.getBoolean("recommend-latest", true)) return
        val protocol = com.github.retrooper.packetevents.PacketEvents.getAPI().serverManager.version.protocolVersion
        if (protocol < PackEra.newest.minProtocol) logger.info("Repack recommends switching to 26.3+: older versions show the ripple effects only with Fabulous! graphics, and some features need it. (recommend-latest: false in config.yml hides this.)")
    }

    internal fun jar(): File = file

    fun displays(): DisplayManager? = if (::display.isInitialized) display else null

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

    private fun migrateConfig() {
        val shippedConfig = getResource("config.yml")?.reader()?.use { it.readText() } ?: return
        val defaults = YamlConfiguration().apply { loadFromString(shippedConfig) }
        ConfigMigrator.migrate(File(dataFolder, "config.yml"), ConfigVersions.of(shippedConfig), emptyList(), defaults)?.let(logger::info)
        reloadConfig()
    }

    private fun migrateBlockFiles() {
        val files = blocksFolder.listFiles { f -> f.isFile && f.extension == "yml" && !f.name.startsWith("_") }.orEmpty()
        for (file in files.sortedBy { it.name }) ConfigMigrator.migrate(file, BlockTemplate.version)?.let(logger::info)
    }

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
                for ((char, name) in options.ingredients) if (char in used && char !in options.exact) recipe.setIngredient(char, Material.matchMaterial(name)!!)
                for ((char, bytes) in options.exact) if (char in used) recipe.setIngredient(char, RecipeChoice.ExactChoice(ItemStack.deserializeBytes(bytes)))
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

    private var building = false

    var exportedTo: String? = null
        private set

    fun rebuildAndPush(force: Boolean = false, done: (BuildReport) -> Unit) {
        rebuild(force) { report ->
            if (!report.rejected && report.entries.isNotEmpty()) {
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
            pendingBuild = true
            return
        }
        if (pendingBuild && !building && stamp() == lastStamp) {
            pendingBuild = false
            rebuild { report ->
                if (report.rejected) {
                    logger.warning("The pack was not rebuilt: ${report.errors.size} error(s), see above. The previous build is kept.")
                } else {
                    logger.info("Rebuilt the pack after a file change: ${report.entries.entries.joinToString { (v, e) -> "${v.id} ${e.sha1.take(8)}" }}. /repack push sends it to players who are online.")
                }
            }
        }
    }

    fun rebuild(force: Boolean = false, fresh: Boolean = false, done: (BuildReport) -> Unit) {
        if (building) {
            done(BuildReport(emptyMap(), emptyList(), emptyList(), listOf("A build is already running."), rejected = true))
            return
        }
        building = true
        val progress = BuildProgress(this)
        progress.start()
        val folder = modelsFolder
        val cache = ModelCache(File(dataFolder, "cache/models"), description.version)
        server.scheduler.runTaskAsynchronously(this, Runnable {
            var library: LibraryResult? = null
            var zips: Map<PackEra, ByteArray>? = null
            var failure: String? = null
            try {
                library = ModelLibrary.build(folder, cache, fresh) { done, total ->
                    progress.update(PARSE_SHARE * done / maxOf(total, 1), "converting models $done/$total")
                }
                // A model that failed to convert is missing from the pack, so a pack built around it could replace a working one.
                if (library.errors.isEmpty() || force) {
                    val ripples = config.getBoolean("distortion.enabled", true)
                    zips = PackEra.entries.associateWith { era ->
                        progress.update(PARSE_SHARE + (ZIP_SHARE - PARSE_SHARE) * era.ordinal / PackEra.entries.size, "zipping the ${era.id} pack")
                        val files = HashMap(library.files.getValue(era.family))
                        files.putAll(GuiPack.files())
                        if (ripples) files.putAll(EraAssets.files(file, era))
                        files["pack.mcmeta"] = PackBuilder.meta(era)
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
                    progress.finish(false, "build failed")
                    logger.severe(failure ?: "The build failed.")
                    done(BuildReport(emptyMap(), emptyList(), emptyList(), listOf(failure ?: "The build failed."), rejected = true))
                    return@Runnable
                }
                progress.update(ZIP_SHARE, "loading the block files")
                library.warnings.forEach { logger.warning(it) }
                library.errors.forEach { logger.severe(it) }
                val definitionErrors = reloadDefinitions()
                if (zips == null) {
                    progress.finish(false, "build rejected, ${library.errors.size} error(s)")
                    done(BuildReport(emptyMap(), library.models, library.warnings, library.errors + definitionErrors, rejected = true))
                    return@Runnable
                }
                val entries = LinkedHashMap<PackEra, PackEntry>()
                for ((era, zip) in zips) entries[era] = store.add(era, zip)
                progress.finish(true, "pack built")
                done(BuildReport(entries, library.models, library.warnings, library.errors + definitionErrors))
            })
        })
    }
}

private val ID = Regex("[a-z0-9_-]+")

internal val CONFIG_MIGRATIONS = listOf(ConfigMigration { yaml ->
    yaml.set("proxy-block", null)
    yaml.set("create-block-files", null)
})

private const val WATCH_TICKS = 100L // 5 seconds

private const val PARSE_SHARE = 0.7f
private const val ZIP_SHARE = 0.95f

class BuildReport(
    val entries: Map<PackEra, PackEntry>,
    val models: List<String>,
    val warnings: List<String>,
    val errors: List<String>,
    val rejected: Boolean = false,
) {
    var sent: Int? = null

    var unchanged = false
}
