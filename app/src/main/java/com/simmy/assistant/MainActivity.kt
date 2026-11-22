
package com.simmy.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null
    private var isAfrikaans = false

    private val microphoneRequestCode = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK

        tts = TextToSpeech(this, this)

        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        val face = ImageView(this).apply {
            setImageResource(R.drawable.simmy_face)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = null
        }

        root.addView(
            face,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val talkButton = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            background = ColorDrawable(Color.TRANSPARENT)
            isClickable = true

            setOnClickListener {
                startListening()
            }

            setOnLongClickListener {
                isAfrikaans = !isAfrikaans
                speak(
                    if (isAfrikaans) "Afrikaans mode activated"
                    else "English mode activated"
                )
                true
            }
        }

        val buttonSize =
            (190 * resources.displayMetrics.density).toInt()

        root.addView(
            talkButton,
            FrameLayout.LayoutParams(
                buttonSize,
                buttonSize,
                Gravity.CENTER
            )
        )

        setContentView(root)

        checkMicrophonePermission()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            ttsReady = true
            setSpeechLanguage()
        }
    }

    private fun setSpeechLanguage() {
        if (!ttsReady) return

        val result = if (isAfrikaans) {
            tts.setLanguage(Locale("af", "ZA"))
        } else {
            tts.setLanguage(Locale("en", "ZA"))
        }

        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Toast.makeText(
                this,
                "This language is not available for speech.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun checkMicrophonePermission() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                microphoneRequestCode
            )
        }
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkMicrophonePermission()
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                this,
                "Speech recognition is not available on this device.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        try {
            speechRecognizer?.destroy()

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            speechRecognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {}

                    override fun onBeginningOfSpeech() {}

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        Toast.makeText(
                            this@MainActivity,
                            "I couldn't hear you. Tap the circle and try again.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        val spokenText = matches?.firstOrNull()

                        if (!spokenText.isNullOrBlank()) {
                            handleCommand(
                                spokenText.lowercase(Locale.ROOT)
                            )
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {}

                    override fun onEvent(eventType: Int, params: Bundle?) {}
                }
            )

            val intent = Intent(
                RecognizerIntent.ACTION_RECOGNIZE_SPEECH
            ).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    if (isAfrikaans) "af-ZA" else "en-ZA"
                )
                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    3
                )
                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    false
                )
            }

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Could not start listening.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun handleCommand(command: String) {
        when {
            command.contains("hello") ||
            command.contains("hi simmy") -> {
                speak(
                    if (isAfrikaans) "Hallo! Hoe kan ek help?"
                    else "Hello! How can I help?"
                )
            }

            command.contains("time") -> {
                val time = SimpleDateFormat(
                    "HH:mm",
                    Locale.getDefault()
                ).format(Date())

                speak("The time is $time")
            }

            command.contains("youtube") -> {
                openAppOrWebsite(
                    "com.google.android.youtube",
                    "https://www.youtube.com"
                )
            }

            command.contains("whatsapp") -> {
                openAppOrWebsite(
                    "com.whatsapp",
                    "https://www.whatsapp.com"
                )
            }

            command.contains("camera") -> {
                openIntent(
                    Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                )
            }

            command.contains("settings") -> {
                openIntent(
                    Intent(android.provider.Settings.ACTION_SETTINGS)
                )
            }

            command.contains("search") ||
            command.contains("google") -> {
                val searchText = command
                    .replace("search", "")
                    .replace("google", "")
                    .trim()

                val intent = Intent(
                    Intent.ACTION_WEB_SEARCH
                ).apply {
                    putExtra(
                        "query",
                        searchText.ifBlank { command }
                    )
                }

                openIntent(intent)
            }

            command.contains("stop") -> {
                tts.stop()
            }

            else -> {
                speak(
                    if (isAfrikaans)
                        "Ek verstaan nie daardie opdrag nie."
                    else
                        "Sorry, I don't understand that command yet."
                )
            }
        }
    }

    private fun openAppOrWebsite(
        packageName: String,
        website: String
    ) {
        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName)

        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            openIntent(
                Intent(
                    Intent.ACTION_VIEW,
                    android.net.Uri.parse(website)
                )
            )
        }
    }

    private fun openIntent(intent: Intent) {
        try {
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                speak("That app is not available.")
            }
        } catch (e: Exception) {
            speak("Sorry, I could not open that.")
        }
    }

    private fun speak(message: String) {
        if (ttsReady) {
            setSpeechLanguage()
            tts.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "SIMMY_SPEECH"
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == microphoneRequestCode &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(
                this,
                "SIMMY microphone is ready.",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null

        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }

        super.onDestroy()
    }
}
