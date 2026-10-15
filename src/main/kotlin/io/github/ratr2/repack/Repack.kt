package io.github.ratr2.repack

import com.github.retrooper.packetevents.PacketEvents
import io.github.ratr2.repack.block.BlockDefinitions
import io.github.ratr2.repack.block.BlockEvents
import io.github.ratr2.repack.block.BlockItems
import io.github.ratr2.repack.block.Loot
import io.github.ratr2.repack.block.PlacedBlocks
import io.github.ratr2.repack.display.DisplayManager
import io.github.ratr2.repack.model.ModelLibrary
import io.github.ratr2.repack.pack.PackBuilder
import io.github.ratr2.repack.pack.PackEntry
import io.github.ratr2.repack.pack.PackHost
import io.github.ratr2.repack.pack.PackSender
import io.github.ratr2.repack.pack.PackStore
import io.github.ratr2.repack.protect.Protections
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
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
    private lateinit var host: PackHost
    private lateinit var display: DisplayManager

    override fun onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this))
        PacketEvents.getAPI().load()
    }

    override fun onEnable() {
        PacketEvents.getAPI().init()
        saveDefaultConfig()
        saveResource("blocks/_example.yml", true)
        store = PackStore(dataFolder)
        host = PackHost(this, store).also { it.start() }
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

        val command = RepackCommand(this)
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(command.node(), "Build and push the custom block resource pack")
        }
    }

    override fun onDisable() {
        RepackAPI.plugin = null
        if (::host.isInitialized) host.stop()
        PacketEvents.getAPI().terminate()
    }

    val modelsFolder: File
        get() = File(dataFolder, "models").apply { mkdirs() }

    val blocksFolder: File
        get() = File(dataFolder, "blocks").apply { mkdirs() }

    /** Reads the block files again. Placed blocks keep working; types that are gone stay in the world but show nothing. */
    fun reloadDefinitions(): List<String> {
        val loaded = BlockDefinitions.load(blocksFolder, ModelLibrary.ids(modelsFolder))
        loaded.errors.forEach { logger.severe(it) }
        blocks.definitions = loaded.definitions
        if (::display.isInitialized && server.pluginManager.isPluginEnabled(this)) display.refreshAll()
        return loaded.errors
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

class BuildReport(val entry: PackEntry, val models: List<String>, val warnings: List<String>, val errors: List<String>)
