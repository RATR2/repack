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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import net.kyori.adventure.resource.ResourcePackInfo
import net.kyori.adventure.resource.ResourcePackRequest
import net.kyori.adventure.text.Component
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Function
import java.util.function.Supplier

/**
 * A resource pack web server that several plugins share. Whichever plugin starts first hosts it and the others serve their
 * packs under it. This file is shared with Repack (only the package line differs); the copies talk through a
 * service of JDK types only, since each plugin has its own copy of every class.
 *
 * Which plugin hosts does not decide how: every plugin hands the host its resource-pack settings (bind, port, public-host,
 * public-url, join-delay-ticks, send-gap-ticks), and for each one the host uses a value some plugin changed from its own
 * default, the first such plugin by source name when they disagree (with a warning), or the first plugin's default when none
 * changed it. So the outcome is the same whichever plugin happened to load first, and the host moves its server when it changes.
 *
 * Files are served at `/<source>/<file>`. enqueue spaces out pack sends to the same player, and joined sends every plugin's
 * pack in one request after the join delay. An offer may name further packs under "more", which go in the same request.
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

    private val sources = ConcurrentHashMap<String, Function<String, ByteArray?>>()

    @Volatile
    private var ownProvider: Function<UUID, Map<String, String>?>? = null

    private val providers = ConcurrentHashMap<String, Function<UUID, Map<String, String>?>>()

    /** Players whose join packs are already scheduled, so two plugins reporting the same join cause one send. */
    private val pendingJoins = ConcurrentHashMap.newKeySet<UUID>()

    private var service: MutableMap<String, Any>? = null

    private var remote: Map<*, *>? = null

    private val nextSlot = ConcurrentHashMap<UUID, Int>()

    /** While hosting: each source's settings (see [ownSettings]), its own included, to work out the ones in use. */
    private val settingsBy = ConcurrentHashMap<String, Map<String, Any>>()

    /** The disagreement last warned about, so the same one is not logged on every reload. */
    private var warned: String? = null

    val baseUrl: String
        get() = when (mode) {
            Mode.HOST -> ownBase
            Mode.GUEST -> (remote?.get("baseUrl") as? Supplier<*>)?.get()?.toString() ?: ownBase
            Mode.NONE -> ownBase
        }

    val description: String
        get() = when (mode) {
            Mode.HOST -> "hosting at $ownBase"
            Mode.GUEST -> "served by ${remote?.get("owner")} at $baseUrl"
            Mode.NONE -> "not serving (see the console for why)"
        }

    fun start() {
        elect()
    }

    fun restart(): String? {
        if (mode == Mode.HOST && plugin.config.getBoolean(SHARE, true) && findRemote() == null) {
            settingsBy[source] = ownSettings()
            return rebind()
        }
        leave()
        elect()
        return if (mode == Mode.NONE) "Could not start the pack server, see the console." else null
    }

    fun stop() {
        leave()
    }

    fun urlOf(file: String): URI {
        ensure()
        return URI("$baseUrl/$source/$file")
    }

    /** Whether a join offer's "more" packs reach the player: always when this plugin hosts, and with a host that says so. */
    val sendsMore: Boolean get() {
        ensure()
        return when (mode) {
            Mode.HOST -> true
            Mode.GUEST -> remote?.get("more") == true
            Mode.NONE -> false
        }
    }

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
        val delay = number(JOIN_DELAY).coerceAtLeast(0).toLong()
        plugin.server.scheduler.runTaskLater(plugin, Runnable {
            pendingJoins.remove(player)
            val online = Bukkit.getPlayer(player) ?: return@Runnable
            val offers = providers.toSortedMap().values.mapNotNull { runCatching { it.apply(player) }.getOrNull() }
            if (offers.isEmpty()) return@Runnable
            val prompt = offers.map { it["prompt"].orEmpty() }.firstOrNull { it.isNotBlank() }.orEmpty()
            // An offer can carry further packs under "more", one "id url sha1" per line, so a plugin with several packs has them
            // all downloaded and loaded together with everyone else's.
            val packs = offers.flatMap { offer ->
                listOf(ResourcePackInfo.resourcePackInfo(UUID.fromString(offer["id"]), URI(offer["url"]!!), offer["sha1"]!!)) +
                    offer["more"].orEmpty().lines().mapNotNull { line ->
                        val parts = line.trim().split(' ')
                        if (parts.size != 3) null else runCatching { ResourcePackInfo.resourcePackInfo(UUID.fromString(parts[0]), URI(parts[1]), parts[2]) }.getOrNull()
                    }
            }
            val request = ResourcePackRequest.resourcePackRequest()
                .packs(packs)
                .required(offers.any { it["required"] == "true" })
                .prompt(Component.text(prompt))
                .build()
            nextSlot[player] = Bukkit.getCurrentTick() + number(GAP).coerceAtLeast(0)
            online.sendResourcePacks(request)
        }, delay)
    }

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
        val gap = number(GAP).coerceAtLeast(0)
        val at = maxOf(now, nextSlot[player] ?: 0)
        nextSlot[player] = at + gap
        if (nextSlot.size > 200) nextSlot.values.removeIf { it < now }
        if (at <= now) task.run() else plugin.server.scheduler.runTaskLater(plugin, task, (at - now).toLong())
    }

    private fun elect() {
        val host = if (plugin.config.getBoolean(SHARE, true)) findRemote() else null
        if (host != null) join(host) else becomeHost()
    }

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
        (host["settings"] as? BiConsumer<String, Map<String, Any>>)?.accept(source, ownSettings())
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
        settingsBy.clear()
        settingsBy[source] = ownSettings()
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
        map["unregister"] = Consumer<String> {
            sources.remove(it)
            providers.remove(it)
            if (settingsBy.remove(it) != null) settle()
        }
        // A guest's settings, which may change the ones in use (and move the server).
        map["settings"] = BiConsumer<String, Map<String, Any>> { name, settings ->
            settingsBy[name] = settings
            settle()
        }
        map["sources"] = Supplier<Set<String>> { sources.keys.toSet() }
        map["queue"] = BiConsumer<UUID, Runnable> { player, task -> schedule(player, task) }
        // Tells guests this host sends an offer's "more" packs; an older host would leave them out.
        map["more"] = true
        service = map
        @Suppress("UNCHECKED_CAST")
        Bukkit.getServicesManager().register(Map::class.java as Class<Map<String, Any>>, map, plugin, ServicePriority.Normal)
    }

    private fun leave() {
        when (mode) {
            Mode.HOST -> {
                http?.let(::close)
                http = null
                boundTo = null
                service?.let { Bukkit.getServicesManager().unregister(it) }
                service = null
                sources.clear()
                providers.clear()
                settingsBy.clear()
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

    /** Opens a server on the configured port. The old one, if any, keeps running when the new one cannot start. */
    private fun bind(): HttpServer? {
        val (address, port) = bindTarget()
        return try {
            HttpServer.create(InetSocketAddress(address, port), 0).apply {
                // Downloads run in a small pool, so one slow client cannot hold up everyone else's.
                executor = Executors.newFixedThreadPool(DOWNLOAD_THREADS) { task -> Thread(task, "pack-download").apply { isDaemon = true } }
                createContext("/", ::handle)
                start()
            }.also {
                ownBase = publicBase(port)
                boundTo = address to port
            }
        } catch (e: IOException) {
            plugin.logger.severe(
                "Could not open the pack server on port $port (${e.message}). Another program may be using it: set " +
                    "resource-pack.port to a free port and reload, or leave sharing on so another plugin hosts it."
            )
            null
        }
    }

    private fun close(server: HttpServer) {
        server.stop(0)
        (server.executor as? ExecutorService)?.shutdownNow()
    }

    /**
     * This plugin's resource-pack settings, with defaults for those it leaves out, and under "changed" the ones it set to something
     * other than its own default (what the host prefers).
     */
    private fun ownSettings(): Map<String, Any> {
        val values = mapOf<String, Any>(
            BIND to plugin.config.getString("resource-pack.$BIND", "0.0.0.0")!!,
            PORT to plugin.config.getInt("resource-pack.$PORT", defaultPort),
            PUBLIC_HOST to plugin.config.getString("resource-pack.$PUBLIC_HOST", "")!!,
            PUBLIC_URL to plugin.config.getString("resource-pack.$PUBLIC_URL", "")!!,
            JOIN_DELAY to plugin.config.getInt("resource-pack.$JOIN_DELAY", 40),
            GAP to plugin.config.getInt("resource-pack.$GAP", 20),
        )
        val defaults = plugin.config.defaults
        val changed = values.filter { (key, value) ->
            val default = defaults?.get("resource-pack.$key") ?: if (key == PORT) defaultPort else null
            default != null && default.toString() != value.toString()
        }.keys
        return values + mapOf(CHANGED to changed.toSet())
    }

    /** A setting in use: changed by some plugin (the first by source name), else the first plugin's own. */
    private fun setting(key: String): Any? {
        val all = settingsBy.toSortedMap().values.ifEmpty { listOf(ownSettings()) }
        return all.firstOrNull { (it[CHANGED] as? Set<*>)?.contains(key) == true }?.get(key) ?: all.first()[key]
    }

    private fun number(key: String): Int = (setting(key) as? Number)?.toInt() ?: 0

    private fun text(key: String): String = setting(key)?.toString().orEmpty()

    // The settings in use changed (a guest arrived, left or reloaded): warn about plugins that disagree, and move the server if
    // its address or port is no longer the one in use.
    private fun settle() {
        if (mode != Mode.HOST) return
        val clashes = SETTINGS.mapNotNull { key ->
            val set = settingsBy.toSortedMap().filterValues { (it[CHANGED] as? Set<*>)?.contains(key) == true }.mapValues { it.value[key].toString() }
            if (set.values.distinct().size > 1) "resource-pack.$key (" + set.entries.joinToString(", ") { (name, value) -> "$name: $value" } + ")" else null
        }
        val warning = clashes.joinToString("; ")
        if (warning.isNotEmpty() && warning != warned) {
            plugin.logger.warning("Plugins sharing the pack server set it up differently: $warning. Using the first by name; set them the same to be sure.")
        }
        warned = warning.ifEmpty { null }
        if (rebind() != null) plugin.logger.warning("The pack server could not move to the settings in use; still serving at $ownBase.")
    }

    private fun bindTarget(): Pair<String, Int> = text(BIND).ifBlank { "0.0.0.0" } to number(PORT)

    private fun publicBase(port: Int): String {
        // A full address wins: the pack server behind a reverse proxy or a host's own port forwarding, on any port or https.
        text(PUBLIC_URL).trim().trimEnd('/').takeIf { it.startsWith("http://") || it.startsWith("https://") }?.let { return it }
        val configured = text(PUBLIC_HOST).ifBlank { plugin.server.ip }
        if (configured.isBlank()) {
            plugin.logger.warning(
                "resource-pack.public-host and server-ip are both empty, so players are told to download the pack from localhost, " +
                    "which only works on this machine. Set resource-pack.public-host to your server's address."
            )
        }
        return "http://${configured.ifBlank { "localhost" }}:$port"
    }

    /** What the running server is bound to, so a reload that keeps it does not try to bind the same port a second time. */
    private var boundTo: Pair<String, Int>? = null

    private fun rebind(): String? {
        val target = bindTarget()
        if (boundTo == target) {
            // Same address and port: only the advertised address can have changed, which needs no new server.
            ownBase = publicBase(target.second)
            plugin.logger.info("Pack server address is now $ownBase")
            return null
        }
        val old = http
        val server = bind() ?: return "Could not use the new port, still serving at $ownBase."
        http = server
        old?.let(::close)
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
        // The settings handed to the host, under resource-pack in each plugin's config.
        private const val BIND = "bind"
        private const val PORT = "port"
        private const val PUBLIC_HOST = "public-host"
        private const val PUBLIC_URL = "public-url"
        private const val JOIN_DELAY = "join-delay-ticks"
        private const val GAP = "send-gap-ticks"
        private const val CHANGED = "changed"
        private val SETTINGS = listOf(BIND, PORT, PUBLIC_HOST, PUBLIC_URL, JOIN_DELAY, GAP)
        // 3: guests hand the host their settings; older copies do not, so they are not mixed.
        private const val VERSION = 3
        private const val DOWNLOAD_THREADS = 4
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
