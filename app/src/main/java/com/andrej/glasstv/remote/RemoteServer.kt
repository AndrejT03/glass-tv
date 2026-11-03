package com.andrej.glasstv.remote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.compose.runtime.snapshotFlow
import androidx.core.content.ContextCompat
import com.andrej.glasstv.GlassApp
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Glass's door for its iPhone app (`ios/`): a small HTTP server on the home network, on port 7676,
 * which the phone finds by Bonjour (`_glass._tcp`). Only paired phones get past [Pairing]. The
 * phone reads the state (`GET /v1/state`), follows it (`GET /v1/events`, server-sent events, while
 * it is open) and acts with a POST per control; see [RemoteApi] for both.
 *
 * At rest it costs the TV nothing: one thread waiting for a connection. While a phone follows the
 * state, the main thread works the state out again whenever something in it changes, and the volume
 * is read when the remote's own buttons change it. Every thread of it runs at background priority,
 * so it never competes with the launcher's frames.
 */
class RemoteServer(private val app: GlassApp) {
    val pairing = Pairing(app)
    private val api = RemoteApi(app, pairing)
    private val main = Handler(Looper.getMainLooper())
    private val scope = MainScope()
    private val workers = ThreadPoolExecutor(0, MAX_CONNECTIONS, 30, TimeUnit.SECONDS, SynchronousQueue(), ThreadFactory { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "remote").apply { isDaemon = true }
    })

    // The phones following the state, each with its queue of events, and the state last sent to
    // them; on the main thread.
    private val followers = CopyOnWriteArraySet<LinkedBlockingQueue<String>>()
    private var following: Job? = null
    private var sent: String? = null

    // The remote's volume buttons go to the system, not to Glass: the broadcast says when they did.
    private val volumeChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = app.volume.refresh()
    }

    fun start() {
        Thread(::serve, "remote-accept").apply { isDaemon = true }.start()
    }

    private fun serve() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val server = listen() ?: return
        Log.i(TAG, "Listening on port ${server.localPort}")
        port = server.localPort
        api.renamed = { workers.execute { advertise() } }
        advertise()
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: IOException) {
                Log.w(TAG, "accept failed", e)
                continue
            }
            try {
                workers.execute { handle(socket) }
            } catch (e: RejectedExecutionException) {
                runCatching { socket.close() }
            }
        }
    }

    private fun listen(): ServerSocket? {
        for (port in listOf(PORT, 0)) {
            try {
                return ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
            } catch (e: IOException) {
                Log.w(TAG, "Port $port is taken", e)
            }
        }
        return null
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            val request = Http.read(BufferedInputStream(socket.getInputStream())) ?: return
            if (request.method == "GET" && request.path == "/v1/events") return follow(socket, request)
            val response = try {
                route(request)
            } catch (e: JSONException) {
                Http.error(400, "bad-request")
            } catch (e: Exception) {
                Log.w(TAG, "${request.method} ${request.path} failed", e)
                Http.error(500, "failed")
            }
            Http.write(socket.getOutputStream(), response)
        } catch (e: IOException) {
            // The phone went away mid-request.
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun route(r: Http.Request): Http.Response {
        val post = r.method == "POST"
        when (r.path) {
            "/v1/info" -> return Http.json(api.info(pairing.authorized(r.token)))
            "/v1/pair" -> if (post) {
                pairing.start()
                return Http.ok()
            }
            "/v1/pair/confirm" -> if (post) {
                val body = r.json()
                return when (val answer = pairing.confirm(body.getString("code"), body.optString("device", "iPhone"))) {
                    is Pairing.Answer.Paired -> Http.json(JSONObject().put("token", answer.token).put("tv", api.info(paired = true)))
                    Pairing.Answer.Wrong -> Http.error(401, "wrong-code")
                    Pairing.Answer.Over -> Http.error(410, "code-over")
                }
            }
            "/v1/pair/cancel" -> if (post) {
                pairing.cancel()
                return Http.ok()
            }
        }
        val token = r.token
        if (token == null || !pairing.authorized(token)) return Http.error(401, "unpaired")
        return when {
            r.method == "GET" && r.path == "/v1/state" -> Http.json(api.state())
            r.method == "GET" && r.path == "/v1/stats" -> Http.json(api.stats())
            r.method == "GET" && r.path == "/v1/apps/art" -> api.art(r.query["key"]) ?: Http.error(404, "no-app")
            r.method == "GET" && r.path == "/v1/dynamic" -> api.dynamic()
            r.method == "GET" && r.path == "/v1/dynamic/frame" -> api.dynamicFrame(r.query["id"], r.query["frame"])
            post && r.path == "/v1/unpair" -> {
                pairing.forget(token)
                Http.ok()
            }
            post -> api.act(r.path, r.json())
            else -> Http.error(404, "unknown")
        }
    }

    /** `GET /v1/events`: the state now, then again whenever it changes, until the phone goes. */
    private fun follow(socket: Socket, r: Http.Request) {
        val out = socket.getOutputStream()
        if (!pairing.authorized(r.token)) return Http.write(out, Http.error(401, "unpaired"))
        if (followers.size >= MAX_FOLLOWERS) return Http.write(out, Http.error(503, "busy"))
        socket.soTimeout = 0
        out.write("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\nTransfer-Encoding: chunked\r\n\r\n".toByteArray())
        out.flush()
        api.readPicture()
        // Dynamic wallpapers pushed since Glass started, for the phone's picker.
        app.dynamic.read()
        val queue = LinkedBlockingQueue<String>()
        main.post { start(queue) }
        try {
            while (true) {
                // A comment now and then, so a phone that went away without a word is found out.
                Http.chunk(out, queue.poll(PING_S, TimeUnit.SECONDS) ?: ": ping\n\n")
            }
        } finally {
            main.post { stop(queue) }
        }
    }

    @OptIn(FlowPreview::class)
    private fun start(queue: LinkedBlockingQueue<String>) {
        followers += queue
        // Glass only reads the volume when something shows it: read it now, for the first state sent.
        app.volume.refresh()
        // Nor has it listed the apps when the TV started it again without the home screen (after an
        // update, or memory taken back for an app): list them now; the state follows when they're in.
        if (app.apps.all.isEmpty()) scope.launch { app.apps.reload() }
        val now = api.json().toString()
        queue.offer(event(now))
        if (following != null) return
        sent = now
        following = scope.launch {
            // TCL keeps the picture per source and doesn't say when it changes: read it again when
            // another app or input comes to the front, and when the TV wakes (the volume too). Not
            // asleep: TCL then reports its standby's own (muted, another preset), which the TV awake
            // doesn't have.
            launch {
                snapshotFlow { app.recents.front to app.screenOn }
                    .drop(1)
                    .debounce(PICTURE_SETTLE_MS)
                    .collect { (_, on) ->
                        if (!on) return@collect
                        app.volume.refresh()
                        runCatching { workers.execute { api.readPicture() } }
                    }
            }
            snapshotFlow { api.json().toString() }
                .debounce(DEBOUNCE_MS)
                .collect { json ->
                    if (json == sent) return@collect
                    sent = json
                    followers.forEach { it.offer(event(json)) }
                }
        }
        ContextCompat.registerReceiver(app, volumeChanges, IntentFilter(VOLUME_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    private fun stop(queue: LinkedBlockingQueue<String>) {
        followers -= queue
        if (followers.isNotEmpty() || following == null) return
        following?.cancel()
        following = null
        runCatching { app.unregisterReceiver(volumeChanges) }
    }

    private fun event(json: String) = "event: state\ndata: $json\n\n"

    private var port = PORT

    // The registration under the TV's current name; a new name replaces it (a listener serves one at a time).
    private var registration: NsdManager.RegistrationListener? = null

    /** Bonjour, so the phone finds the TV by itself under its name; [Pairing.tvId] goes along to know it again. */
    @Synchronized
    private fun advertise() {
        val nsd = app.getSystemService(NsdManager::class.java) ?: return
        registration?.let { old -> runCatching { nsd.unregisterService(old) } }
        val listening = port
        val info = NsdServiceInfo().apply {
            serviceName = api.tvName
            serviceType = SERVICE_TYPE
            setPort(listening)
            setAttribute("id", pairing.tvId)
            setAttribute("api", RemoteApi.API.toString())
        }
        val listener = Registration()
        registration = listener
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure { Log.w(TAG, "Couldn't advertise", it) }
    }

    private class Registration : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            Log.i(TAG, "Advertised as '${info.serviceName}'")
        }

        override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) {
            Log.w(TAG, "Advertising failed: $error")
        }

        override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

        override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
    }

    private companion object {
        const val TAG = "Remote"
        const val PORT = 7676
        const val SERVICE_TYPE = "_glass._tcp"
        const val MAX_CONNECTIONS = 12
        const val MAX_FOLLOWERS = 4
        const val READ_TIMEOUT_MS = 10_000
        const val PING_S = 15L
        const val DEBOUNCE_MS = 120L

        /** How long after a change of app or input TCL has the new source's picture settled. */
        const val PICTURE_SETTLE_MS = 1_500L

        const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    }
}

