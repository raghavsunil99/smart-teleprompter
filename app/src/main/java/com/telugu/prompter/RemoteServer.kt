package com.telugu.prompter

import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoWSD
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLServerSocketFactory
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Tiny web server the laptop talks to over Wi-Fi. Plain JVM code (no Android APIs) so it
 * can be tested on a computer.
 *
 *   GET  /              remote control page
 *   GET  /api/status    live prompter state (JSON)
 *   POST /api/cmd       cmd=play|pause|toggle|mode|faster|slower|bigger|smaller|up|down|top|flip|mirror [&value=]
 *   GET  /api/scripts   list of scripts
 *   GET  /api/script    ?id=  one script
 *   POST /api/script    id=&title=&body=&language=&open=  save (and open on the phone)
 *   POST /api/heard     text=  words heard by the laptop's mic (empty text = "still listening")
 *   WS   /ws?pin=       one open connection for all of the above, with no per-request cost:
 *                       laptop → phone: "cmd\t<name>\t<value>", "heard\t<text>", "ping"
 *                       phone → laptop: "status\t<json>" whenever the status changes
 *
 * When a PIN is set ([pin] returns non-null), every /api call needs it in the X-Pin header,
 * and too many wrong PINs lock the API for a minute. With no PIN, anyone on the Wi-Fi can connect.
 * The same server also runs over https (see [sslFactory]): Chrome only allows the laptop
 * microphone on secure pages, and a phone on Wi-Fi can't have a trusted certificate, so it
 * uses a bundled self-signed one that the user accepts once in Chrome.
 * The plain request API stays as a fallback for when the WebSocket can't connect.
 */
