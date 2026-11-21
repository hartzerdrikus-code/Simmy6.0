package com.simmy.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var status: TextView
    private lateinit var mic: Button
    private var tts: TextToSpeech? = null

    companion object {
        private const val MIC_PERMISSION = 101
        private const val SPEECH_REQUEST = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(12, 18, 30))
        }

        val title = TextView(this).apply {
            text = "SIMMY 6.0"
            textSize = 38f
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Hey! Ek is Simmy."
            textSize = 26f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            text = "Ek luister... I'm listening!"
            textSize = 18f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }

        mic = Button(this).apply {
            text = "🎙️ TALK TO SIMMY"
            textSize = 20f
            setOnClickListener {
                checkMicrophonePermission()
            }
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(status)

        layout.addView(mic)

        setContentView(layout)
    }

    private fun checkMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            startListening()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                MIC_PERMISSION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == MIC_PERMISSION) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startListening()
            } else {
                status.text = "Microphone permission denied."
                speak("I need microphone permission to hear you.")
            }
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.text = "Speech recognition is not available."
            speak("Speech recognition is not available on this phone.")
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Simmy")
        }

        try {
            status.text = "Listening... / Ek luister..."
            startActivityForResult(intent, SPEECH_REQUEST)
        } catch (e: Exception) {
            status.text = "Could not start speech recognition."
        }
    }

    @Deprecated("Legacy speech result callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == SPEECH_REQUEST) {
            if (resultCode == RESULT_OK && data != null) {
                val results = data.getStringArrayListExtra(
                    RecognizerIntent.EXTRA_RESULTS
                )

                val spokenText = results?.firstOrNull()

                if (!spokenText.isNullOrBlank()) {
                    status.text = "You said: $spokenText"
                    respond(spokenText)
                } else {
                    status.text = "I didn't catch that. Try again."
                }
            } else {
                status.text = "Speech cancelled. Tap to try again."
            }
        }
    }

    private fun respond(input: String) {
        val message = input.lowercase(Locale.ROOT)

        val reply = when {
            message.contains("hello") || message.contains("hi simmy") ->
                "Hello! Ek is hier. How can I help you?"

            message.contains("hoe gaan dit") || message.contains("how are you") ->
                "Dit gaan goed met my! And how are you doing?"

            message.contains("your name") || message.contains("jou naam") ->
                "My name is Simmy, your personal assistant."

            message.contains("thank you") || message.contains("dankie") ->
                "You're welcome! Geen probleem."

            else ->
                "Ek het jou gehoor. You said: $input"
        }

        status.text = reply
        speak(reply)
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            val languageResult = tts?.setLanguage(Locale("en", "ZA"))

            if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                tts?.setLanguage(Locale("en", "US"))
            }

            // Prefer a voice whose name identifies it as female, if available.
            val voices = tts?.voices
            val femaleVoice = voices?.firstOrNull {
                it.name.contains("female", ignoreCase = true) &&
                it.locale.language == "en"
            }

            if (femaleVoice != null) {
                tts?.voice = femaleVoice
            }
        }
    }

    private fun speak(text: String) {
        tts?.speak(
            text,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "SIMMY_REPLY"
        )
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
