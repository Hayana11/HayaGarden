package xyz.lovestyle.home.canary

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * P2H-3C acceptance screen for NativeCallEngine, separate from CallScreen (the
 * page keeps the WebView runtime until 3E). No launcher entry; open it with
 *   adb shell am start -n xyz.lovestyle.home.canary/.NativeCallProbeActivity
 * Permissions are asked only from the button that needs them, never at startup:
 * the microphone before a call starts, Nearby devices for the Bluetooth endpoint.
 */
class NativeCallProbeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var summaryView: TextView
    private lateinit var logView: TextView
    private lateinit var languageView: EditText
    private lateinit var legacyView: CheckBox
    private var pendingStart: String? = null
    private val refresh = object : Runnable {
        override fun run() {
            summaryView.text = NativeCallEngine.summary()
            logView.text = NativeCallEngine.snapshot()
            main.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 96, 32, 32)
        }
        summaryView = TextView(this).apply { typeface = Typeface.MONOSPACE }
        root.addView(summaryView)
        languageView = EditText(this).apply {
            hint = "语言代码：空 = 中文，en，ja"
            setSingleLine()
        }
        root.addView(languageView)
        // P2H-3D A/B: the same call through Core-Telecom's legacy ConnectionService path
        legacyView = CheckBox(this).apply { text = "A/B：Telecom 走 legacy ConnectionService" }
        root.addView(legacyView)
        val buttons = listOf(
            "拨给费佳" to NativeCallEngine.ACTION_DIAL,
            "测试来电" to NativeCallEngine.ACTION_TEST_RING,
            "接听" to NativeCallEngine.ACTION_ANSWER,
            "听筒" to NativeCallEngine.ACTION_EARPIECE,
            "扬声器" to NativeCallEngine.ACTION_SPEAKER,
            "蓝牙" to NativeCallEngine.ACTION_BLUETOOTH,
            "挂断" to NativeCallEngine.ACTION_HANGUP,
        )
        for ((label, action) in buttons) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { onAction(action) }
            })
        }
        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        root.addView(ScrollView(this).apply { addView(logView) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        NativeCallEngine.log("probe screen opened")
    }

    private fun onAction(action: String) {
        val starts = action == NativeCallEngine.ACTION_DIAL || action == NativeCallEngine.ACTION_TEST_RING
        if (starts && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingStart = action
            NativeCallEngine.log("microphone needed for the call: requesting RECORD_AUDIO")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
            return
        }
        if (action == NativeCallEngine.ACTION_BLUETOOTH && needsNearbyDevices()) {
            NativeCallEngine.log("Bluetooth wanted: requesting Nearby devices (BLUETOOTH_CONNECT)")
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), REQUEST_NEARBY_DEVICES)
            return
        }
        send(action)
    }

    private fun send(action: String) {
        val language = languageView.text.toString().trim().ifEmpty { null }
        NativeCallEngine.send(this, action, language, legacyTelecom = legacyView.isChecked)
    }

    private fun needsNearbyDevices(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        when (requestCode) {
            REQUEST_MICROPHONE -> {
                val action = pendingStart ?: return
                pendingStart = null
                if (granted) {
                    send(action)
                } else {
                    NativeCallEngine.log("microphone denied: no call started")
                }
            }
            REQUEST_NEARBY_DEVICES -> if (granted) {
                NativeCallEngine.log("Nearby devices granted")
                send(NativeCallEngine.ACTION_BLUETOOTH)
            } else {
                // The call goes on: the route simply stays on earpiece/speaker.
                NativeCallEngine.log("Nearby devices denied: staying on the current earpiece/speaker endpoint")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }

    private companion object {
        const val REQUEST_MICROPHONE = 2011
        const val REQUEST_NEARBY_DEVICES = 2012
    }
}
