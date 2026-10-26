package com.telugu.prompter

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Keeps Android's speech recognizer listening in Telugu for as long as [start]ed.
 *
 * The platform recognizer ends a session after every pause, so we restart it
 * immediately on results/timeouts. Partial results are delivered as they arrive.
 */
class SpeechFollower(
    private val context: Context,
    private val language: String = "te-IN",
    private val onHypothesis: (String) -> Unit,
    private val onStatus: (Status) -> Unit,
) {
    enum class Status { IDLE, LISTENING, HEARING, ERROR_NO_RECOGNIZER, ERROR_LANGUAGE, ERROR_NETWORK, ERROR_PERMISSION }

    private val handler = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var recognizer: SpeechRecognizer? = null
    private var running = false
    private var muted = false
    var muteBeeps: Boolean = true
    /** Android 13+: one long session instead of restarting after every pause (fewer missed words, no beeps). */
    var continuous: Boolean = true

    fun start() {
        if (running) return
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            onStatus(Status.ERROR_NO_RECOGNIZER)
            return
        }
        running = true
        if (muteBeeps) muteSystemSounds(true)
        createRecognizer()
        listen()
    }

    fun stop() {
        halt()
        onStatus(Status.IDLE)
    }

    private fun halt() {
        running = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.cancel()
        recognizer?.destroy()
        recognizer = null
        muteSystemSounds(false)
    }

    private fun createRecognizer() {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(listener)
        }
    }

    private fun listen() {
        if (!running) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Hints; many recognizers ignore them, which is why we also auto-restart.
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            if (continuous && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Segmented session: results arrive per pause-separated segment, but the session
                // itself keeps running. Recognizers that don't support it just behave as before.
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, SEGMENTED_SESSION_MS)
            } else {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 15000L)
            }
        }
        try {
            recognizer?.startListening(intent)
        } catch (e: Exception) {
            restart(500)
        }
    }

    private fun restart(delayMs: Long, recreate: Boolean = false) {
        if (!running) return
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (recreate) createRecognizer()
            listen()
        }, delayMs)
    }

    private fun muteSystemSounds(mute: Boolean) {
        if (mute == muted) return
        val dir = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
        for (stream in listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_SYSTEM)) {
            try {
                audio.adjustStreamVolume(stream, dir, 0)
            } catch (_: SecurityException) {
                // Do Not Disturb can block this; beeps just won't be muted.
            }
        }
        muted = mute
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = onStatus(Status.LISTENING)
        override fun onBeginningOfSpeech() = onStatus(Status.HEARING)
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.takeIf { it.isNotBlank() }?.let(onHypothesis)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            firstResult(segmentResults)?.takeIf { it.isNotBlank() }?.let(onHypothesis)
        }

        override fun onEndOfSegmentedSession() = restart(0)

        override fun onResults(results: Bundle?) {
            firstResult(results)?.takeIf { it.isNotBlank() }?.let(onHypothesis)
            restart(0)
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(0)

                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> restart(300, recreate = true)

                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> {
                    onStatus(Status.ERROR_NETWORK)
                    restart(1500, recreate = true)
                }

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    halt()
                    onStatus(Status.ERROR_PERMISSION)
                }

                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    halt()
                    onStatus(Status.ERROR_LANGUAGE)
                }

                else -> restart(500, recreate = true)
            }
        }
    }

    companion object {
        /** Length of one continuous (segmented) session before it is renewed. */
        private const val SEGMENTED_SESSION_MS = 10 * 60 * 1000L
    }
}
