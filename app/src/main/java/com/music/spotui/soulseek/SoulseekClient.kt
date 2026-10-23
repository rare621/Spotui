package com.music.spotui.soulseek

import android.util.Log
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.Inflater

/**
 * A minimal, download-oriented Soulseek client.
 *
 * It maintains one TCP connection to the central Soulseek server, logs in, and
 * implements just enough of the peer protocol to (a) run a search and collect
 * [SlskFile] matches from peers, and (b) download a chosen file to disk.
 *
 * NAT handling (essential on mobile): peers can rarely reach us directly, so the
 * server-brokered *indirect* path is the primary route. When a peer wants to
 * reach us the server sends us a [ConnectToPeer]; we dial OUT to the peer and
 * send a PierceFirewall. We also open a best-effort listening socket for the
 * networks where direct inbound connections do work.
 *
 * Protocol reference: Nicotine+ SLSKPROTOCOL.md. Framing:
 *   - server / peer-'P' messages: `<uint32 len><uint32 code><payload>`
 *   - peer-init messages:         `<uint32 len><uint8 code><payload>`
 *   - file-'F' connections:       raw (uint32 token, uint64 offset, then bytes)
 *
 * Peers are strangers: every size coming from them is bounded (frames 16 MB,
 * decompressed search replies [MAX_INFLATED]).
 */
object SoulseekClient {

    private const val TAG = "SoulseekClient"
    private const val SERVER_HOST = "server.slsknet.org"
    private const val SERVER_PORT = 2242
    // Nicotine+ major/minor — widely accepted by the server. Change if the server
    // ever replies INVALIDVERSION.
    private const val VERSION_MAJOR = 160
    private const val VERSION_MINOR = 1
    private const val LISTEN_PORT = 2234

    // Server message codes
    private const val S_LOGIN = 1
    private const val S_SET_WAIT_PORT = 2
    private const val S_GET_PEER_ADDRESS = 3
    private const val S_CONNECT_TO_PEER = 18
    private const val S_FILE_SEARCH = 26
    private const val S_CANT_CONNECT = 1001

    // Peer-init codes
    private const val PI_PIERCE = 0
    private const val PI_PEERINIT = 1

    // Peer message codes
    private const val P_SEARCH_REPLY = 9
    private const val P_TRANSFER_REQUEST = 40
    private const val P_TRANSFER_RESPONSE = 41
    private const val P_QUEUE_UPLOAD = 43
    private const val P_PLACE_IN_QUEUE = 44
    private const val P_UPLOAD_FAILED = 46
    private const val P_UPLOAD_DENIED = 50

    // ── Server connection state ──────────────────────────────────────────────
    @Volatile private var socket: Socket? = null
    @Volatile private var out: DataOutputStream? = null
    @Volatile private var username: String = ""
    @Volatile private var loggedIn = false

    private val tokenSeq = AtomicInteger(1)
    private fun nextToken() = tokenSeq.getAndIncrement() and 0x7FFFFFFF

    // Pending GetPeerAddress replies: username -> queue carrying (ip,port).
    private val addrWaiters = ConcurrentHashMap<String, LinkedBlockingQueue<Pair<String, Int>>>()

    // Active search: token -> collected files.
    private val searchResults = ConcurrentHashMap<Int, CopyOnWriteArrayList<SlskFile>>()

    // The single in-flight download (debug button downloads one track at a time).
    @Volatile private var activeDownload: DownloadState? = null

    private class DownloadState(
        val peer: String,
        val filename: String,
        @Volatile var transferToken: Int = 0,
        @Volatile var expectedSize: Long = 0,
        val maxBytes: Long = Long.MAX_VALUE,
        val events: LinkedBlockingQueue<String> = LinkedBlockingQueue(),
        @Volatile var outFile: File? = null,
        @Volatile var done: Boolean = false,
        @Volatile var ok: Boolean = false,
        @Volatile var onProgress: ((Long, Long) -> Unit)? = null,
    )

    @Volatile var lastError: String? = null
        private set

    // ── Public API ───────────────────────────────────────────────────────────

    /** Connect + log in (idempotent). Returns true on success. Blocking. */
    @Synchronized
    fun ensureLoggedIn(user: String, password: String): Boolean {
        if (loggedIn && socket?.isConnected == true && username == user) return true
        disconnect()
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(SERVER_HOST, SERVER_PORT), 10_000)
            s.soTimeout = 0
            socket = s
            out = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            username = user

