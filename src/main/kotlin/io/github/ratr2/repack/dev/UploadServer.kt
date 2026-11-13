package io.github.ratr2.repack.dev

import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A server for the uploader alone, for when dev.port gives it a port of its own instead of the pack server's. It is only open while it is wanted: [DevMode] starts it
 * when the first player turns dev mode on and closes it when the last one is out. It serves nothing but the upload page.
 */
class UploadServer(private val route: UploadRoute) {

    private var http: HttpServer? = null

    /** The port it listens on (the one asked for, or the one it was given when that was 0), or null when it is closed. */
    val port: Int? get() = http?.address?.port

    val open: Boolean get() = http != null

    /** Opens it on [bind] and [port]. Returns what went wrong, or null. Does nothing when it is already open. */
    @Synchronized
    fun start(bind: String, port: Int): String? {
        if (http != null) return null
        io.github.ratr2.repack.pack.HttpTimeouts.apply()
        return try {
            http = HttpServer.create(InetSocketAddress(bind.ifBlank { "0.0.0.0" }, port), 0).apply {
                // A few threads: an upload is one person at a time.
                executor = Executors.newFixedThreadPool(THREADS) { task -> Thread(task, "repack-uploader").apply { isDaemon = true } }
                createContext(DevMode.ROUTE, route)
                start()
            }
            null
        } catch (e: IOException) {
            "Could not open the uploader on port $port (${e.message}). Set dev.port to a free port, or 0 to use the pack server's."
        } catch (e: IllegalArgumentException) {
            "Could not open the uploader: ${e.message}"
        }
    }

    @Synchronized
    fun stop() {
        http?.let {
            it.stop(0)
            (it.executor as? ExecutorService)?.shutdownNow()
        }
        http = null
    }

    private companion object {
        const val THREADS = 3
    }
}