class RemoteServer(
    port: Int,
    /** Current PIN, or null when no PIN is required. Read on every request, so it can change live. */
    private val pin: () -> String?,
    private val page: String,
    private val api: Api,
    private val clock: () -> Long = System::currentTimeMillis,
) : NanoWSD(port) {

    interface Api {
        fun statusJson(): String
        fun command(cmd: String, value: String?): Boolean
        fun scriptsJson(): String
        fun scriptJson(id: String): String?
        /** Saves the script (opening it on the phone if [open]) and returns its id. [language]: a code, or null for auto. */
        fun saveScript(id: String?, title: String, body: String, open: Boolean = true, language: String? = null): String
        /** Words heard by the laptop's mic; blank text is just a heartbeat. */
        fun heard(text: String)
    }

    private var failures = 0
    private var lockedUntil = 0L

    private val sockets = CopyOnWriteArraySet<RemoteSocket>()
    @Volatile private var pushing = false

    /** One connected laptop page. */
    private inner class RemoteSocket(handshake: IHTTPSession) : WebSocket(handshake) {
        override fun onOpen() {
            sockets += this
            trySend("status\t" + api.statusJson())
        }

        override fun onClose(code: WebSocketFrame.CloseCode?, reason: String?, initiatedByRemote: Boolean) {
            sockets -= this
        }

        override fun onMessage(message: WebSocketFrame) = handleMessage(message.textPayload ?: "")

        override fun onPong(pong: WebSocketFrame?) {}

        override fun onException(exception: IOException?) {
            sockets -= this
        }

        fun trySend(text: String) {
            try {
                send(text)
            } catch (_: IOException) {
                sockets -= this
            }
        }
    }

    /** Applies one WebSocket message from the laptop page. */
    internal fun handleMessage(text: String) {
        val parts = text.split('\t', limit = 3)
        when (parts[0]) {
            "cmd" -> parts.getOrNull(1)?.let { api.command(it, parts.getOrNull(2)?.ifEmpty { null }) }
            "heard" -> api.heard(parts.getOrElse(1) { "" })
            // "ping": just keeps the connection (and its read timeout) alive.
        }
    }

    override fun openWebSocket(handshake: IHTTPSession): WebSocket = RemoteSocket(handshake)

    override fun serve(session: IHTTPSession): Response {
        val isWebSocket = session.headers["upgrade"].equals("websocket", ignoreCase = true)
        if (isWebSocket) {
            if (session.uri != "/ws") return error(Response.Status.NOT_FOUND, "not found")
            authError(session.parameters["pin"]?.firstOrNull())?.let { return it }
        }
        return super.serve(session)
    }

    /** Starts the server and the status pusher for connected WebSockets. */
    override fun start(timeout: Int, daemon: Boolean) {
        super.start(timeout, daemon)
        pushing = true
        Thread({
            var last = ""
            var lastSent = 0L
            while (pushing) {
                if (sockets.isNotEmpty()) {
                    val s = api.statusJson()
                    val now = System.currentTimeMillis()
                    if (s != last || now - lastSent > 1000) { // on change, plus a 1 s keep-alive
                        sockets.forEach { it.trySend("status\t$s") }
                        last = s
                        lastSent = now
                    }
                }
                Thread.sleep(PUSH_INTERVAL_MS)
            }
        }, "remote-status-push").apply { isDaemon = true }.start()
    }

    override fun stop() {
        pushing = false
        sockets.forEach { runCatching { it.close(WebSocketFrame.CloseCode.GoingAway, "stopped", false) } }
        sockets.clear()
        super.stop()
    }

    override fun serveHttp(session: IHTTPSession): Response {
        val uri = session.uri
        if (session.method == Method.OPTIONS) return text(Response.Status.NO_CONTENT, "text/plain", "")
        if (uri == "/" || uri == "/index.html") return text(Response.Status.OK, "text/html; charset=utf-8", page)
        if (!uri.startsWith("/api/")) return error(Response.Status.NOT_FOUND, "not found")

        authError(session.headers["x-pin"])?.let { return it }

        return try {
            when {
                uri == "/api/status" && session.method == Method.GET -> json(api.statusJson())
                uri == "/api/scripts" && session.method == Method.GET -> json(api.scriptsJson())
                uri == "/api/script" && session.method == Method.GET -> {
                    val id = session.parameters["id"]?.firstOrNull() ?: return error(Response.Status.BAD_REQUEST, "id required")
                    api.scriptJson(id)?.let { json(it) } ?: error(Response.Status.NOT_FOUND, "no such script")
                }
                uri == "/api/cmd" && session.method == Method.POST -> {
                    val p = formParams(session)
                    val cmd = p["cmd"] ?: return error(Response.Status.BAD_REQUEST, "cmd required")
                    if (api.command(cmd, p["value"])) json("{\"ok\":true}") else error(Response.Status.BAD_REQUEST, "unknown cmd")
                }
                uri == "/api/heard" && session.method == Method.POST -> {
                    api.heard(formParams(session)["text"].orEmpty())
                    json("{\"ok\":true}")
                }
                uri == "/api/script" && session.method == Method.POST -> {
                    val p = formParams(session)
                    val body = p["body"]?.takeIf { it.isNotBlank() } ?: return error(Response.Status.BAD_REQUEST, "body required")
                    val id = api.saveScript(
                        p["id"]?.takeIf { it.isNotBlank() }, p["title"].orEmpty(), body,
                        open = p["open"] != "0", language = p["language"]?.takeIf { it.isNotBlank() },
                    )
                    json("{\"ok\":true,\"id\":${Json.str(id)}}")
                }
                else -> error(Response.Status.NOT_FOUND, "not found")
            }
        } catch (e: Exception) {
            error(Response.Status.INTERNAL_ERROR, e.message ?: "error")
        }
    }

    @Synchronized
    private fun authError(given: String?): Response? {
        val required = pin() ?: return null
        val now = clock()
        if (now < lockedUntil) return error(Response.Status.FORBIDDEN, "locked")
        if (given == required) {
            failures = 0
            return null
        }
        failures++
        if (failures >= MAX_FAILURES) {
            failures = 0
            lockedUntil = now + LOCK_MILLIS
        }
        return error(Response.Status.UNAUTHORIZED, "wrong pin")
    }

    private fun formParams(session: IHTTPSession): Map<String, String> {
        session.parseBody(HashMap())
        return session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
    }

    private fun json(body: String) = text(Response.Status.OK, "application/json; charset=utf-8", body)

    private fun error(status: Response.Status, message: String) =
        text(status, "application/json; charset=utf-8", "{\"error\":${Json.str(message)}}")

    private fun text(status: Response.Status, mime: String, body: String): Response =
        newFixedLengthResponse(status, mime, body).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("Access-Control-Allow-Origin", "*")
            addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            addHeader("Access-Control-Allow-Headers", "X-Pin, Content-Type")
            addHeader("Access-Control-Allow-Private-Network", "true")
            addHeader("Access-Control-Max-Age", "600")
        }

    companion object {
        const val MAX_FAILURES = 10
        const val LOCK_MILLIS = 60_000L
        const val TLS_PASSWORD = "prompter-remote"
        private const val PUSH_INTERVAL_MS = 100L

        /** TLS socket factory from the bundled PKCS#12 keystore (call [makeSecure] with it). */
        fun sslFactory(p12: InputStream, password: String = TLS_PASSWORD): SSLServerSocketFactory {
            val ks = KeyStore.getInstance("PKCS12")
            p12.use { ks.load(it, password.toCharArray()) }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, password.toCharArray())
            return makeSSLSocketFactory(ks, kmf)
        }
    }
}

/** Minimal JSON writing, so this file doesn't depend on Android's org.json. */
object Json {
    fun str(s: String?): String {
        if (s == null) return "null"
        val b = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> b.append("\\\"")
                '\\' -> b.append("\\\\")
                '\n' -> b.append("\\n")
                '\r' -> b.append("\\r")
                '\t' -> b.append("\\t")
                else -> if (c < ' ') b.append(String.format("\\u%04x", c.code)) else b.append(c)
            }
        }
        return b.append('"').toString()
    }

    fun obj(vararg fields: Pair<String, Any?>): String =
        fields.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:${value(v)}" }

    fun arr(items: List<String>): String = items.joinToString(",", "[", "]")

    private fun value(v: Any?): String = when (v) {
        null -> "null"
        is String -> str(v)
        is Boolean, is Int, is Long -> v.toString()
        is Float -> if (v.isFinite()) v.toString() else "null"
        is Double -> if (v.isFinite()) v.toString() else "null"
        is List<*> -> v.joinToString(",", "[", "]") { value(it) }
        else -> str(v.toString())
    }
}
