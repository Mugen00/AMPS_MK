package dev.amps.app.data.remote

import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.amps.app.util.localIPv4Addresses
import dev.amps.app.util.readableMessage
import dev.amps.app.util.subnetBroadcastAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * A bridge on the LAN that answered the discovery ping.
 *
 * [url] is the only field the app acts on; the rest is what the settings screen
 * shows, so a user can tell "found, but no SauceNAO key" from "found and ready".
 */
data class DiscoveredBridge(
    val url: String,
    val version: String?,
    val host: String?,
    val traceMoeKey: Boolean,
    val sauceNaoKey: Boolean,
    val tracemoeReady: Boolean?,
    val imgfindReady: Boolean?,
    val latencyMs: Long,
)

/**
 * Finds the bridge on the LAN without asking the user for anything.
 *
 * 1. `AMPS_DISCOVER_V1` goes out as a UDP datagram to 255.255.255.255:8788, to the
 *    broadcast address of every active IPv4 subnet and to 10.0.2.2 (the emulator's
 *    route to the host). A bridge answers with one UTF-8 JSON line naming its own
 *    HTTP port and its key state.
 * 2. Every reply that claims `service == "amps-bridge"` is checked against
 *    `GET <url>/api/health`; only a 200 with `ok == true` is a bridge.
 * 3. If nothing answered the datagram (an older bridge predates the ping), the
 *    same hosts are probed directly on port 8787.
 *
 * The socket is bound with `DatagramSocket()`, never `DatagramSocket(port)`: the
 * latter needs a permission Android does not grant to a normal app.
 *
 * Results are memoised for [CACHE_TTL_MS] so a search never re-broadcasts.
 */
