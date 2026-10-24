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
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
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
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * P2H-3A probe only: does Reno16 (ColorOS 16 / Android 16) let a self-managed
 * VoIP call live under Core-Telecom in a phoneCall foreground service, keep
 * recording with the screen off, and route through Telecom endpoints?
 *
 * It does not touch the real call loop (CallScreen, VoiceBridge,
 * CallAudioBridge). Everything it learns goes to logcat (tag ElpisTelecomSpike)
 * and to the in-memory log TelecomSpikeActivity shows.
 */
class TelecomSpikeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val commands = Channel<String>(Channel.UNLIMITED)
    private var callJob: Job? = null
    private var recorder: Thread? = null
    @Volatile private var recording = false
    private var endpoints: List<CallEndpointCompat> = emptyList()
    private var answered = false
    private var startedAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        when (action) {
            ACTION_INCOMING, ACTION_OUTGOING -> startCall(action == ACTION_INCOMING)
            else -> commands.trySend(action)
        }
        return START_NOT_STICKY
    }

    private fun startCall(incoming: Boolean) {
        if (callJob != null) {
            log("a probe call is already running")
            return
        }
        createChannel()
        answered = !incoming
        // phoneCall first: the microphone type is only added once the call is live.
        if (!promote(notification(incoming = incoming), withMicrophone = false)) {
            stopSelf()
            return
        }
        val manager = CallsManager(this)
        try {
            manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE)
            log("registerAppWithTelecom OK")
        } catch (e: Exception) {
            log("registerAppWithTelecom FAILED: $e")
            finish()
            return
        }
        val attributes = CallAttributesCompat(
            "费奥多尔",
            Uri.parse("hayagarden:fyodor"),
            if (incoming) CallAttributesCompat.DIRECTION_INCOMING else CallAttributesCompat.DIRECTION_OUTGOING,
            CallAttributesCompat.CALL_TYPE_AUDIO_CALL,
            CallAttributesCompat.SUPPORTS_SET_INACTIVE,
        )
        callJob = scope.launch {
            try {
                manager.addCall(
                    attributes,
                    onAnswer = { type -> log("telecom onAnswer type=$type (answered by a system surface)"); live() },
                    onDisconnect = { cause -> log("telecom onDisconnect $cause"); commands.trySend(ACTION_ENDED) },
                    onSetActive = { log("telecom onSetActive") },
                    onSetInactive = { log("telecom onSetInactive") },
                ) {
                    log("addCall OK callId=${getCallId()}")
                    launch { currentCallEndpoint.collect { log("currentCallEndpoint ${describe(it)}") } }
                    launch {
                        availableEndpoints.collect {
                            endpoints = it
                            log("availableEndpoints [${it.joinToString { e -> describe(e) }}]")
                        }
                    }
                    launch { isMuted.collect { log("isMuted $it") } }
                    launch {
                        if (!incoming) goLive(setActive(), "setActive")
                        for (command in commands) if (!handle(command)) break
                    }
                }
            } catch (e: Exception) {
                log("addCall FAILED: $e")
            }
            finish()
        }
    }

    /** Returns false once the call is over. */
    private suspend fun CallControlScope.handle(command: String): Boolean {
        when (command) {
            ACTION_ANSWER -> if (!answered) goLive(answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL), "answer")
            ACTION_EARPIECE -> route(CallEndpointCompat.TYPE_EARPIECE)
            ACTION_SPEAKER -> route(CallEndpointCompat.TYPE_SPEAKER)
            ACTION_BLUETOOTH -> route(CallEndpointCompat.TYPE_BLUETOOTH)
            ACTION_HANGUP -> {
                log("disconnect(LOCAL) -> ${disconnect(DisconnectCause(DisconnectCause.LOCAL))}")
                return false
            }
            ACTION_ENDED -> return false
        }
        return true
    }

    private fun goLive(result: CallControlResult, what: String) {
        log("$what -> $result")
        if (result is CallControlResult.Success) live()
    }

    /** The call is connected: ongoing notification, microphone type, recording. */
    private fun live() {
        if (answered && startedAt > 0) return
        answered = true
        startedAt = SystemClock.elapsedRealtime()
        promote(notification(incoming = false), withMicrophone = true)
        startRecording()
    }

    private suspend fun CallControlScope.route(type: Int) {
        val target = endpoints.firstOrNull { it.type == type }
        if (target == null) {
            log("no endpoint of type ${typeName(type)} in [${endpoints.joinToString { describe(it) }}]")
            return
        }
        log("requestEndpointChange ${describe(target)} -> ${requestEndpointChange(target)}")
    }

    /** startForeground with phoneCall (and microphone once live); logs a refusal instead of crashing. */
    private fun promote(notification: Notification, withMicrophone: Boolean): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                if (withMicrophone) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            log("startForeground OK (phoneCall${if (withMicrophone) "|microphone" else ""})")
            true
        } catch (e: Exception) {
            log("startForeground FAILED (phoneCall${if (withMicrophone) "|microphone" else ""}): $e")
            false
        }
    }

    private fun startRecording() {
        if (recording) return
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("AudioRecord skipped: RECORD_AUDIO not granted (open the call page once and allow the mic)")
            return
        }
        recording = true
        recorder = Thread({
            val rate = 16000
            val size = maxOf(AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), 3200)
            val record = try {
                AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            } catch (e: Exception) {
                log("AudioRecord FAILED: $e")
                recording = false
                return@Thread
            }
            val buffer = ShortArray(size / 2)
            var frames = 0L
            var peak = 0
            var silentReads = 0
            var lastReport = SystemClock.elapsedRealtime()
            try {
                record.startRecording()
                log("AudioRecord started state=${record.recordingState}")
                while (recording) {
                    val n = record.read(buffer, 0, buffer.size)
                    if (n < 0) {
                        log("AudioRecord read error $n")
                        break
                    }
                    frames += n
                    var readPeak = 0
                    for (i in 0 until n) readPeak = maxOf(readPeak, kotlin.math.abs(buffer[i].toInt()))
                    if (readPeak == 0) silentReads++
                    peak = maxOf(peak, readPeak)
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReport >= REPORT_MS) {
                        // all-zero reads mean the OS is silencing a backgrounded recorder
                        log("audio +${frames} frames peak=$peak zeroReads=$silentReads")
                        frames = 0; peak = 0; silentReads = 0; lastReport = now
                    }
                }
            } finally {
                record.stop()
                record.release()
                log("AudioRecord released")
            }
        }, "TelecomSpikeRecorder").apply { start() }
    }

    private fun notification(incoming: Boolean): Notification {
        val caller = Person.Builder().setName("费奥多尔").setImportant(true).build()
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, TelecomSpikeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val style = if (incoming) {
            NotificationCompat.CallStyle.forIncomingCall(caller, command(ACTION_HANGUP, 1), command(ACTION_ANSWER, 2))
        } else {
            NotificationCompat.CallStyle.forOngoingCall(caller, command(ACTION_HANGUP, 1))
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("费奥多尔")
            .setContentText(if (incoming) "Telecom 探针 · 来电" else "Telecom 探针 · 通话中")
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
        PendingIntent.getService(this, code, Intent(this, TelecomSpikeService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Telecom 探针", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun finish() {
        recording = false
        recorder?.join(1000)
        recorder = null
        if (startedAt > 0) log("call lasted ${(SystemClock.elapsedRealtime() - startedAt) / 1000}s")
        callJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        log("probe ended")
    }

    override fun onDestroy() {
        recording = false
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        const val TAG = "ElpisTelecomSpike"
        const val CHANNEL_ID = "fyodor_telecom_spike"
        const val NOTIFICATION_ID = 0xCA12
        const val ACTION_INCOMING = "elpis.telecom_spike.INCOMING"
        const val ACTION_OUTGOING = "elpis.telecom_spike.OUTGOING"
        const val ACTION_ANSWER = "elpis.telecom_spike.ANSWER"
        const val ACTION_EARPIECE = "elpis.telecom_spike.EARPIECE"
        const val ACTION_SPEAKER = "elpis.telecom_spike.SPEAKER"
        const val ACTION_BLUETOOTH = "elpis.telecom_spike.BLUETOOTH"
        const val ACTION_HANGUP = "elpis.telecom_spike.HANGUP"
        private const val ACTION_ENDED = "elpis.telecom_spike.ENDED"
        private const val REPORT_MS = 5000L
        private const val MAX_LINES = 200

        private val lines = ArrayDeque<String>()
        private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

        fun log(message: String) {
            Log.i(TAG, message)
            synchronized(lines) {
                lines.addLast("${clock.format(Date())} $message")
                while (lines.size > MAX_LINES) lines.removeFirst()
            }
        }

        fun snapshot(): String = synchronized(lines) { lines.joinToString("\n") }

        fun send(context: Context, action: String) {
            val intent = Intent(context, TelecomSpikeService::class.java).setAction(action)
            if (action == ACTION_INCOMING || action == ACTION_OUTGOING) {
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

        fun describe(endpoint: CallEndpointCompat): String = "${typeName(endpoint.type)}:${endpoint.name}"
    }
}
