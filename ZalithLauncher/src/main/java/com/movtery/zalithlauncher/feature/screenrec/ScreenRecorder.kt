package com.movtery.zalithlauncher.feature.screenrec

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList

object ScreenRecorder {
    private const val TAG = "ScreenRecorder"

    fun interface StateListener {
        fun onStateChanged(state: RecState)
    }

    private val listeners = CopyOnWriteArrayList<StateListener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var state: RecState = RecState.IDLE
        private set

    private var session: Session? = null

    @JvmStatic
    fun addListener(listener: StateListener) {
        listeners.addIfAbsent(listener)
        listener.onStateChanged(state)
    }

    @JvmStatic
    fun removeListener(listener: StateListener) {
        listeners.remove(listener)
    }

    private fun publish(newState: RecState) {
        state = newState
        mainHandler.post { listeners.forEach { it.onStateChanged(newState) } }
    }

    @JvmStatic
    fun elapsedMs(): Long = session?.elapsedMs() ?: 0L

    @JvmStatic
    fun isMicEnabled(): Boolean = session?.micEnabled == true

    @JvmStatic
    fun canUseMic(): Boolean = session?.micAvailable == true

    @Synchronized
    fun start(context: Context, projection: MediaProjection): Boolean {
        if (state != RecState.IDLE) return false
        val created = Session(context.applicationContext, projection)
        return try {
            created.begin()
            session = created
            publish(RecState.RECORDING)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Unable to start recording", e)
            created.abort()
            session = null
            false
        }
    }

    @Synchronized
    fun pause() {
        val current = session ?: return
        if (state != RecState.RECORDING) return
        current.pause()
        publish(RecState.PAUSED)
    }

    @Synchronized
    fun resume() {
        val current = session ?: return
        if (state != RecState.PAUSED) return
        current.resume()
        publish(RecState.RECORDING)
    }

    @Synchronized
    fun toggleMic(): Boolean {
        val current = session ?: return false
        return current.toggleMic()
    }

    @Synchronized
    fun stop(onFinished: (File?) -> Unit) {
        val current = session
        if (current == null || state == RecState.IDLE || state == RecState.SAVING) {
            onFinished(null)
            return
        }
        publish(RecState.SAVING)
        Thread({
            val file = current.finish()
            synchronized(this) { session = null }
            publish(RecState.IDLE)
            mainHandler.post { onFinished(file) }
        }, "ScreenRecorder-finish").start()
    }

    private class PendingSample(val track: Int, val data: ByteBuffer, val info: MediaCodec.BufferInfo)

    private class Session(private val context: Context, private val projection: MediaProjection) {
        private val rate = 44100
        private val file: File = RecordingStore.newFile()
        private val lock = Any()
        private val pending = ArrayList<PendingSample>()

        private var videoCodec: MediaCodec? = null
        private var audioCodec: MediaCodec? = null
        private var muxer: MediaMuxer? = null
        private var display: VirtualDisplay? = null
        private var inputSurface: Surface? = null
        private var playbackRecord: AudioRecord? = null
        private var micRecord: AudioRecord? = null
        private var videoThread: Thread? = null
        private var audioThread: Thread? = null

        private var videoTrack = -1
        private var audioTrack = -1
        private var muxerStarted = false
        private var audioEnabled = false

        @Volatile private var running = false
        @Volatile private var paused = false
        @Volatile var micEnabled = false
        @Volatile var micAvailable = false

        @Volatile private var needSync = false
        private var firstRawPts = -1L
        private var lastRawPts = 0L
        private var shiftUs = 0L
        private var resumePending = false

        private var audioFrames = 0L
        private var startedAt = 0L
        private var pausedAt = 0L
        private var pausedTotal = 0L

        fun elapsedMs(): Long {
            if (startedAt == 0L) return 0L
            val now = if (paused) pausedAt else SystemClock.elapsedRealtime()
            return (now - startedAt - pausedTotal).coerceAtLeast(0L)
        }

        fun begin() {
            val metrics = realMetrics()
            val scale = minOf(1f, 1920f / maxOf(metrics.widthPixels, metrics.heightPixels))
            val width = even((metrics.widthPixels * scale).toInt())
            val height = even((metrics.heightPixels * scale).toInt())

            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, (width * height * 30 * 0.09).toInt().coerceIn(2_000_000, 16_000_000))
                setInteger(MediaFormat.KEY_FRAME_RATE, 30)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val vCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            vCodec.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = vCodec.createInputSurface()
            videoCodec = vCodec

