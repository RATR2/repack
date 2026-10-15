package io.github.ratr2.repack.pack

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.bukkit.Bukkit
import org.bukkit.plugin.ServicePriority
import org.bukkit.plugin.java.JavaPlugin
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

/**
 * A resource pack web server that several plugins share. Whichever plugin starts first hosts it; the others find it and serve
 * their packs under it instead of opening a port of their own. This file is identical in Distortion and Repack (only the package
 * line differs), and the two talk through a service made only of JDK types, because each plugin has its own copy of every class.
 *
 * Files are served at `/<source>/<file>`, where the source is the plugin's name for its packs. The plugin that hosts decides the
 * address and port, so the other plugin's own `public-host` and `port` settings are not used while it is a guest.
 *
 * The same service also spaces out pack sends: [enqueue] makes sure two plugins never push packs to the same player in the same
 * moment, which keeps the client from handling them all at once.
 *
 * When a player joins, [joined] has the host send the packs of every plugin that [offer]s one in a single request, so the client
 * shows one prompt for all of them. The host's `resource-pack.join-delay-ticks` sets how long after joining that happens.
 */
class SharedHost(
    private val plugin: JavaPlugin,
    private val source: String,
    private val defaultPort: Int,
    private val resolver: (String) -> ByteArray?,
) {

    private enum class Mode { NONE, HOST, GUEST }

    private var mode = Mode.NONE
    private var http: HttpServer? = null
    private var ownBase = "http://localhost"

    /** What this plugin serves, and what guests have added. Lives as long as the plugin, through port changes. */
    private val sources = ConcurrentHashMap<String, Function<String, ByteArray?>>()

    /**
     * What this plugin sends a player who joins: id, url, sha1, required and prompt, or null for nothing. Kept so it can be handed
     * to whichever plugin hosts, now or later. JDK types only, like the rest of the service.
     */
    @Volatile
    private var ownProvider: Function<UUID, Map<String, String>?>? = null

    /** The providers of every plugin that uses this host, by source. Only used by the host. */
    private val providers = ConcurrentHashMap<String, Function<UUID, Map<String, String>?>>()

    /** Players whose join packs are already scheduled, so two plugins reporting the same join cause one send. */
    private val pendingJoins = ConcurrentHashMap.newKeySet<UUID>()

    /** The service this plugin publishes while it hosts. */
    private var service: MutableMap<String, Any>? = null

    /** The service of the plugin that hosts, while this plugin is a guest. */
    private var remote: Map<*, *>? = null

    /** The server tick each player's next pack send may happen at. Only used by the host. */
    private val nextSlot = ConcurrentHashMap<UUID, Int>()

    val baseUrl: String
        get() = when (mode) {
            Mode.HOST -> ownBase
            Mode.GUEST -> (remote?.get("baseUrl") as? Supplier<*>)?.get()?.toString() ?: ownBase
            Mode.NONE -> ownBase
        }

    /** A line for logs and commands saying who serves and where. */
    val description: String
        get() = when (mode) {
            Mode.HOST -> "hosting at $ownBase"
            Mode.GUEST -> "served by ${remote?.get("owner")} at $baseUrl"
            Mode.NONE -> "not serving (see the console for why)"
        }

    fun start() {
        elect()
    }

    /** Applies a changed port, address or sharing setting without restarting the server. Returns an error message, or null. */
    fun restart(): String? {
        if (mode == Mode.HOST && plugin.config.getBoolean(SHARE, true) && findRemote() == null) return rebind()
        leave()
        elect()
        return if (mode == Mode.NONE) "Could not start the pack server, see the console." else null
    }

    fun stop() {
        leave()
    }

    /** The address a pack file can be downloaded from, making sure the shared server is still there first. */
    fun urlOf(file: String): URI {
        ensure()
        return URI("$baseUrl/$source/$file")
    }

    /** Says what this plugin sends a joining player. Return null when there is nothing to send that player. */
    fun offer(provider: (UUID) -> Map<String, String>?) {
        ownProvider = Function { provider(it) }
        ensure()
        when (mode) {
            Mode.HOST -> providers[source] = ownProvider!!
            Mode.GUEST -> remote?.let(::register)
            Mode.NONE -> {}
        }
    }

    /**
     * Reports a join. The host waits its join delay, then sends the packs of all plugins in one request. Every plugin calls this
     * for the same join; only the first call per player does anything.
     */
    fun joined(player: UUID) {
        ensure()
        val join = (remote?.get("join") as? Consumer<*>)?.takeIf { mode == Mode.GUEST }
        if (join != null) {
            @Suppress("UNCHECKED_CAST")
            (join as Consumer<UUID>).accept(player)
        } else {
            dispatchJoin(player)
        }
    }

    private fun dispatchJoin(player: UUID) {
        if (!pendingJoins.add(player)) return
        val delay = plugin.config.getInt("resource-pack.join-delay-ticks", 40).coerceAtLeast(0).toLong()
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            pendingJoins.remove(player)
            val online = Bukkit.getPlayer(player) ?: return@Runnable
            val offers = providers.toSortedMap().values.mapNotNull { runCatching { it.apply(player) }.getOrNull() }
            if (offers.isEmpty()) return@Runnable
            val prompt = offers.map { it["prompt"].orEmpty() }.firstOrNull { it.isNotBlank() }.orEmpty()
            val request = ResourcePackRequest.resourcePackRequest()
                .packs(offers.map { ResourcePackInfo.resourcePackInfo(UUID.fromString(it["id"]), URI(it["url"]!!), it["sha1"]!!) })
                .required(offers.any { it["required"] == "true" })
                .prompt(Component.text(prompt))
                .build()
            nextSlot[player] = Bukkit.getCurrentTick() + plugin.config.getInt(GAP_KEY, 20).coerceAtLeast(0)
            online.sendResourcePacks(request)
        }, delay)
    }

    /** Runs [task] when it is this player's turn, at least [GAP_KEY] ticks after the last pack send to them by any plugin here. */
    fun enqueue(player: UUID, task: Runnable) {
        ensure()
        val queue = (remote?.get("queue") as? BiConsumer<*, *>)?.takeIf { mode == Mode.GUEST }
        if (queue != null) {
            @Suppress("UNCHECKED_CAST")
            (queue as BiConsumer<UUID, Runnable>).accept(player, task)
        } else {
            schedule(player, task)
        }
    }

    private fun schedule(player: UUID, task: Runnable) {
        val now = Bukkit.getCurrentTick()
        val gap = plugin.config.getInt(GAP_KEY, 20).coerceAtLeast(0)
        val at = maxOf(now, nextSlot[player] ?: 0)
        nextSlot[player] = at + gap
        if (nextSlot.size > 200) nextSlot.values.removeIf { it < now }
        if (at <= now) task.run() else plugin.server.scheduler.runTaskLater(plugin, task, (at - now).toLong())
    }

    // Choosing who hosts

    private fun elect() {
        val host = if (plugin.config.getBoolean(SHARE, true)) findRemote() else null
        if (host != null) join(host) else becomeHost()
    }

    /** Called before using the server: takes over or rejoins if the plugin that hosted has gone away. */
    private fun ensure() {
        when (mode) {
            Mode.HOST -> {}
            Mode.NONE -> elect()
            Mode.GUEST -> {
                val current = remote
                if (current == null || !stillProvided(current)) {
                    mode = Mode.NONE
                    elect()
                } else if (source !in sourcesOf(current)) {
                    register(current)
                }
            }
        }
    }

    private fun findRemote(): Map<*, *>? {
        for (registration in Bukkit.getServicesManager().getRegistrations(Map::class.java)) {
            val provider = registration.provider
            if (registration.plugin !== plugin && registration.plugin.isEnabled && provider["version"] == VERSION && provider["owner"] != null) return provider
        }
        return null
    }

    private fun stillProvided(service: Map<*, *>) =
        Bukkit.getServicesManager().getRegistrations(Map::class.java).any { it.provider === service && it.plugin.isEnabled }

    private fun sourcesOf(service: Map<*, *>): Set<*> = (service["sources"] as? Supplier<*>)?.get() as? Set<*> ?: emptySet<Any>()

    @Suppress("UNCHECKED_CAST")
    private fun register(host: Map<*, *>) {
        (host["register"] as BiConsumer<String, Function<String, ByteArray?>>).accept(source, Function { resolver(it) })
        ownProvider?.let { (host["offer"] as BiConsumer<String, Function<UUID, Map<String, String>?>>).accept(source, it) }
    }

    private fun join(host: Map<*, *>) {
        register(host)
        remote = host
        mode = Mode.GUEST
        plugin.logger.info("Pack server shared: serving under ${host["owner"]} at $baseUrl")
    }

    private fun becomeHost() {
        val server = bind() ?: return
        http = server
        sources[source] = Function { resolver(it) }
        ownProvider?.let { providers[source] = it }
        publish()
        mode = Mode.HOST
        plugin.logger.info("Pack server started at $ownBase")
    }

    private fun publish() {
        val map = LinkedHashMap<String, Any>()
        map["version"] = VERSION
        map["owner"] = plugin.name
        map["baseUrl"] = Supplier { ownBase }
        map["register"] = BiConsumer<String, Function<String, ByteArray?>> { name, provider -> sources[name] = provider }
        map["offer"] = BiConsumer<String, Function<UUID, Map<String, String>?>> { name, provider -> providers[name] = provider }
        map["join"] = Consumer<UUID> { dispatchJoin(it) }
        map["unregister"] = Consumer<String> { sources.remove(it); providers.remove(it) }
        map["sources"] = Supplier<Set<String>> { sources.keys.toSet() }
        map["queue"] = BiConsumer<UUID, Runnable> { player, task -> schedule(player, task) }
        service = map
        @Suppress("UNCHECKED_CAST")
        Bukkit.getServicesManager().register(Map::class.java as Class<Map<String, Any>>, map, plugin, ServicePriority.Normal)
    }

    private fun leave() {
        when (mode) {
            Mode.HOST -> {
                http?.stop(0)
                http = null
                service?.let { Bukkit.getServicesManager().unregister(it) }
                service = null
                sources.clear()
                providers.clear()
            }
            Mode.GUEST -> (remote?.get("unregister") as? Consumer<*>)?.let {
                @Suppress("UNCHECKED_CAST")
                (it as Consumer<String>).accept(source)
            }
            Mode.NONE -> {}
        }
        remote = null
        mode = Mode.NONE
    }

    // The server itself

    /** Opens a server on the configured port. The old one, if any, keeps running when the new one cannot start. */
    private fun bind(): HttpServer? {
        val config = plugin.config
        val port = config.getInt("resource-pack.port", defaultPort)
        val host = config.getString("resource-pack.public-host").orEmpty().ifBlank { plugin.server.ip }.ifBlank { "localhost" }
        return try {
            HttpServer.create(InetSocketAddress(config.getString("resource-pack.bind", "0.0.0.0"), port), 0).apply {
                createContext("/", ::handle)
                start()
            }.also { ownBase = "http://$host:$port" }
        } catch (e: IOException) {
            plugin.logger.severe(
                "Could not open the pack server on port $port (${e.message}). Another program may be using it: set " +
                    "resource-pack.port to a free port and reload, or leave sharing on so another plugin hosts it."
            )
            null
        }
    }

    private fun rebind(): String? {
        val old = http
        val server = bind() ?: return "Could not use the new port, still serving at $ownBase."
        http = server
        old?.stop(0)
        plugin.logger.info("Pack server moved to $ownBase")
        return null
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            val parts = HostRoutes.parse(exchange.requestURI.path)
            val bytes = parts?.let { (name, file) -> sources[name]?.apply(file) }
            if (bytes == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                exchange.responseHeaders.add("Content-Type", "application/zip")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { out -> out.write(bytes) }
            }
        }
    }

    companion object {
        const val SHARE = "resource-pack.share-host"
        const val GAP_KEY = "resource-pack.send-gap-ticks"
        private const val VERSION = 2
    }
}

/** Splits `/<source>/<file>` and rejects anything that is not a plain name, so a request can never reach outside the sources. */
object HostRoutes {

    private val NAME = Regex("[A-Za-z0-9._-]+")

    fun parse(path: String): Pair<String, String>? {
        val parts = path.removePrefix("/").split('/')
        if (parts.size != 2) return null
        val (name, file) = parts
        if (!NAME.matches(name) || !NAME.matches(file) || file.startsWith(".") || name.startsWith(".")) return null
        return name to file
    }
}
