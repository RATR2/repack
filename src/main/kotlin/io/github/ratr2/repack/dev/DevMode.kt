package io.github.ratr2.repack.dev

import io.github.ratr2.repack.Repack
import io.github.ratr2.repack.RepackCommand
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.io.File
import java.util.UUID

/**
 * Dev mode, a switch per player (`/repack devmode`): the things for building a server from the game. While it is on the player can have links for the web
 * uploader (`/repack upload <kind>`), served by the pack server under `/uploader`. It stays on until the player turns it off or leaves the server: when they
 * come back it is off, and they are told so.
 */
class DevMode(private val plugin: Repack) : Listener, UploadAccess {

    val tokens = UploadTokens()
    private val route = UploadRoute(tokens, this)
    private val ownServer = UploadServer(route)
    // Read by the uploader's threads as well as the main one.
    private val on: MutableSet<UUID> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    private val turnedOffByRejoin = HashSet<UUID>()

    fun isOn(player: Player) = player.uniqueId in on

    override fun allowed(player: UUID) = player in on

    override fun folder(kind: UploadKind): File = File(plugin.dataFolder, kind.folder)

    override fun models(): File = plugin.modelsFolder

    override fun backups(): File = File(plugin.dataFolder, "backups/uploads")

    override fun saved(player: UUID, kind: UploadKind, file: File, replaced: Boolean) {
        plugin.server.scheduler.runTask(plugin, Runnable {
            plugin.logger.info("Upload: ${file.parentFile.name}/${file.name} ${if (replaced) "replaced" else "added"}.")
            when (kind) {
                UploadKind.IMPACT -> plugin.reloadImpactScenes().forEach { plugin.logger.warning(it) }
                UploadKind.GUI -> plugin.reloadGuis().forEach { plugin.logger.warning(it) }
                else -> {}
            }
            // The watcher rebuilds for models, blocks and items; the rest is asked for the same way, and several uploads in a row make one build.
            plugin.requestBuild()
            plugin.server.getPlayer(player)?.sendMessage(
                Component.text("Uploaded ${file.parentFile.name}/${file.name}${if (replaced) " (replaced; the old one is in backups/uploads)" else ""}. ", NamedTextColor.GREEN)
                    .append(Component.text("[Test on me]", NamedTextColor.AQUA).clickEvent(ClickEvent.runCommand("/repack test")))
                    .append(Component.text(" builds the pack and sends it to you only.", NamedTextColor.GRAY)),
            )
        })
    }

    /** The port of the uploader's own server (dev.port), or 0 when it uses the pack server's. */
    private val ownPort: Int get() = plugin.config.getInt("dev.port", 0).coerceIn(0, 65535)

    /** Turns dev mode on or off for a player. Returns the reason it could not be turned on, or null. */
    fun set(player: Player, enable: Boolean): String? {
        if (!enable) {
            on -= player.uniqueId
            tokens.revoke(player.uniqueId)
            if (on.isEmpty()) ownServer.stop()
            return null
        }
        if (ownPort > 0) {
            // dev.port changed while the uploader was open: it moves, so the links point where it listens.
            if (ownServer.open && ownServer.port != ownPort) ownServer.stop()
            // A port of its own: nothing needs the pack server, and the page is only reachable while someone is in dev mode.
            ownServer.start(plugin.config.getString("dev.bind").orEmpty(), ownPort)?.let { return it }
        } else if (!plugin.host.addRoute(ROUTE, route)) {
            return plugin.host.ownerName?.let { "The pack server is run by $it, and a plugin cannot add pages to another one's server. Give the uploader a port of its own (dev.port) or let Repack run the pack server (resource-pack.share-host: false)." }
                ?: "The pack server is not running, so there is no page to upload to. Check /repack status."
        }
        on += player.uniqueId
        return null
    }

    /** The link for a kind of file, or null when dev mode is off. The link is only good for that kind. */
    fun linkFor(player: Player, kind: UploadKind): String? =
        if (!isOn(player)) null else "${base(plugin.config.getString("dev.public-url"), plugin.host.baseUrl, ownPort)}$ROUTE?t=${tokens.create(player.uniqueId, kind)}"

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        if (on.remove(id)) {
            tokens.revoke(id)
            turnedOffByRejoin += id
            if (on.isEmpty()) ownServer.stop()
        }
    }

    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        if (turnedOffByRejoin.remove(event.player.uniqueId) && event.player.hasPermission(RepackCommand.ADMIN)) {
            event.player.sendMessage(Component.text("Dev mode was turned off because you rejoined. /repack devmode turns it back on.", NamedTextColor.YELLOW))
        }
    }

    fun stop() {
        on.clear()
        ownServer.stop()
        plugin.host.removeRoute(ROUTE)
    }

    companion object {
        const val ROUTE = "/uploader"

        /**
         * The address links start with: dev.public-url when it is a full http or https address (a trailing slash is dropped); else the pack server's own address,
         * with [ownPort] in place of its port when the uploader has a port of its own (above 0).
         */
        fun base(publicUrl: String?, hostBase: String, ownPort: Int = 0): String {
            val own = publicUrl?.trim()?.trimEnd('/').orEmpty()
            if (Regex("https?://[^\\s/?#:]+(:\\d+)?(/[^\\s?#]*)?").matches(own)) return own
            if (ownPort <= 0) return hostBase
            val uri = runCatching { java.net.URI(hostBase) }.getOrNull()?.takeIf { it.scheme != null && it.host != null } ?: return hostBase
            return "${uri.scheme}://${uri.host}:$ownPort"
        }
    }
}
