package xyz.lovestyle.home.canary

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * P2H-3A probe screen. No launcher entry; open it with
 *   adb shell am start -n xyz.lovestyle.home.canary/.TelecomSpikeActivity
 * Removed again once the probe has answered its questions.
 */
class TelecomSpikeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var logView: TextView
    private val refresh = object : Runnable {
        override fun run() {
            logView.text = TelecomSpikeService.snapshot()
            main.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 96, 32, 32)
        }
        val buttons = listOf(
            "来电（响铃，再接听）" to TelecomSpikeService.ACTION_INCOMING,
            "直接通话" to TelecomSpikeService.ACTION_OUTGOING,
            "听筒" to TelecomSpikeService.ACTION_EARPIECE,
            "扬声器" to TelecomSpikeService.ACTION_SPEAKER,
            "蓝牙" to TelecomSpikeService.ACTION_BLUETOOTH,
            "挂断" to TelecomSpikeService.ACTION_HANGUP,
        )
        for ((label, action) in buttons) {
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { TelecomSpikeService.send(this@TelecomSpikeActivity, action) }
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
        TelecomSpikeService.log("probe screen opened")
    }

    override fun onResume() {
        super.onResume()
        main.post(refresh)
    }

    override fun onPause() {
        main.removeCallbacks(refresh)
        super.onPause()
    }
}
