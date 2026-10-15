package io.github.ratr2.repack.pack

import com.sun.net.httpserver.HttpServer
import io.github.ratr2.repack.Repack
import java.net.InetSocketAddress
import java.net.URI

/** Serves the zips in the [PackStore] over HTTP at `/<sha1>.zip`. */
class PackHost(private val plugin: Repack, private val store: PackStore) {

    private var http: HttpServer? = null
    var baseUrl: String = "http://localhost"
        private set

    fun start() {
        val config = plugin.config
        val port = config.getInt("resource-pack.port", 8086)
        val host = config.getString("resource-pack.public-host").orEmpty()
            .ifBlank { plugin.server.ip }
            .ifBlank { "localhost" }

        http = HttpServer.create(InetSocketAddress(config.getString("resource-pack.bind", "0.0.0.0"), port), 0).apply {
            createContext("/") { exchange ->
                val file = store.file(exchange.requestURI.path.removePrefix("/").removeSuffix(".zip"))
                if (file == null) {
                    exchange.sendResponseHeaders(404, -1)
                } else {
                    exchange.responseHeaders.add("Content-Type", "application/zip")
                    exchange.sendResponseHeaders(200, file.length())
                    exchange.responseBody.use { out -> file.inputStream().use { it.copyTo(out) } }
                }
                exchange.close()
            }
            start()
        }
        baseUrl = "http://$host:$port"
        plugin.logger.info("Serving resource packs at $baseUrl")
    }

    fun stop() {
        http?.stop(0)
        http = null
    }

    fun urlOf(entry: PackEntry): URI = URI("$baseUrl/${entry.sha1}.zip")
}
