package com.movtery.zalithlauncher.feature.screenrec

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.movtery.zalithlauncher.R

class RecorderMenuBinder(
    private val start: TextView,
    private val pause: TextView,
    private val mic: TextView,
    private val onToggle: Runnable
) {
    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (ScreenRecorder.state != RecState.IDLE) handler.postDelayed(this, 1000)
        }
    }
    private val listener = ScreenRecorder.StateListener { state ->
        handler.removeCallbacks(ticker)
        refresh()
        if (state != RecState.IDLE) handler.postDelayed(ticker, 1000)
    }

    fun attach() {
        start.setOnClickListener { onToggle.run() }
        pause.setOnClickListener {
            if (ScreenRecorder.state == RecState.PAUSED) ScreenRecorder.resume() else ScreenRecorder.pause()
        }
        mic.setOnClickListener {
            ScreenRecorder.toggleMic()
            refresh()
        }
        ScreenRecorder.addListener(listener)
    }

    fun detach() {
        ScreenRecorder.removeListener(listener)
        handler.removeCallbacks(ticker)
    }

    private fun refresh() {
        val state = ScreenRecorder.state
        val active = state == RecState.RECORDING || state == RecState.PAUSED
        val time = RecordingStore.formatDuration(ScreenRecorder.elapsedMs())

        start.isEnabled = state != RecState.SAVING
        start.text = when (state) {
            RecState.IDLE -> start.context.getString(R.string.recorder_start)
            RecState.SAVING -> start.context.getString(R.string.recorder_saving)
            else -> start.context.getString(R.string.recorder_stop_with_time, time)
        }

        pause.visibility = if (active) View.VISIBLE else View.GONE
        pause.setText(if (state == RecState.PAUSED) R.string.recorder_resume else R.string.recorder_pause)

        val micVisible = active && ScreenRecorder.canUseMic()
        mic.visibility = if (micVisible) View.VISIBLE else View.GONE
        mic.setText(if (ScreenRecorder.isMicEnabled()) R.string.recorder_mic_on else R.string.recorder_mic_off)
    }
}
