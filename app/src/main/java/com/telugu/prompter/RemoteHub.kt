package com.telugu.prompter

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableSharedFlow
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

sealed interface RemoteCommand {
    /** A prompter control (see [RemoteServer] for the list). */
    data class Prompter(val cmd: String, val value: String?) : RemoteCommand
    /** A script was sent from the laptop; open it. */
    data class OpenScript(val id: String) : RemoteCommand
    /** Words the laptop's mic heard (current utterance). */
    data class Heard(val text: String) : RemoteCommand
    /** A script was saved from the laptop without opening it; refresh the list. */
    data object ScriptsChanged : RemoteCommand
}

/**
 * Phone side of the laptop remote: owns the [RemoteServer]s (https for the laptop mic, plain
 * http as a backup), hands commands to the UI through [commands], and serves whatever the
 * prompter last published in [status].
 */
class RemoteHub(
    context: Context,
    private val store: ScriptStore,
    private val page: String,
    /** Opens the bundled TLS keystore (assets/remote-tls.p12). */
    private val openTlsKeystore: () -> InputStream,
) {

    val commands = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 64)

    /** JSON published by the screen currently showing; read by the server thread. */
    @Volatile var status: String = Json.obj("screen" to "list")

    var isOn by mutableStateOf(false); private set

    /** Off by default: anyone on the same Wi-Fi can connect. On: the laptop needs [pin]. */
    var requirePin by mutableStateOf(store.loadRemoteRequirePin()); private set
    /** Fixed PIN (kept across restarts, so the laptop remembers it). */
    var pin by mutableStateOf(store.loadRemotePin()); private set
    /** https addresses: controls + laptop mic. */
    var secureUrls by mutableStateOf(emptyList<String>()); private set
    /** Plain http addresses: controls only (Chrome blocks the mic there). */
    var plainUrls by mutableStateOf(emptyList<String>()); private set
    var error by mutableStateOf<String?>(null); private set

    /** True while the laptop page is listening and sending words (or heartbeats). */
    var laptopMicActive by mutableStateOf(false); private set

    private val main = Handler(Looper.getMainLooper())

    // Phone Wi-Fi naps between packets to save power, adding 100 ms+ spikes; while the remote is
    // on, ask for low-latency Wi-Fi so laptop-mic words arrive promptly.
    private val wifiLock: WifiManager.WifiLock? = runCatching {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        wm.createWifiLock(mode, "prompter-remote").apply { setReferenceCounted(false) }
    }.getOrNull()
    private var servers: List<RemoteServer> = emptyList()
    private val laptopMicExpired = Runnable { laptopMicActive = false }
    @Volatile private var httpsPort = 0

    /** What the server checks against; null = no PIN. Read on the server thread. */
    @Volatile private var activePin: String? = if (requirePin) pin else null

    fun updateRequirePin(on: Boolean) {
        requirePin = on
        store.saveRemoteRequirePin(on)
        activePin = if (on) pin else null
    }

    fun changePin() {
        pin = ScriptStore.newRemotePin()
        store.saveRemotePin(pin)
        if (requirePin) activePin = pin
    }

    private val api = object : RemoteServer.Api {
        // The page uses httpsPort to point plain-http visitors at the mic-capable address.
        override fun statusJson(): String {
            val s = status
            val extra = "\"httpsPort\":$httpsPort"
            return if (s.length <= 2) "{$extra}" else s.dropLast(1) + ",$extra}"
        }

        override fun command(cmd: String, value: String?): Boolean {
            if (cmd == "openScript") {
                val id = value ?: return false
                return commands.tryEmit(RemoteCommand.OpenScript(id))
            }
            if (cmd !in PROMPTER_COMMANDS) return false
            return commands.tryEmit(RemoteCommand.Prompter(cmd, value))
        }

        override fun scriptsJson() = Json.arr(store.loadScripts().map { Json.obj("id" to it.id, "title" to it.title) })

        override fun scriptJson(id: String) = store.loadScripts().find { it.id == id }
            ?.let { Json.obj("id" to it.id, "title" to it.title, "body" to it.body) }

        override fun saveScript(id: String?, title: String, body: String, open: Boolean): String {
            val script = Script(id ?: ScriptStore.newId(), title.ifBlank { body.trim().take(30) }, body)
            store.upsert(script)
            commands.tryEmit(if (open) RemoteCommand.OpenScript(script.id) else RemoteCommand.ScriptsChanged)
            return script.id
        }

        override fun heard(text: String) {
            main.post {
                laptopMicActive = true
                main.removeCallbacks(laptopMicExpired)
                main.postDelayed(laptopMicExpired, LAPTOP_MIC_TIMEOUT_MS)
            }
            if (text.isNotBlank()) commands.tryEmit(RemoteCommand.Heard(text))
        }
    }

    /** Starts a server on the first free port in [ports]; null if none could start. */
    private fun startOn(ports: IntRange, secure: Boolean): Pair<RemoteServer, Int>? {
        for (p in ports) {
            val s = RemoteServer(p, { activePin }, page, api)
            try {
                if (secure) s.makeSecure(RemoteServer.sslFactory(openTlsKeystore()), null)
                s.start(SOCKET_TIMEOUT_MS, true)
                return s to p
            } catch (_: IOException) {
                // Port busy; try the next one.
            }
        }
        return null
    }

    fun start() {
        if (isOn) return
        thread(name = "remote-start") {
            val plain = startOn(HTTP_PORTS, secure = false)
            val secure = try {
                startOn(HTTPS_PORTS, secure = true)
            } catch (_: Exception) {
                null // keystore problem: keep the plain remote working
            }
            val hosts = localAddresses()
            main.post {
                if (plain == null && secure == null) {
                    error = "Could not start the remote (no free port)."
                    return@post
                }
                servers = listOfNotNull(plain?.first, secure?.first)
                runCatching { wifiLock?.acquire() }
                httpsPort = secure?.second ?: 0
                secureUrls = secure?.let { (_, port) -> hosts.map { "https://$it:$port" } }.orEmpty()
                plainUrls = plain?.let { (_, port) -> hosts.map { "http://$it:$port" } }.orEmpty()
                error = when {
                    hosts.isEmpty() -> "No Wi-Fi found. Join Wi-Fi or turn on the hotspot, then restart Remote."
                    secure == null -> "Secure address unavailable; the laptop mic won't work. Controls still work."
                    else -> null
                }
                isOn = true
            }
        }
    }

    fun stop() {
        servers.forEach { it.stop() }
        servers = emptyList()
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
        httpsPort = 0
        main.removeCallbacks(laptopMicExpired)
        laptopMicActive = false
        isOn = false
        secureUrls = emptyList()
        plainUrls = emptyList()
    }

    companion object {
        private val HTTP_PORTS = 8080..8090
        private val HTTPS_PORTS = 8443..8453
        /** Idle read timeout; the laptop page pings every 2 s, so its open connection stays up. */
        private const val SOCKET_TIMEOUT_MS = 15000
        /** No words or heartbeat from the laptop for this long → phone mic takes over again. */
        private const val LAPTOP_MIC_TIMEOUT_MS = 4000L

        val PROMPTER_COMMANDS = setOf(
            "play", "pause", "toggle", "mode", "faster", "slower",
            "bigger", "smaller", "up", "down", "top", "flip", "mirror",
            "section", "nextSection", "prevSection", "seek",
        )

        /** IPv4 addresses on Wi-Fi / hotspot interfaces the laptop can reach. */
        fun localAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("swlan")) 0 else 1 }
                .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().filter { it.isSiteLocalAddress } }
                .mapNotNull { it.hostAddress }
                .distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }
}