class BridgeDiscovery(
    private val client: OkHttpClient = ownClient(),
    private val context: Context? = null,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 4 s health check, single attempt: a slow answer is as good as no answer. */
    private val healthClient by lazy {
        client.newBuilder()
            .connectTimeout(HEALTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(HEALTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(HEALTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    /** Tighter budget for the blind port scan, which walks several hosts. */
    private val scanClient by lazy {
        client.newBuilder()
            .connectTimeout(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }

    private val discovery = Mutex()

    /** The bridge to use, or null when this network has none. Never throws. */
    suspend fun discover(): DiscoveredBridge? = withContext(Dispatchers.IO) {
        discovery.withLock {
            freshCache()?.let { return@withLock it }
            val hosts = candidateHosts()
            val replies = collectReplies()
            if (replies.size > 1) {
                debug("several bridges answered, the fastest one wins: " + replies.joinToString { "${it.url} (${it.latencyMs} ms)" })
            }
            val bridge = verify(replies) ?: scanHosts(hosts)
            if (bridge == null) {
                debug("no bridge on ${hosts.size} candidate host(s): ${hosts.joinToString()}")
            } else {
                remember(bridge)
            }
            bridge
        }
    }

    /** True when `<url>/api/health` answers 200 with `ok == true`. */
    suspend fun probe(url: String): Boolean = withContext(Dispatchers.IO) {
        healthOk(normalizeBaseUrl(url), healthClient)
    }

    /**
     * One broadcast round. Sends the ping, then listens until the window closes.
     * Never throws: a phone with no LAN permission simply returns nothing.
     */
    private fun collectReplies(): List<DiscoveredBridge> {
        val payload = DISCOVERY_PAYLOAD.toByteArray(Charsets.UTF_8)
        val ownAddresses = localIPv4Addresses().toSet()
        val found = LinkedHashMap<String, DiscoveredBridge>()
        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = UDP_SO_TIMEOUT_MS
                socket.broadcast = true
                val sentAt = SystemClock.elapsedRealtime()
                broadcastTargets().forEach { (host, port) -> send(socket, host, port, payload) }
                val deadline = sentAt + COLLECT_WINDOW_MS
                val buffer = ByteArray(REPLY_BUFFER_BYTES)
                while (SystemClock.elapsedRealtime() < deadline) {
                    val packet = receive(socket, buffer, deadline) ?: break
                    val text = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
                    val bridge = parseReply(
                        text = text,
                        sender = packet.address,
                        senderPort = packet.port,
                        latencyMs = SystemClock.elapsedRealtime() - sentAt,
                        ownAddresses = ownAddresses,
                    )
                    if (bridge == null) {
                        debug("ignored an unexpected reply from ${packet.address.hostAddress}: $text")
                        continue
                    }
                    val previous = found[bridge.url]
                    if (previous == null || bridge.latencyMs < previous.latencyMs) found[bridge.url] = bridge
                }
            }
        } catch (failure: IOException) {
            debug("udp discovery unavailable: ${failure.readableMessage()}")
            return emptyList()
        }
        return found.values.sortedBy { it.latencyMs }
    }

    /** Waits until the deadline; `null` means the window closed or the socket died. */
    private fun receive(socket: DatagramSocket, buffer: ByteArray, deadline: Long): DatagramPacket? {
        val remaining = deadline - SystemClock.elapsedRealtime()
        if (remaining <= 0) return null
        // The socket keeps the 1.5 s SO_TIMEOUT, but a single wait must never
        // outlast the collection window.
        socket.soTimeout = remaining.toInt().coerceIn(MIN_WAIT_MS, UDP_SO_TIMEOUT_MS)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            packet
        } catch (timeout: SocketTimeoutException) {
            debug("no reply within ${socket.soTimeout} ms")
            null
        } catch (failure: IOException) {
            debug("udp receive failed: ${failure.readableMessage()}")
            null
        }
    }

    private fun send(socket: DatagramSocket, host: String, port: Int, payload: ByteArray) {
        val address = runCatching { InetAddress.getByName(host) }.getOrNull()
        if (address == null) {
            debug("cannot resolve broadcast target $host")
            return
        }
        runCatching { socket.send(DatagramPacket(payload, payload.size, address, port)) }
            .onFailure { debug("cannot reach $host:$port — ${it.readableMessage()}") }
    }

    /** Everything the ping goes to: the limited broadcast, each subnet, the emulator host. */
    private fun broadcastTargets(): List<Pair<String, Int>> = buildList {
        add(LIMITED_BROADCAST to DISCOVERY_PORT)
        add(EMULATOR_HOST to DISCOVERY_PORT)
        subnetBroadcastAddress().forEach { address ->
            address.hostAddress?.let { add(it to DISCOVERY_PORT) }
        }
    }

    /**
     * Hosts worth a direct unicast attempt: the emulator's alias to the host plus the
     * phone's own interface addresses. Guessing `x.y.z.1` would be a guess, and the DHCP
     * gateway is not readable from the public SDK, so it is not used.
     */
    private fun candidateHosts(): List<String> = buildList {
        add(EMULATOR_HOST)
        addAll(localIPv4Addresses())
    }.distinct()

    private fun verify(replies: List<DiscoveredBridge>): DiscoveredBridge? {
        replies.forEach { reply ->
            if (healthOk(reply.url, healthClient)) return reply
            debug("${reply.url} answered the ping but /api/health did not confirm it")
        }
        return null
    }

    /** Older bridge: no discovery listener, but the HTTP port is still there. */
    private fun scanHosts(hosts: List<String>): DiscoveredBridge? {
        hosts.forEach { host ->
            val url = "http://$host:$HTTP_PORT"
            val startedAt = SystemClock.elapsedRealtime()
            if (!healthOk(url, scanClient)) return@forEach
            debug("bridge found by scanning $url")
            return DiscoveredBridge(
                url = url,
                version = null,
                host = null,
                traceMoeKey = false,
                sauceNaoKey = false,
                tracemoeReady = null,
                imgfindReady = null,
                latencyMs = SystemClock.elapsedRealtime() - startedAt,
            )
        }
        return null
    }

    private fun healthOk(baseUrl: String, http: OkHttpClient): Boolean = try {
        val request = Request.Builder()
            .url("$baseUrl/api/health")
            .header("Accept", "application/json")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                debug("$baseUrl answered HTTP ${response.code}")
                return@use false
            }
            val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
            val ok = root?.bool("ok") ?: false
            if (!ok) debug("$baseUrl answered, but ok != true")
            ok
        }
    } catch (failure: Exception) {
        debug("health check of $baseUrl failed: ${failure.readableMessage()}")
        false
    }

    private fun parseReply(
        text: String,
        sender: InetAddress,
        senderPort: Int,
        latencyMs: Long,
        ownAddresses: Set<String>,
    ): DiscoveredBridge? {
        val root = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        if (root.str("service") != SERVICE_NAME) return null
        val protocol = root.int("protocol")
        if (protocol != null && protocol != PROTOCOL_VERSION) {
            debug("bridge speaks protocol $protocol, this build expects $PROTOCOL_VERSION")
        }
        val keys = root.obj("keys")
        val nodes = root.obj("mcp")
        return DiscoveredBridge(
            url = replyUrl(root, sender, senderPort, ownAddresses),
            version = root.str("version"),
            host = root.str("host"),
            traceMoeKey = keys?.bool("traceMoe") ?: false,
            sauceNaoKey = keys?.bool("sauceNao") ?: false,
            // A node the bridge has not probed yet sends no key at all; that is
            // "unknown", not "not ready", and the two must not be shown the same way.
            tracemoeReady = nodes?.bool("tracemoe"),
            imgfindReady = nodes?.bool("imgfind"),
            latencyMs = latencyMs,
        )
    }

    /**
     * The reply names its own HTTP address, but a bridge that builds that name
     * from the datagram it received ends up echoing the phone's address back. A
     * URL pointing at this device is never the bridge, so the address the reply
     * actually came from wins: the datagram sender is by definition the machine
     * running the responder.
     */
    private fun replyUrl(
        root: JsonObject,
        sender: InetAddress,
        senderPort: Int,
        ownAddresses: Set<String>,
    ): String {
        val port = root.int("port") ?: if (senderPort == DISCOVERY_PORT) HTTP_PORT else senderPort
        val advertised = root.str("http")
        val advertisedHost = advertised?.toHttpUrlOrNull()?.host
        if (advertisedHost != null && advertisedHost in ownAddresses) {
            debug("the reply named this phone ($advertisedHost); using its sender ${sender.hostAddress} instead")
            return "http://${sender.hostAddress}:$port"
        }
        return normalizeBaseUrl(advertised ?: "http://${sender.hostAddress}:$port")
    }

    /**
     * The default gateway is deliberately not consulted: `LinkProperties.getGateway()`
     * and `android.net.LinkRoute` are both hidden from the public SDK (verified against
     * android.jar for API 35), so reading the gateway would mean reflection.
     *
     * It is also not needed: the bridge is expected to sit on the same Wi-Fi as the
     * phone, and a subnet broadcast reaches it. The emulator alias and the interface
     * addresses cover the rest.
     */

    private fun normalizeBaseUrl(url: String): String {
        val trimmed = url.trim().trimEnd('/')
        if (trimmed.isEmpty()) return trimmed
        return if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "http://$trimmed"
    }

    private fun freshCache(): DiscoveredBridge? {
        val bridge = cached ?: return null
        val age = cachedAt
        if (age > 0L && SystemClock.elapsedRealtime() - age > CACHE_TTL_MS) {
            debug("cached bridge ${bridge.url} is older than 10 minutes, looking again")
            forget()
            return null
        }
        return bridge
    }

    private fun debug(message: String) {
        Log.d(LOG_TAG, message)
    }

    companion object {
        /** Last bridge that passed its health check, shared by every caller. */
        @Volatile
        var cached: DiscoveredBridge? = null

        @Volatile
        private var cachedAt: Long = 0L

        /** The address to use without paying for discovery again, or null. */
        fun cachedUrl(): String? = cached?.url

        /** Drops the memo, e.g. after the user switches network on purpose. */
        fun forget() {
            cached = null
            cachedAt = 0L
        }

        internal fun remember(bridge: DiscoveredBridge) {
            cached = bridge
            cachedAt = SystemClock.elapsedRealtime()
        }
    }
}

/** Keeps `BridgeDiscovery()` usable on its own, e.g. from a preview or a test. */
private fun ownClient(): OkHttpClient = OkHttpClient.Builder()
    .connectTimeout(HEALTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(HEALTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .build()

private const val LOG_TAG = "AmpsBridgeDiscovery"

/** Handshake string, byte-for-byte what bridge/src/discovery answers to. */
private const val DISCOVERY_PAYLOAD = "AMPS_DISCOVER_V1"
private const val DISCOVERY_PORT = 8788
private const val HTTP_PORT = 8787
private const val SERVICE_NAME = "amps-bridge"
private const val PROTOCOL_VERSION = 1
private const val LIMITED_BROADCAST = "255.255.255.255"

/** The emulator's alias for the machine the app runs on. */
private const val EMULATOR_HOST = "10.0.2.2"

private const val UDP_SO_TIMEOUT_MS = 1_500
private const val MIN_WAIT_MS = 50
private const val COLLECT_WINDOW_MS = 1_200L
private const val REPLY_BUFFER_BYTES = 4 * 1024
private const val HEALTH_TIMEOUT_SECONDS = 4L
private const val SCAN_TIMEOUT_SECONDS = 2L
private const val CACHE_TTL_MS = 10 * 60 * 1000L