package xyz.lovestyle.home.canary

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.telecom.DisconnectCause
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.telecom.CallAttributesCompat
import androidx.core.telecom.CallControlResult
import androidx.core.telecom.CallControlScope
import androidx.core.telecom.CallEndpointCompat
import androidx.core.telecom.CallsManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * P2H-3C native call engine: one HayaGarden call, run entirely on the native side.
 *
 * - Core-Telecom owns the call lifecycle and the audio endpoint (a self-managed
 *   call in a phoneCall foreground service; the microphone FGS type is not used).
 * - Capture (VoiceCapture: AudioRecord + AEC + Silero) and playback (ExoPlayer,
 *   USAGE_VOICE_COMMUNICATION) are native. The engine never sets the audio mode or
 *   a communication device: routing is requestEndpointChange only.
 * - It speaks the same /api/call protocol as the WebView runtime, on the same
 *   call id (NativeCallProtocol, NativeCallApi): ring/answer/hangup, interrupt at
 *   speech_start, hear/discard with seq and prev, the reply stream, /audio/<seq>.
 *
 * Not wired to CallScreen: the 3E capability switch decides when the page uses
 * it. Until then NativeCallProbeActivity drives it for Canary acceptance.
 *
 * Lessons from the 3A probe, kept as rules:
 * 1. Telecom state collectors (watchers) end with the call; addCall returns only
 *    once every child of its scope has finished.
 * 2. Local media cleanup never waits on the Telecom scope: stopMedia() runs on
 *    hangup, on remote end and on onDisconnect, before or without the scope exit.
 * 3. The main thread never joins the recording thread: VoiceCapture.close()
 *    (which joins its worker) runs only on the cleanup executor.
 */
