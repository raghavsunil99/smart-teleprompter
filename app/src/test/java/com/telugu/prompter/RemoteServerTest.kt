package com.telugu.prompter

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.net.URLEncoder

class RemoteServerTest {

    private val commands = mutableListOf<Pair<String, String?>>()
    private val saved = mutableListOf<Triple<String?, String, String>>()
    private val heard = mutableListOf<String>()
    private var now = 0L
    private var pin: String? = "4321"
    private lateinit var server: RemoteServer
    private var port = 0

    private val api = object : RemoteServer.Api {
        override fun statusJson() = Json.obj("running" to true, "line" to "Namaskaram andariki")
        override fun command(cmd: String, value: String?): Boolean {
            if (cmd !in setOf("play", "pause", "up", "bigger")) return false
            commands += cmd to value
            return true
        }
        override fun scriptsJson() = Json.arr(listOf(Json.obj("id" to "a", "title" to "Intro")))
        override fun scriptJson(id: String) = if (id == "a") Json.obj("id" to "a", "title" to "Intro", "body" to "x") else null
        override fun saveScript(id: String?, title: String, body: String): String {
            saved += Triple(id, title, body)
            return id ?: "new-id"
        }
        override fun heard(text: String) { heard += text }
    }

    @Before
    fun start() {
        port = ServerSocket(0).use { it.localPort }
        server = RemoteServer(port, { pin }, "<html>remote</html>", api, clock = { now })
        server.start(5000, false)
    }

    @After
    fun stop() = server.stop()

    private fun call(path: String, pin: String? = "4321", form: Map<String, String>? = null): Pair<Int, String> {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        pin?.let { c.setRequestProperty("X-Pin", it) }
        if (form != null) {
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
            val body = form.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        val text = (if (code < 400) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
        return code to text
    }

    @Test
    fun servesPageWithoutPin() {
        val (code, body) = call("/", pin = null)
        assertEquals(200, code)
        assertTrue(body.contains("remote"))
    }

    @Test
    fun apiNeedsCorrectPin() {
        assertEquals(401, call("/api/status", pin = null).first)
        assertEquals(401, call("/api/status", pin = "0000").first)
        val (code, body) = call("/api/status")
        assertEquals(200, code)
        assertTrue(body.contains("Namaskaram"))
    }

    @Test
    fun noPinNeededWhenPinIsOff() {
        pin = null
        assertEquals(200, call("/api/status", pin = null).first)
        assertEquals(200, call("/api/status", pin = "stale-from-old-session").first)
        assertEquals(200, call("/api/cmd", pin = null, form = mapOf("cmd" to "play")).first)
        repeat(RemoteServer.MAX_FAILURES + 1) { call("/api/status", pin = "1111") }
        assertEquals(200, call("/api/status", pin = null).first) // never locks without a PIN
    }

    @Test
    fun pinCanBeSwitchedOnWhileRunning() {
        pin = null
        assertEquals(200, call("/api/status", pin = null).first)
        pin = "9876"
        assertEquals(401, call("/api/status", pin = null).first)
        assertEquals(200, call("/api/status", pin = "9876").first)
    }

    @Test
    fun locksAfterTooManyWrongPins() {
        repeat(RemoteServer.MAX_FAILURES) { call("/api/status", pin = "1111") }
        assertEquals(403, call("/api/status").first) // even the right PIN is refused while locked
        now += RemoteServer.LOCK_MILLIS + 1
        assertEquals(200, call("/api/status").first)
    }

    @Test
    fun commandsReachTheApp() {
        assertEquals(200, call("/api/cmd", form = mapOf("cmd" to "play")).first)
        assertEquals(200, call("/api/cmd", form = mapOf("cmd" to "up", "value" to "1")).first)
        assertEquals(400, call("/api/cmd", form = mapOf("cmd" to "explode")).first)
        assertEquals(listOf("play" to null, "up" to "1"), commands)
    }

    @Test
    fun savesTeluguAndTenglishScriptsIntact() {
        val body = "నమస్కారం అందరికీ!\nNamaskaram andariki & \"welcome\" 100%"
        val (code, resp) = call("/api/script", form = mapOf("title" to "తెలుగు Intro", "body" to body))
        assertEquals(200, code)
        assertTrue(resp.contains("new-id"))
        assertEquals(Triple(null, "తెలుగు Intro", body), saved.single())
    }

    @Test
    fun readsScripts() {
        assertTrue(call("/api/scripts").second.contains("Intro"))
        assertEquals(200, call("/api/script?id=a").first)
        assertEquals(404, call("/api/script?id=zzz").first)
    }

    @Test
    fun laptopMicWordsReachTheApp() {
        assertEquals(200, call("/api/heard", form = mapOf("text" to "నమస్కారం అందరికీ")).first)
        assertEquals(200, call("/api/heard", form = mapOf("text" to "")).first) // heartbeat
        assertEquals(401, call("/api/heard", pin = "0000", form = mapOf("text" to "x")).first)
        assertEquals(listOf("నమస్కారం అందరికీ", ""), heard)
    }

    @Test
    fun allowsSavedPageToCallAcrossOrigins() {
        val c = URL("http://127.0.0.1:$port/api/heard").openConnection() as HttpURLConnection
        c.requestMethod = "OPTIONS"
        c.setRequestProperty("Origin", "null")
        c.setRequestProperty("Access-Control-Request-Method", "POST")
        c.setRequestProperty("Access-Control-Request-Headers", "x-pin")
        assertEquals(204, c.responseCode) // preflight needs no PIN
        assertEquals("*", c.getHeaderField("Access-Control-Allow-Origin"))
        assertTrue(c.getHeaderField("Access-Control-Allow-Headers").contains("X-Pin"))
        assertEquals("true", c.getHeaderField("Access-Control-Allow-Private-Network"))
        assertTrue(heard.isEmpty())
    }

    @Test
    fun servesOverHttpsWithBundledCertificate() {
        val tlsPort = ServerSocket(0).use { it.localPort }
        val tls = RemoteServer(tlsPort, { null }, "<html>secure remote</html>", api)
        tls.makeSecure(RemoteServer.sslFactory(java.io.File("src/main/assets/remote-tls.p12").inputStream()), null)
        tls.start(5000, false)
        try {
            // Accept the self-signed certificate, like the user clicking "Proceed" in Chrome.
            val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
                override fun checkClientTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun checkServerTrusted(c: Array<java.security.cert.X509Certificate>, a: String) {}
                override fun getAcceptedIssuers() = arrayOf<java.security.cert.X509Certificate>()
            })
            val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, trustAll, null) }
            val c = URL("https://127.0.0.1:$tlsPort/").openConnection() as javax.net.ssl.HttpsURLConnection
            c.sslSocketFactory = ctx.socketFactory
            c.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, _ -> true }
            assertEquals(200, c.responseCode)
            assertTrue(c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }.contains("secure remote"))
        } finally {
            tls.stop()
        }
    }

    @Test
    fun jsonEscapes() {
        assertEquals("{\"a\":\"x\\\"y\\nz\",\"b\":3,\"c\":null}", Json.obj("a" to "x\"y\nz", "b" to 3, "c" to null))
    }
}