@Composable
private fun UrlList(urls: List<String>, big: Boolean) {
    SelectionContainer {
        Column {
            urls.forEach {
                Text(
                    it, fontFamily = FontFamily.Monospace,
                    fontSize = if (big) 18.sp else 14.sp,
                    fontWeight = if (big) FontWeight.Bold else FontWeight.Normal,
                    color = if (big) MaterialTheme.colorScheme.primary else Color.LightGray,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

@Composable
fun RemoteDialog(hub: RemoteHub, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Laptop remote") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Remote", modifier = Modifier.weight(1f), fontSize = 16.sp)
                    Switch(checked = hub.isOn, onCheckedChange = { if (it) hub.start() else hub.stop() })
                }
                if (hub.isOn) {
                    Text("Put the laptop on the same Wi-Fi (or on this phone's hotspot), then open in Chrome:",
                        Modifier.padding(top = 12.dp))
                    if (hub.secureUrls.isNotEmpty()) {
                        Text("Controls + laptop mic", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                        UrlList(hub.secureUrls, big = true)
                        Text("First time: Chrome says \"Your connection is not private\". Click Advanced → Proceed. It's your own phone.",
                            color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (hub.plainUrls.isNotEmpty()) {
                        Text("Backup: controls only", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
                        UrlList(hub.plainUrls, big = false)
                    }
                } else {
                    Text("Turn on to control the prompter, and use the laptop's mic, from Chrome on your laptop.",
                        Modifier.padding(top = 12.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Require PIN", fontSize = 16.sp)
                        Text("Turn on for café / office / hotel Wi-Fi", color = Color.Gray, fontSize = 12.sp)
                    }
                    Switch(checked = hub.requirePin, onCheckedChange = { hub.updateRequirePin(it) })
                }
                if (hub.requirePin) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(hub.pin, fontFamily = FontFamily.Monospace, fontSize = 30.sp, fontWeight = FontWeight.Bold,
                            letterSpacing = 6.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { hub.changePin() }) { Text("New PIN") }
                    }
                    Text("Stays the same until you tap New PIN; the laptop remembers it.", color = Color.Gray, fontSize = 12.sp)
                }
                hub.error?.let { Text(it, color = Color(0xFFFF8A80), modifier = Modifier.padding(top = 10.dp)) }
                Text("Remote stays on while the app is open.", color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