class NativeCallEngine : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val main = Handler(Looper.getMainLooper())
    private val commands = Channel<String>(Channel.UNLIMITED)
    private val api = NativeCallApi()
    /** Control requests (ring/answer/hangup/interrupt/discard), in order. */
    private val control: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "elpis-call-control") }
    /** Her utterances to Scribe; separate so a slow upload never delays a barge-in. */
    private val uploads: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "elpis-call-upload") }
    /** Closing the recorder joins its thread: done here, never on the main thread. */
    private val cleanup: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "elpis-call-cleanup") }

    private var callJob: Job? = null
    private var watchers: Job? = null
    private var endpoints: List<CallEndpointCompat> = emptyList()
    private var callId: String? = null
    private var incoming = false
    private var answered = false
    private var systemAnswered = false
    private var hangupRequestedAt = 0L
    private var scopeExited = false
    private var finished = false
    private var media: Media? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        when (action) {
            ACTION_DIAL, ACTION_TEST_RING -> begin(action == ACTION_TEST_RING, intent.getStringExtra(EXTRA_LANGUAGE))
            else -> {
                if (action == ACTION_HANGUP) log("hangup command received (call running=${callJob != null})")
                commands.trySend(action)
            }
        }
        return START_NOT_STICKY
    }

    /** Ring through the server first, so Telecom only ever carries a call that exists there. */
    private fun begin(asIncoming: Boolean, language: String?) {
        if (callJob != null || callId != null) {
            log("a call is already running")
            return
        }
        createChannel()
        incoming = asIncoming
        answered = false
        systemAnswered = false
        hangupRequestedAt = 0L
        scopeExited = false
        finished = false
        if (!promote(notification(incoming = asIncoming))) {
            stopSelf()
            return
        }
        publish(state = if (asIncoming) "ringing" else "dialing")
        control.execute {
            try {
                val ringing = api.ring(language)
                val id = ringing.getString("id")
                log("server ring OK callId=$id language=${ringing.optString("language", "default")}")
                // She calls him: ring and pick up at once, as the page's dial does.
                if (!asIncoming) api.answer(id).also { log("server answer OK state=${it.optString("state")}") }
                main.post { addCall(id) }
            } catch (e: Exception) {
                log("server ring FAILED: $e")
                main.post { finish("ring failed") }
            }
        }
    }

    private fun addCall(id: String) {
        if (finished) return
        callId = id
        publish(callId = id)
        val manager = CallsManager(this)
        try {
            manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
        } catch (e: Exception) {
            log("registerAppWithTelecom FAILED: $e")
            endOnServer("telecom registration failed")
            finish("registerAppWithTelecom failed")
            return
        }
        val attributes = CallAttributesCompat(
            "费奥多尔",
            Uri.parse("hayagarden:call:$id"),
            if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )
        callJob = scope.launch {
            try {
                manager.addCall(
                    attributes,
                    onAnswer = { log("telecom onAnswer (system surface)"); commands.trySend(ACTION_SYSTEM_ANSWERED) },
                    onDisconnect = { cause ->
                        log("telecom onDisconnect $cause")
                        stopMedia("telecom onDisconnect")
                        stopWatchers("telecom onDisconnect")
                        if (hangupRequestedAt == 0L) endOnServer("telecom onDisconnect")
                        commands.trySend(ACTION_ENDED)
                    },
                    onSetActive = { log("telecom onSetActive") },
                    onSetInactive = { log("telecom onSetInactive") },
                ) {
                    log("addCall OK telecomId=${getCallId()}")
                    watchers = launch {
                        launch { currentCallEndpoint.collect { publish(route = describe(it)) } }
                        launch {
                            availableEndpoints.collect {
                                endpoints = it
                                log("availableEndpoints [${it.joinToString { e -> describe(e) }}]")
                            }
                        }
                        launch { isMuted.collect { muted -> media?.setMuted(muted); publish(muted = muted) } }
                    }
                    launch {
                        if (!incoming) goLive(setActive(), "setActive")
                        for (command in commands) if (!handle(command)) break
                    }
                }
            } catch (e: CancellationException) {
                log("call scope cancelled" + sinceHangup())
                throw e
            } catch (e: Exception) {
                log("addCall FAILED: $e")
                stopMedia("addCall failed")
                endOnServer("addCall failed")
            }
            scopeExited = true
            log("CallControlScope exited" + sinceHangup())
            finish("call scope exited")
        }
    }

    /** Returns false once the call is over. */
    private suspend fun CallControlScope.handle(command: String): Boolean {
        when (command) {
            ACTION_ANSWER -> if (incoming && !answered) answerOnServer()
            // Telecom already answered it (a system surface): only the server is left to tell.
            ACTION_SYSTEM_ANSWERED -> if (incoming && !answered) { systemAnswered = true; answerOnServer() }
            ACTION_SERVER_ANSWERED -> if (!answered) {
                if (systemAnswered) goLive(CallControlResult.Success(), "answer (system)")
                else goLive(answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL), "answer")
            }
            ACTION_EARPIECE -> route(CallEndpointCompat.TYPE_EARPIECE)
            ACTION_SPEAKER -> route(CallEndpointCompat.TYPE_SPEAKER)
            ACTION_BLUETOOTH -> route(CallEndpointCompat.TYPE_BLUETOOTH)
            ACTION_HANGUP -> return disconnectAs(if (answered) DisconnectCause.LOCAL else DisconnectCause.REJECTED, local = true)
            ACTION_REMOTE_ENDED -> return disconnectAs(DisconnectCause.REMOTE, local = false)
            ACTION_ENDED -> return false
        }
        return true
    }

    private fun answerOnServer() {
        val id = callId ?: return
        control.execute {
            try {
                api.answer(id)
                log("server answer OK")
                commands.trySend(ACTION_SERVER_ANSWERED)
            } catch (e: Exception) {
                log("server answer FAILED: $e")
                commands.trySend(ACTION_REMOTE_ENDED)
            }
        }
    }

    /** Local media stops first; the server and Telecom are told after, and nothing waits on the scope. */
    private suspend fun CallControlScope.disconnectAs(cause: Int, local: Boolean): Boolean {
        if (hangupRequestedAt > 0) {
            log("disconnect already requested" + sinceHangup())
            return true
        }
        hangupRequestedAt = SystemClock.elapsedRealtime()
        stopMedia(if (local) "hangup" else "server ended the call")
        if (local) endOnServer("hangup")
        log("disconnect requested cause=${causeName(cause)}")
        armTeardownWatchdog()
        val result = disconnect(DisconnectCause(cause))
        log("disconnect result=$result elapsedMs=${SystemClock.elapsedRealtime() - hangupRequestedAt}")
        if (result is CallControlResult.Success) stopWatchers("disconnect succeeded")
        return false
    }

    /** Ringing in: decline; connected on the server (answered, or her dial): hang up. */
    private fun endOnServer(reason: String) {
        val id = callId ?: return
        val wasAnswered = answered || !incoming
        control.execute {
            try {
                if (wasAnswered) api.hangup(id) else api.decline(id)
                log("server ${if (wasAnswered) "hangup" else "decline"} OK ($reason)")
            } catch (e: Exception) {
                log("server end FAILED ($reason): $e")
            }
        }
    }

    private fun armTeardownWatchdog() {
        scope.launch {
            delay(TEARDOWN_TIMEOUT_MS)
            if (scopeExited || finished) return@launch
            log("teardown watchdog: call scope still open" + sinceHangup() + "; cancelling it")
            stopMedia("teardown watchdog")
            callJob?.cancel()
            finish("teardown watchdog")
        }
    }

    private fun goLive(result: CallControlResult, what: String) {
        log("$what -> $result")
        if (result !is CallControlResult.Success) {
            commands.trySend(ACTION_HANGUP)
            return
        }
        if (answered) return
        answered = true
        promote(notification(incoming = false))
        publish(state = "active")
        val id = callId ?: return
        val audio = getSystemService(AudioManager::class.java)
        log("live: audio mode=${audio?.mode} (read only; Telecom owns routing)")
        media = Media(id).also { it.start() }
    }

    private suspend fun CallControlScope.route(type: Int) {
        if (type == CallEndpointCompat.TYPE_BLUETOOTH && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            log("Bluetooth skipped: Nearby devices not granted; the call stays on earpiece/speaker")
            return
        }
        val target = endpoints.firstOrNull { it.type == type }
        if (target == null) {
            log("no endpoint of type ${typeName(type)} in [${endpoints.joinToString { describe(it) }}]")
            return
        }
        log("requestEndpointChange ${describe(target)} -> ${requestEndpointChange(target)}")
    }

    /** Main thread only. Idempotent: hangup, remote end, onDisconnect and the watchdog may all get here. */
    private fun stopMedia(reason: String) {
        val current = media ?: return
        media = null
        current.stop(reason)
    }

    private fun stopWatchers(reason: String) {
        val job = watchers ?: return
        watchers = null
        job.cancel()
        log("state collectors cancelled ($reason)" + sinceHangup())
    }

    private fun sinceHangup(): String =
        if (hangupRequestedAt > 0) " ${SystemClock.elapsedRealtime() - hangupRequestedAt}ms after the disconnect request" else ""

    /**
     * One live call's media: capture → interrupt/hear/discard, reply stream → his
     * play queue. Capture, stream and uploads run on their own threads; the player
     * on the main thread.
     */
    private inner class Media(private val id: String) : VoiceCapture.Listener {
        @Volatile private var live = true
        private val chain = NativeCallProtocol.UtteranceChain()
        private val lines = NativeCallProtocol.LineQueue()
        private val queue = ArrayDeque<Int>()
        private var playing = 0
        private var nextSeq = 0
        @Volatile private var currentSeq = 0
        private var capture: VoiceCapture? = null
        private var player: ExoPlayer? = null
        private var stream: Thread? = null
        @Volatile private var connection: HttpURLConnection? = null
        private var muted = false

        fun start() {
            stream = Thread(::readStream, "elpis-call-stream").apply { start() }
            startCapture()
        }

        private fun startCapture() {
            if (!live || muted || capture != null) return
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                log("capture skipped: RECORD_AUDIO not granted (allow the mic in the probe first)")
                return
            }
            capture = VoiceCapture(this@NativeCallEngine, this).also { it.start() }
        }

        /** Telecom mute: the recorder is released, not just ignored. */
        fun setMuted(on: Boolean) {
            if (!live || on == muted) return
            muted = on
            if (on) closeCapture() else startCapture()
        }

        private fun closeCapture() {
            val current = capture ?: return
            capture = null
            cleanup.execute {
                current.close()
                log("capture closed")
            }
        }

        /** Main thread. Nothing here blocks: the recorder and the stream are closed on other threads. */
        fun stop(reason: String) {
            if (!live) return
            live = false
            log("media stop ($reason)")
            queue.clear()
            playing = 0
            player?.let {
                it.stop()
                it.release()
            }
            player = null
            closeCapture()
            val open = connection
            stream?.interrupt()
            stream = null
            cleanup.execute { open?.disconnect() }
        }

        // ── capture (VoiceCapture thread) ─────────────────────────
        override fun onReady(aecAvailable: Boolean, aecEnabled: Boolean) {
            log("capture ready aec=$aecAvailable/$aecEnabled")
        }

        override fun onSpeechStart() {
            if (!live) return
            val seq = ++nextSeq
            currentSeq = seq
            val prev = chain.start(seq)
            main.post { stopSpeaking() }
            control.execute { quietly("interrupt $seq") { api.interrupt(id, seq, prev) } }
        }

        override fun onUtterance(samples: ShortArray) {
            val seq = currentSeq
            if (!live || seq == 0) return
            val prev = chain.prev(seq)
            val wav = WavEncoder.encode(samples, VoiceSegmenter.SAMPLE_RATE)
            uploads.execute {
                try {
                    val result = api.hear(id, seq, prev, wav)
                    val line = result.optJSONObject("line")
                    if (line != null && !result.optBoolean("stale")) accept(line)
                    log("heard $seq: ${result.optString("text").take(40)}")
                } catch (e: Exception) {
                    log("hear $seq FAILED: $e")  // recoverable: capture stays on for the next sentence
                }
            }
        }

        override fun onDiscarded(reason: String) {
            val seq = currentSeq
            if (!live || seq == 0) return
            val prev = chain.prev(seq)
            control.execute { quietly("discard $seq") { api.discard(id, seq, prev) } }
        }

        override fun onError(code: String) {
            log("capture error $code")
        }

        // ── reply stream (stream thread) ──────────────────────────
        private fun readStream() {
            while (live) {
                try {
                    val open = api.openStream(id, lines.lastSeq())
                    connection = open
                    val parser = NativeCallProtocol.SseParser()
                    var state: String? = null
                    BufferedReader(InputStreamReader(open.inputStream, Charsets.UTF_8)).use { reader ->
                        while (live) {
                            val raw = reader.readLine() ?: break
                            val event = parser.feed(raw) ?: continue
                            when (event.name) {
                                "line" -> accept(JSONObject(event.data))
                                "state" -> state = JSONObject(event.data).optString("state")
                            }
                        }
                    }
                    open.disconnect()
                    connection = null
                    if (!live) return
                    if (state != null && state != "active") {
                        log("server says the call is $state")
                        commands.trySend(ACTION_REMOTE_ENDED)
                        return
                    }
                } catch (e: Exception) {
                    if (!live) return
                    log("stream error: $e; retrying")
                    try {
                        Thread.sleep(STREAM_RETRY_MS)
                    } catch (interrupted: InterruptedException) {
                        return
                    }
                }
            }
        }

        private fun accept(line: JSONObject) {
            val seq = line.optInt("seq")
            val speaker = line.optString("speaker")
            if (!lines.accept(seq, speaker)) return
            log("line $seq $speaker: ${line.optString("text").take(60)}")
            main.post {
                if (!live) return@post
                queue.addLast(seq)
                playNext()
            }
        }

        // ── his voice (main thread) ───────────────────────────────
        private fun playNext() {
            if (!live || playing != 0) return
            val seq = queue.pollFirst() ?: return
            val url = NativeCallProtocol.audioUrl(id, seq) ?: return playNext()
            playing = seq
            val current = player()
            val factory = httpFactory
            val cookie = api.cookie()
            factory.setDefaultRequestProperties(if (cookie.isNullOrEmpty()) emptyMap() else mapOf("Cookie" to cookie))
            current.setMediaItem(MediaItem.fromUri(url))
            current.prepare()
            current.setPlayWhenReady(true)
        }

        private fun stopSpeaking() {
            queue.clear()
            playing = 0
            player?.let {
                it.stop()
                it.clearMediaItems()
            }
        }

        private lateinit var httpFactory: DefaultHttpDataSource.Factory

        private fun player(): ExoPlayer {
            player?.let { return it }
            httpFactory = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(false)
                .setConnectTimeoutMs(10_000)
                .setReadTimeoutMs(20_000)
            val attributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_VOICE_COMMUNICATION)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .build()
            val created = ExoPlayer.Builder(this@NativeCallEngine)
                .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
                .setAudioAttributes(attributes, /* handleAudioFocus= */ false)
                .build()
            created.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED && playing != 0) done(false)
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (playing != 0) done(true, error.errorCodeName)
                }
            })
            player = created
            return created
        }

        private fun done(failed: Boolean, code: String? = null) {
            if (failed) log("line $playing not voiced: $code")
            playing = 0
            playNext()
        }

        private inline fun quietly(what: String, block: () -> Unit) {
            try {
                block()
            } catch (e: Exception) {
                log("$what FAILED: $e")
            }
        }
    }

    /** startForeground as a phoneCall service; logs a refusal instead of crashing. */
    private fun promote(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            log("startForeground FAILED (phoneCall): $e")
            false
        }
    }

    private fun notification(incoming: Boolean): Notification {
        val caller = Person.Builder().setName("费奥多尔").setImportant(true).build()
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, NativeCallProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val style = if (incoming) {
            NotificationCompat.CallStyle.forIncomingCall(caller, command(ACTION_HANGUP, 1), command(ACTION_ANSWER, 2))
        } else {
            NotificationCompat.CallStyle.forOngoingCall(caller, command(ACTION_HANGUP, 1))
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("费奥多尔")
            .setContentText(if (incoming) "原生通话 · 来电" else "原生通话 · 通话中")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setContentIntent(open)
            .setStyle(style)
        if (incoming) builder.setFullScreenIntent(open, true)
        if (!incoming) builder.setUsesChronometer(true).setWhen(System.currentTimeMillis())
        return builder.build()
    }

    private fun command(action: String, code: Int): PendingIntent =
        PendingIntent.getService(this, code, Intent(this, NativeCallEngine::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "原生通话（测试）", NotificationManager.IMPORTANCE_HIGH))
    }

    /** Idempotent: the scope exit, a failed ring and the watchdog may all get here. */
    private fun finish(reason: String) {
        if (finished) return
        finished = true
        log("finish($reason)")
        stopMedia("finish")
        callJob = null
        callId = null
        publish(state = "ended")
        stopForeground(STOP_FOREGROUND_REMOVE)
        log("stopForeground done")
        stopSelf()
    }

    override fun onDestroy() {
        stopMedia("service destroyed")
        scope.cancel()
        control.shutdown()
        uploads.shutdown()
        cleanup.shutdown()  // queued closes still run
        super.onDestroy()
    }

    companion object {
        const val TAG = "ElpisCallEngine"
        const val CHANNEL_ID = "fyodor_native_call"
        const val NOTIFICATION_ID = 0xCA13
        const val EXTRA_LANGUAGE = "language"
        const val ACTION_DIAL = "elpis.call_engine.DIAL"
        const val ACTION_TEST_RING = "elpis.call_engine.TEST_RING"
        const val ACTION_ANSWER = "elpis.call_engine.ANSWER"
        const val ACTION_HANGUP = "elpis.call_engine.HANGUP"
        const val ACTION_EARPIECE = "elpis.call_engine.EARPIECE"
        const val ACTION_SPEAKER = "elpis.call_engine.SPEAKER"
        const val ACTION_BLUETOOTH = "elpis.call_engine.BLUETOOTH"
        private const val ACTION_SYSTEM_ANSWERED = "elpis.call_engine.SYSTEM_ANSWERED"
        private const val ACTION_SERVER_ANSWERED = "elpis.call_engine.SERVER_ANSWERED"
        private const val ACTION_REMOTE_ENDED = "elpis.call_engine.REMOTE_ENDED"
        private const val ACTION_ENDED = "elpis.call_engine.ENDED"
        private const val TEARDOWN_TIMEOUT_MS = 8000L
        private const val STREAM_RETRY_MS = 2000L
        private const val MAX_LINES = 300

        private val logLines = ArrayDeque<String>()
        private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)
        @Volatile private var status = "idle"
        @Volatile private var currentCall: String? = null
        @Volatile private var currentRoute = "-"
        @Volatile private var isMuted = false

        fun log(message: String) {
            Log.i(TAG, message)
            synchronized(logLines) {
                logLines.addLast("${clock.format(Date())} $message")
                while (logLines.size > MAX_LINES) logLines.removeFirst()
            }
        }

        private fun publish(state: String? = null, callId: String? = null, route: String? = null, muted: Boolean? = null) {
            state?.let { status = it; log("state $it") }
            callId?.let { currentCall = it }
            route?.let { currentRoute = it; log("endpoint $it") }
            muted?.let { isMuted = it }
        }

        fun summary(): String = "state=$status call=${currentCall ?: "-"} endpoint=$currentRoute muted=$isMuted"

        fun snapshot(): String = synchronized(logLines) { logLines.joinToString("\n") }

        fun send(context: Context, action: String, language: String? = null) {
            val intent = Intent(context, NativeCallEngine::class.java).setAction(action)
            if (language != null) intent.putExtra(EXTRA_LANGUAGE, language)
            if (action == ACTION_DIAL || action == ACTION_TEST_RING) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun typeName(type: Int): String = when (type) {
            CallEndpointCompat.TYPE_EARPIECE -> "earpiece"
            CallEndpointCompat.TYPE_SPEAKER -> "speaker"
            CallEndpointCompat.TYPE_BLUETOOTH -> "bluetooth"
            CallEndpointCompat.TYPE_WIRED_HEADSET -> "wired"
            CallEndpointCompat.TYPE_STREAMING -> "streaming"
            else -> "unknown($type)"
        }

        fun causeName(code: Int): String = when (code) {
            DisconnectCause.LOCAL -> "LOCAL"
            DisconnectCause.REJECTED -> "REJECTED"
            DisconnectCause.REMOTE -> "REMOTE"
            else -> code.toString()
        }

        fun describe(endpoint: CallEndpointCompat): String = "${typeName(endpoint.type)}:${endpoint.name}"
    }
}