            val loginOk = LinkedBlockingQueue<Boolean>(1)
            // Server read loop
            Thread({ serverReadLoop(DataInputStream(s.getInputStream()), loginOk) }, "slsk-server").apply {
                isDaemon = true; start()
            }
            sendLogin(user, password)

            val ok = loginOk.poll(15, TimeUnit.SECONDS) ?: false
            if (ok) {
                loggedIn = true
                sendSetWaitPort(LISTEN_PORT)
                startListener()
                Log.d(TAG, "logged in as $user")
            } else {
                Log.w(TAG, "login failed: $lastError")
                disconnect()
            }
            ok
        } catch (e: Exception) {
            lastError = e.message ?: "connect error"
            Log.e(TAG, "connect/login failed", e)
            disconnect()
            false
        }
    }

    /**
     * Run a search and collect matches. Returns as soon as we have [enoughFlac]
     * FLAC results with a free upload slot (so we don't wait the whole window for
     * a query that already found good peers), or after [windowMs] at the latest.
     */
    fun search(query: String, windowMs: Long = 6_000, enoughFlac: Int = 4): List<SlskFile> {
        if (!loggedIn) return emptyList()
        val token = nextToken()
        val bucket = CopyOnWriteArrayList<SlskFile>()
        searchResults[token] = bucket
        try {
            send(SlskWriter().int(token).str(query).frameServer(S_FILE_SEARCH))
            val deadline = System.currentTimeMillis() + windowMs
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(250)
                if (bucket.count { it.isFlac && it.slotsFree } >= enoughFlac) break
            }
        } catch (e: Exception) {
            Log.w(TAG, "search error", e)
        } finally {
            searchResults.remove(token)
        }
        return bucket.sortedByDescending { it.score() }
    }

    /**
     * Download [file] to [outFile]. Queues the upload with the peer, waits for the
     * peer's TransferRequest, accepts it, then receives the file bytes over an 'F'
     * connection. Blocking; returns true only when the full file was written.
     */
    @Synchronized // one transfer at a time: the read loops route to a single activeDownload
    fun download(
        file: SlskFile,
        outFile: File,
        timeoutMs: Long = 120_000,
        onProgress: ((Long, Long) -> Unit)? = null,
        maxBytes: Long = Long.MAX_VALUE,
    ): Boolean {
        if (maxBytes <= 0 || file.size > maxBytes) { lastError = "cache limit"; return false }
        if (!loggedIn) { lastError = "not logged in"; return false }
        lastError = null
        val state = DownloadState(file.username, file.filename, expectedSize = file.size, outFile = outFile, maxBytes = maxBytes)
        state.onProgress = onProgress
        activeDownload = state
        try {
            // Open a 'P' connection to the peer and queue the upload.
            val peerOut = connectToPeer(file.username, "P") ?: run {
                lastError = "couldn't reach peer ${file.username}"
                return false
            }
            synchronized(peerOut) {
                peerOut.write(SlskWriter().str(file.filename).frameServer(P_QUEUE_UPLOAD))
                peerOut.flush()
            }
            // The peer's TransferRequest / the file transfer are handled by the read
            // loops, which push completion into state.events.
            val deadline = System.currentTimeMillis() + timeoutMs
            while (!state.done && System.currentTimeMillis() < deadline) {
                val ev = state.events.poll(2, TimeUnit.SECONDS) ?: continue
                when (ev) {
                    "denied", "failed" -> { state.done = true; state.ok = false }
                }
            }
            if (!state.done) lastError = "timed out waiting for ${file.username}"
            return state.ok
        } catch (e: Exception) {
            lastError = e.message ?: "download error"
            Log.e(TAG, "download failed", e)
            return false
        } finally {
            state.done = true
            if (activeDownload === state) activeDownload = null
        }
    }

    fun disconnect() {
        loggedIn = false
        runCatching { socket?.close() }
        socket = null; out = null
        // Also stop listening, so "off" really leaves the network.
        runCatching { listener?.close() }
        listener = null
    }

    // ── Server protocol ──────────────────────────────────────────────────────

    private fun sendLogin(user: String, password: String) {
        val hash = md5Hex(user + password)
        send(
            SlskWriter().str(user).str(password).int(VERSION_MAJOR).str(hash).int(VERSION_MINOR)
                .frameServer(S_LOGIN),
        )
    }

    private fun sendSetWaitPort(port: Int) =
        send(SlskWriter().int(port).frameServer(S_SET_WAIT_PORT))

    private fun serverReadLoop(input: DataInputStream, loginOk: LinkedBlockingQueue<Boolean>) {
        try {
            while (true) {
                val body = input.readFrame() ?: break
                val r = SlskReader(body)
                val code = r.int()
                when (code) {
                    S_LOGIN -> {
                        val success = r.bool()
                        if (success) {
                            runCatching { r.str() } // greeting/MOTD
                        } else {
                            lastError = runCatching { r.str() }.getOrDefault("login rejected")
                        }
                        loginOk.offer(success)
                    }
                    S_GET_PEER_ADDRESS -> {
                        val user = r.str()
                        val ip = ipToString(r.int())
                        val port = r.int()
                        addrWaiters[user]?.offer(ip to port)
                    }
                    S_CONNECT_TO_PEER -> handleConnectToPeer(r)
                    else -> { /* ignore the many messages we don't need */ }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "server loop ended: ${e.message}")
        } finally {
            loginOk.offer(false)
            loggedIn = false
        }
    }

    // Dial-outs currently running. Peers keep asking for a minute after a search, and
    // most are unreachable: unbounded, one search spawned ~2,900 ten-second threads.
    private val dialing = AtomicInteger(0)
    private const val MAX_DIALING = 24

    /** Server tells us a peer wants a connection — dial out and pierce their firewall. */
    private fun handleConnectToPeer(r: SlskReader) {
        val peer = r.str()
        val type = r.str()
        val ip = ipToString(r.int())
        val port = r.int()
        val token = r.int()
        // Only while we wait for something: search replies (P) or a file (F).
        val wanted = if (type == "F") activeDownload != null else searchResults.isNotEmpty() || activeDownload != null
        if (!wanted || dialing.incrementAndGet() > MAX_DIALING) {
            if (wanted) dialing.decrementAndGet()
            return
        }
        Thread({
            val s = Socket()
            val pout = try {
                s.connect(InetSocketAddress(ip, port), 5_000)
                DataOutputStream(BufferedOutputStream(s.getOutputStream())).apply {
                    write(SlskWriter().int(token).frameInit(PI_PIERCE))
                    flush()
                }
            } catch (e: Exception) {
                // Most peers are unreachable; logging each one flooded logcat (thousands per search).
                runCatching { s.close() }
                runCatching { send(SlskWriter().int(token).str(peer).frameServer(S_CANT_CONNECT)) }
                null
            } finally {
                dialing.decrementAndGet() // the limit covers dialing, not the open connection
            }
            when {
                pout == null -> Unit
                type == "P" -> peerMessageLoop(peer, s, pout)
                type == "F" -> fileConnectionLoop(s, pierceToken = token)
                else -> runCatching { s.close() }
            }
        }, "slsk-peer-$peer").apply { isDaemon = true; start() }
    }

    /**
     * Open a direct 'P' connection to [peer] (via GetPeerAddress + PeerInit) and,
     * in parallel, ask the server to broker an indirect one. Returns the output
     * stream of whichever handshake we managed to start, or null.
     */
    private fun connectToPeer(peer: String, type: String): DataOutputStream? {
        // Kick off an indirect request too, in case the direct dial is firewalled.
        val token = nextToken()
        runCatching { send(SlskWriter().int(token).str(peer).str(type).frameServer(S_CONNECT_TO_PEER)) }

        val q = addrWaiters.getOrPut(peer) { LinkedBlockingQueue() }
        runCatching { send(SlskWriter().str(peer).frameServer(S_GET_PEER_ADDRESS)) }
        val addr = q.poll(10, TimeUnit.SECONDS)
        addrWaiters.remove(peer)
        if (addr == null || addr.second == 0) return null
        return try {
            val s = Socket()
            s.connect(InetSocketAddress(addr.first, addr.second), 10_000)
            val pout = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            pout.write(SlskWriter().str(username).str(type).int(0).frameInit(PI_PEERINIT))
            pout.flush()
            if (type == "P") {
                Thread({ peerMessageLoop(peer, s, pout) }, "slsk-peer-$peer").apply {
                    isDaemon = true; start()
                }
            }
            pout
        } catch (e: Exception) {
            Log.d(TAG, "direct connect to $peer failed: ${e.message}")
            null
        }
    }

    // ── Listener for inbound peer connections (best effort) ───────────────────

    @Volatile private var listener: ServerSocket? = null
    private fun startListener() {
        if (listener != null) return
        Thread({
            try {
                val ss = ServerSocket(LISTEN_PORT)
                listener = ss
                while (true) {
                    val s = ss.accept()
                    Thread({ handleInbound(s) }, "slsk-inbound").apply { isDaemon = true; start() }
                }
            } catch (e: Exception) {
                Log.d(TAG, "listener ended: ${e.message}")
            }
        }, "slsk-listener").apply { isDaemon = true; start() }
    }

    /** First message on an inbound connection is a peer-init frame (uint8 code). */
    private fun handleInbound(s: Socket) {
        try {
            val din = DataInputStream(s.getInputStream())
            val body = din.readFrame() ?: return
            val code = body[0].toInt() and 0xFF
            val r = SlskReader(body.copyOfRange(1, body.size))
            when (code) {
                PI_PEERINIT -> {
                    val peer = r.str()
                    val type = r.str()
                    val pout = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                    when (type) {
                        "P" -> peerMessageLoop(peer, s, pout, din)
                        "F" -> fileConnectionLoop(s, din = din)
                    }
                }
                PI_PIERCE -> {
                    // We initiated indirectly; the pierce token tells us which. We
                    // don't track P vs F for inbound pierces here — treat as file if
                    // a download is active, else as a peer message stream.
                    val pout = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
                    if (activeDownload != null) fileConnectionLoop(s, din = din)
                    else peerMessageLoop("?", s, pout, din)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "inbound handling failed: ${e.message}")
            runCatching { s.close() }
        }
    }

    // ── Peer 'P' message loop ────────────────────────────────────────────────

    private fun peerMessageLoop(
        peer: String,
        s: Socket,
        pout: DataOutputStream,
        existingIn: DataInputStream? = null,
    ) {
        val din = existingIn ?: DataInputStream(s.getInputStream())
        try {
            while (true) {
                val body = din.readFrame() ?: break
                val r = SlskReader(body)
                val code = r.int()
                when (code) {
                    P_SEARCH_REPLY -> handleSearchReply(body)
                    P_TRANSFER_REQUEST -> handleTransferRequest(r, pout)
                    P_UPLOAD_DENIED, P_UPLOAD_FAILED -> {
                        // Denied carries a reason ("Queued", "Banned", "Too many files", ...).
                        val reason = if (code == P_UPLOAD_DENIED) runCatching { r.str(); r.str() }.getOrNull() else null
                        activeDownload?.takeIf { it.peer == peer || peer == "?" }?.let {
                            lastError = if (code == P_UPLOAD_DENIED) "denied by $peer: ${reason ?: "no reason"}" else "upload failed at $peer"
                            it.events.offer("denied")
                        }
                    }
                    P_PLACE_IN_QUEUE -> { /* filename + place — informational */ }
                    else -> { /* ignore */ }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "peer loop ($peer) ended: ${e.message}")
        } finally {
            runCatching { s.close() }
        }
    }

    /** FileSearchResponse (code 9): payload after the code is zlib-compressed. */
    private fun handleSearchReply(body: ByteArray) {
        val inflated = runCatching { inflate(body.copyOfRange(4, body.size)) }.getOrNull() ?: return
        val r = SlskReader(inflated)
        val user = runCatching { r.str() }.getOrNull() ?: return
        val token = r.int()
        val bucket = searchResults[token] ?: return
        val count = r.int()
        val files = ArrayList<SlskFile>(count.coerceIn(0, 10_000))
        repeat(count.coerceIn(0, 10_000)) {
            r.byte() // code == 1
            val filename = r.str()
            val size = r.long()
            r.str() // extension
            val nAttr = r.int()
            var bitrate = 0; var duration = 0; var sampleRate = 0; var bitDepth = 0
            repeat(nAttr.coerceIn(0, 32)) {
                val type = r.int(); val value = r.int()
                when (type) {
                    0 -> bitrate = value
                    1 -> duration = value
                    4 -> sampleRate = value
                    5 -> bitDepth = value
                }
            }
            files += SlskFile(user, filename, size, bitrate, duration, sampleRate, bitDepth)
        }
        val slotFree = runCatching { r.bool() }.getOrDefault(false)
        val avgSpeed = runCatching { r.int() }.getOrDefault(0)
        val queueLen = runCatching { r.long() }.getOrDefault(0L)
        bucket.addAll(files.map { it.copy(slotsFree = slotFree, avgSpeed = avgSpeed, queueLength = queueLen) })
    }

    /** Peer is ready to upload — accept, so it opens an 'F' connection to us. */
    private fun handleTransferRequest(r: SlskReader, pout: DataOutputStream) {
        val direction = r.int()
        val token = r.int()
        val filename = r.str()
        val size = if (direction == 1) runCatching { r.long() }.getOrDefault(0L) else 0L
        val dl = activeDownload
        if (dl == null || filename != dl.filename) {
            // Reject unknown transfer.
            synchronized(pout) {
                pout.write(SlskWriter().int(token).bool(false).str("Cancelled").frameServer(P_TRANSFER_RESPONSE))
                pout.flush()
            }
            return
        }
        dl.transferToken = token
        if (size > 0) dl.expectedSize = size
        synchronized(pout) {
            pout.write(SlskWriter().int(token).bool(true).frameServer(P_TRANSFER_RESPONSE))
            pout.flush()
        }
    }

    // ── File 'F' connection: raw token + offset + bytes ──────────────────────

    private fun fileConnectionLoop(s: Socket, pierceToken: Int = 0, din: DataInputStream? = null) {
        val dl = activeDownload ?: run { runCatching { s.close() }; return }
        val input = din ?: DataInputStream(s.getInputStream())
        try {
            // FileTransferInit: raw uint32 token identifying the transfer.
            val token = readRawInt(input)
            if (dl.transferToken != 0 && token != dl.transferToken && token != pierceToken) {
                Log.d(TAG, "F-conn token $token != expected ${dl.transferToken}")
            }
            // Tell the peer we start from byte 0: raw uint64 offset (8 zero bytes).
            val fout = DataOutputStream(BufferedOutputStream(s.getOutputStream()))
            fout.write(ByteArray(8))
            fout.flush()

            val outFile = dl.outFile ?: return
            val expected = dl.expectedSize
            var received = 0L
            var lastNotified = 0L
            BufferedOutputStream(outFile.outputStream()).use { fileOut ->
                val buf = ByteArray(64 * 1024)
                while (!dl.done && (expected <= 0 || received < expected)) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (dl.done || n.toLong() > dl.maxBytes - received) throw java.io.IOException("Cache transfer cancelled or limit reached")
                    fileOut.write(buf, 0, n)
                    received += n
                    // Throttle progress callbacks to ~every 256 KB.
                    if (received - lastNotified >= 256 * 1024) {
                        lastNotified = received
                        dl.onProgress?.invoke(received, expected)
                    }
                }
            }
            dl.onProgress?.invoke(received, expected)
            dl.ok = expected > 0 && received >= expected
            if (!dl.ok) lastError = "incomplete transfer ($received/$expected bytes)"
            dl.done = true
            dl.events.offer(if (dl.ok) "done" else "failed")
            Log.d(TAG, "file transfer finished: $received/$expected bytes ok=${dl.ok}")
        } catch (e: Exception) {
            Log.d(TAG, "file connection error: ${e.message}")
            lastError = "transfer broke off: ${e.message}"
            dl.events.offer("failed")
        } finally {
            runCatching { s.close() }
        }
    }

    private fun readRawInt(input: DataInputStream): Int {
        val b = ByteArray(4); input.readFully(b)
        return (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
            ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun send(bytes: ByteArray) {
        val o = out ?: throw IllegalStateException("not connected")
        synchronized(o) { o.write(bytes); o.flush() }
    }

    // A real search reply is a few hundred KB; a peer's "zip bomb" must not OOM the app.
    private const val MAX_INFLATED = 8 * 1024 * 1024

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        try {
            inflater.setInput(data)
            val out = java.io.ByteArrayOutputStream(minOf(data.size * 4, MAX_INFLATED))
            val buf = ByteArray(16 * 1024)
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0) break // needs input or a dictionary: the reply is truncated/odd
                out.write(buf, 0, n)
                if (out.size() > MAX_INFLATED) throw java.io.IOException("search reply too large")
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun ipToString(ip: Int): String =
        "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}.${(ip ushr 8) and 0xFF}.${ip and 0xFF}"

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