/** Just enough HTTP/1.1 for the phone: one request per connection, JSON both ways. */
internal object Http {
    class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        private val headers: Map<String, String>,
        private val body: ByteArray,
    ) {
        val token: String? get() = headers["authorization"]?.takeIf { it.startsWith("Bearer ") }?.substring(7)?.trim()

        fun json(): JSONObject = if (body.isEmpty()) JSONObject() else JSONObject(String(body, Charsets.UTF_8))
    }

    class Response(val status: Int, val type: String, val body: ByteArray, val cacheable: Boolean = false)

    fun json(o: JSONObject, status: Int = 200) = Response(status, "application/json; charset=utf-8", o.toString().toByteArray())

    fun ok() = json(JSONObject().put("ok", true))

    fun error(status: Int, code: String) = json(JSONObject().put("error", code), status)

    fun read(input: InputStream): Request? {
        val start = line(input)?.split(' ') ?: return null
        if (start.size < 3) return null
        val headers = HashMap<String, String>()
        while (true) {
            val header = line(input) ?: return null
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon > 0) headers[header.substring(0, colon).trim().lowercase()] = header.substring(colon + 1).trim()
            if (headers.size > MAX_HEADERS) return null
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length !in 0..MAX_BODY) return null
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        val target = start[1]
        val query = target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
            decode(it.substringBefore('=')) to decode(it.substringAfter('=', ""))
        }
        return Request(start[0].uppercase(), target.substringBefore('?'), query, headers, body)
    }

    fun write(out: OutputStream, r: Response) {
        val head = "HTTP/1.1 ${r.status} ${reason(r.status)}\r\n" +
            "Content-Type: ${r.type}\r\n" +
            "Content-Length: ${r.body.size}\r\n" +
            "Cache-Control: ${if (r.cacheable) "max-age=86400" else "no-store"}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray())
        out.write(r.body)
        out.flush()
    }

    /** One chunk of a chunked response (the events). */
    fun chunk(out: OutputStream, text: String) {
        val bytes = text.toByteArray()
        out.write("${Integer.toHexString(bytes.size)}\r\n".toByteArray())
        out.write(bytes)
        out.write("\r\n".toByteArray())
        out.flush()
    }

    private fun line(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return null
            if (b == '\n'.code) break
            if (b != '\r'.code) out.write(b)
            if (out.size() > MAX_LINE) return null
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun reason(status: Int) = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        409 -> "Conflict"
        410 -> "Gone"
        503 -> "Service Unavailable"
        else -> "Error"
    }

    private const val MAX_LINE = 8_192
    private const val MAX_HEADERS = 64
    private const val MAX_BODY = 65_536
}