            setupAudio()

            projection.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    if (state == RecState.RECORDING || state == RecState.PAUSED) stop { }
                }
            }, mainHandler)

            display = projection.createVirtualDisplay(
                "zalith-plus-recorder", width, height, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                inputSurface, null, null
            )

            vCodec.start()
            audioCodec?.start()
            running = true
            startedAt = SystemClock.elapsedRealtime()

            videoThread = Thread({ drainVideo() }, "ScreenRecorder-video").also { it.start() }
            if (audioEnabled) {
                playbackRecord?.startRecording()
                audioThread = Thread({ pumpAudio() }, "ScreenRecorder-audio").also { it.start() }
            }
        }

        private fun even(value: Int) = (value.coerceAtLeast(2) / 2) * 2

        @Suppress("DEPRECATION")
        private fun realMetrics(): DisplayMetrics {
            val metrics = DisplayMetrics()
            val manager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            manager.defaultDisplay.getRealMetrics(metrics)
            return metrics
        }

        private fun setupAudio() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val granted = context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) return
            try {
                val capture = AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .build()
                val format = AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                    .build()
                val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
                val record = AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(capture)
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(minBuffer.coerceAtLeast(8192) * 2)
                    .build()
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    return
                }
                playbackRecord = record

                val aFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
                }
                val aCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                aCodec.configure(aFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioCodec = aCodec
                audioEnabled = true
                setupMic()
            } catch (e: Throwable) {
                Log.w(TAG, "Game audio capture unavailable", e)
                playbackRecord?.release()
                playbackRecord = null
                audioEnabled = false
            }
        }

        private fun setupMic() {
            try {
                val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val mic = AudioRecord(
                    MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, minBuffer.coerceAtLeast(4096) * 2
                )
                if (mic.state == AudioRecord.STATE_INITIALIZED) {
                    micRecord = mic
                    micAvailable = true
                } else {
                    mic.release()
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Microphone unavailable", e)
            }
        }

        fun toggleMic(): Boolean {
            val mic = micRecord ?: return false
            return try {
                if (micEnabled) {
                    micEnabled = false
                    mic.stop()
                } else {
                    mic.startRecording()
                    micEnabled = true
                }
                micEnabled
            } catch (e: Throwable) {
                micEnabled = false
                false
            }
        }

        fun pause() {
            paused = true
            pausedAt = SystemClock.elapsedRealtime()
        }

        fun resume() {
            pausedTotal += SystemClock.elapsedRealtime() - pausedAt
            resumePending = true
            paused = false
            videoCodec?.let {
                runCatching {
                    it.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
                }
            }
        }

        private fun addTrack(format: MediaFormat): Int {
            synchronized(lock) {
                val index = muxer!!.addTrack(format)
                tryStartMuxer()
                return index
            }
        }

        private fun tryStartMuxer() {
            if (muxerStarted) return
            val ready = videoTrack >= 0 && (!audioEnabled || audioTrack >= 0)
            if (!ready) return
            muxer!!.start()
            muxerStarted = true
            pending.forEach { muxer!!.writeSampleData(it.track, it.data, it.info) }
            pending.clear()
        }

        private fun writeSample(track: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) {
            synchronized(lock) {
                if (muxerStarted) {
                    muxer!!.writeSampleData(track, data, info)
                } else {
                    val copy = ByteBuffer.allocate(info.size)
                    data.position(info.offset)
                    data.limit(info.offset + info.size)
                    copy.put(data)
                    copy.flip()
                    val infoCopy = MediaCodec.BufferInfo()
                    infoCopy.set(0, info.size, info.presentationTimeUs, info.flags)
                    pending.add(PendingSample(track, copy, infoCopy))
                }
            }
        }

        private fun drainVideo() {
            val codec = videoCodec ?: return
            val info = MediaCodec.BufferInfo()
            try {
                while (true) {
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val track = addTrack(codec.outputFormat)
                            synchronized(lock) {
                                videoTrack = track
                                tryStartMuxer()
                            }
                        }
                        index >= 0 -> {
                            val buffer = codec.getOutputBuffer(index)
                            val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (buffer != null && info.size > 0 && !isConfig && videoTrack >= 0) {
                                handleVideoSample(buffer, info)
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        }
                        index == MediaCodec.INFO_TRY_AGAIN_LATER && !running -> {
                            Thread.sleep(5)
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Video drain failed", e)
            }
        }

        private fun handleVideoSample(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
            val raw = info.presentationTimeUs
            if (paused) return
            if (firstRawPts < 0) firstRawPts = raw
            if (resumePending) {
                val gap = raw - lastRawPts - 33_000L
                if (gap > 0) shiftUs += gap
                resumePending = false
            }
            lastRawPts = raw
            info.presentationTimeUs = (raw - firstRawPts - shiftUs).coerceAtLeast(0L)
            buffer.position(info.offset)
            buffer.limit(info.offset + info.size)
            writeSample(videoTrack, buffer, info)
        }

        private fun pumpAudio() {
            val record = playbackRecord ?: return
            val codec = audioCodec ?: return
            val chunk = ByteArray(4096)
            val micChunk = ByteArray(2048)
            val info = MediaCodec.BufferInfo()
            try {
                while (running) {
                    val read = record.read(chunk, 0, chunk.size)
                    if (read <= 0) continue
                    if (micEnabled && micRecord != null) mixMic(chunk, read, micChunk)
                    if (!paused) {
                        feedAudio(codec, chunk, read, false)
                    }
                    drainAudio(codec, info, false)
                }
                feedAudio(codec, chunk, 0, true)
                drainAudio(codec, info, true)
            } catch (e: Throwable) {
                Log.e(TAG, "Audio pump failed", e)
            }
        }

        private fun mixMic(chunk: ByteArray, read: Int, micChunk: ByteArray) {
            val frames = read / 4
            val micBytes = micRecord!!.read(micChunk, 0, minOf(micChunk.size, frames * 2))
            if (micBytes <= 0) return
            val micSamples = micBytes / 2
            for (i in 0 until micSamples) {
                val m = (micChunk[i * 2].toInt() and 0xFF) or (micChunk[i * 2 + 1].toInt() shl 8)
                for (channel in 0..1) {
                    val pos = i * 4 + channel * 2
                    if (pos + 1 >= read) continue
                    val g = (chunk[pos].toInt() and 0xFF) or (chunk[pos + 1].toInt() shl 8)
                    val mixed = (g.toShort().toInt() + m.toShort().toInt()).coerceIn(-32768, 32767)
                    chunk[pos] = (mixed and 0xFF).toByte()
                    chunk[pos + 1] = ((mixed shr 8) and 0xFF).toByte()
                }
            }
        }

        private fun feedAudio(codec: MediaCodec, data: ByteArray, length: Int, endOfStream: Boolean) {
            var tries = 0
            while (tries < 50) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val input = codec.getInputBuffer(index) ?: return
                    input.clear()
                    val size = minOf(length, input.capacity())
                    if (size > 0) input.put(data, 0, size)
                    val ptsUs = audioFrames * 1_000_000L / rate
                    audioFrames += size / 4
                    codec.queueInputBuffer(index, 0, size, ptsUs, if (endOfStream) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                    return
                }
                tries++
            }
        }

        private fun drainAudio(codec: MediaCodec, info: MediaCodec.BufferInfo, untilEnd: Boolean) {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val track = addTrack(codec.outputFormat)
                        synchronized(lock) {
                            audioTrack = track
                            tryStartMuxer()
                        }
                    }
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buffer != null && info.size > 0 && !isConfig && audioTrack >= 0) {
                            writeSample(audioTrack, buffer, info)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                    else -> if (!untilEnd) return
                }
            }
        }

        fun finish(): File? {
            running = false
            runCatching { videoCodec?.signalEndOfInputStream() }
            runCatching { audioThread?.join(3000) }
            runCatching { videoThread?.join(3000) }
            release()
            return if (file.exists() && file.length() > 0 && muxerStartedSafely()) file else {
                runCatching { file.delete() }
                null
            }
        }

        private fun muxerStartedSafely() = synchronized(lock) { muxerStarted }

        fun abort() {
            running = false
            release()
            runCatching { file.delete() }
        }

        private fun release() {
            runCatching { display?.release() }
            runCatching { playbackRecord?.stop() }
            runCatching { micRecord?.stop() }
            runCatching { playbackRecord?.release() }
            runCatching { micRecord?.release() }
            runCatching { videoCodec?.stop() }
            runCatching { videoCodec?.release() }
            runCatching { audioCodec?.stop() }
            runCatching { audioCodec?.release() }
            runCatching { inputSurface?.release() }
            synchronized(lock) {
                if (muxerStarted) runCatching { muxer?.stop() }
                runCatching { muxer?.release() }
            }
            runCatching { projection.stop() }
        }
    }
}
