package io.github.ratr2.repack

import com.github.retrooper.packetevents.PacketEvents
import io.github.ratr2.repack.model.ModelLibrary
import io.github.ratr2.repack.pack.PackBuilder
import io.github.ratr2.repack.pack.PackEntry
import io.github.ratr2.repack.pack.PackHost
import io.github.ratr2.repack.pack.PackSender
import io.github.ratr2.repack.pack.PackStore
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class Repack : JavaPlugin() {

    lateinit var store: PackStore
        private set
    lateinit var sender: PackSender
        private set
    private lateinit var host: PackHost

    override fun onLoad() {
        PacketEvents.setAPI(SpigotPacketEventsBuilder.build(this))
        PacketEvents.getAPI().load()
    }

    override fun onEnable() {
        PacketEvents.getAPI().init()
        saveDefaultConfig()
        store = PackStore(dataFolder)
        host = PackHost(this, store).also { it.start() }
        sender = PackSender(this, store, host)
        server.pluginManager.registerEvents(sender, this)

        val command = RepackCommand(this)
        lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            event.registrar().register(command.node(), "Build and push the custom block resource pack")
        }
    }

    override fun onDisable() {
        if (::host.isInitialized) host.stop()
        PacketEvents.getAPI().terminate()
    }

    val modelsFolder: File
        get() = File(dataFolder, "models").apply { mkdirs() }

    /** Regenerates the pack from the .bbmodel files in the models folder. Does not change the published pack. */
    fun rebuild(): BuildReport {
        val library = ModelLibrary.build(modelsFolder)
        val files = HashMap(library.files)
        files["pack.mcmeta"] = PackBuilder.meta("Repack custom blocks")
        library.warnings.forEach { logger.warning(it) }
        library.errors.forEach { logger.severe(it) }
        return BuildReport(store.add(PackBuilder.zip(files)), library.models, library.warnings, library.errors)
    }
}

class BuildReport(val entry: PackEntry, val models: List<String>, val warnings: List<String>, val errors: List<String>)
