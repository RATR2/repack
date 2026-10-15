package io.github.ratr2.repack

import io.github.ratr2.repack.pack.PackBuilder
import io.github.ratr2.repack.pack.PackEntry
import io.github.ratr2.repack.pack.PackHost
import io.github.ratr2.repack.pack.PackSender
import io.github.ratr2.repack.pack.PackStore
import com.github.retrooper.packetevents.PacketEvents
import io.github.retrooper.packetevents.factory.spigot.SpigotPacketEventsBuilder
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.plugin.java.JavaPlugin

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

    /** Regenerates the pack from the registered models. Does not change the published pack. */
    fun rebuild(): PackEntry {
        val files = HashMap<String, ByteArray>()
        files["pack.mcmeta"] = PackBuilder.meta("Repack custom blocks")
        return store.add(PackBuilder.zip(files))
    }
}
