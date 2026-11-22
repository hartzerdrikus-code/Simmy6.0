
package com.simmy.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var isAfrikaans = false

    private val microphoneRequestCode = 100
    private val speechRequestCode = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN

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

        // Invisible touch area over the glowing circle.
        val talkButton = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
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

        val buttonSize = (190 * resources.displayMetrics.density).toInt()

        val buttonParams = FrameLayout.LayoutParams(
            buttonSize,
            buttonSize,
            Gravity.CENTER
        )

        root.addView(talkButton, buttonParams)

        setContentView(root)

        checkMicrophonePermission()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = if (isAfrikaans) {
                Locale("af", "ZA")
            } else {
                Locale("en", "ZA")
            }
            ttsReady = true
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

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                if (isAfrikaans) "af-ZA" else "en-ZA"
            )
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Listening to you...")
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            startActivityForResult(intent, speechRequestCode)
        } catch (e: Exception) {
            Toast.makeText(
                this,
                "Speech recognition is not available",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == speechRequestCode &&
            resultCode == RESULT_OK
        ) {
            val results = data?.getStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS
            )

            val spokenText = results?.firstOrNull()?.lowercase(Locale.ROOT)

            if (!spokenText.isNullOrBlank()) {
                handleCommand(spokenText)
            }
        }
    }

    private fun handleCommand(command: String) {
        when {
            command.contains("hello") ||
            command.contains("hi simmy") -> {
                speak(if (isAfrikaans) "Hallo! Hoe kan ek help?" else "Hello! How can I help?")
            }

            command.contains("time") ||
            command.contains("what time") -> {
                val time = java.text.SimpleDateFormat(
                    "HH:mm",
                    Locale.getDefault()
                ).format(java.util.Date())

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
                openIntent(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE))
            }

            command.contains("settings") -> {
                openIntent(Intent(android.provider.Settings.ACTION_SETTINGS))
            }

            command.contains("google") ||
            command.contains("search") -> {
                val searchText = command
                    .replace("search", "")
                    .replace("google", "")
                    .trim()

                val searchIntent = Intent(
                    Intent.ACTION_WEB_SEARCH
                ).apply {
                    putExtra(
                        "query",
                        searchText.ifBlank { command }
                    )
                }

                openIntent(searchIntent)
            }

            command.contains("stop") -> {
                tts.stop()
            }

            else -> {
                speak(
                    if (isAfrikaans) "Ek het jou gehoor, maar ek verstaan nie daardie opdrag nie."
                    else "I heard you, but I don't understand that command yet."
                )
            }
        }
    }

    private fun openAppOrWebsite(packageName: String, website: String) {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

        if (launchIntent != null) {
            startActivity(launchIntent)
        } else {
            openIntent(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(website)))
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
            tts.language = if (isAfrikaans) {
                Locale("af", "ZA")
            } else {
                Locale("en", "ZA")
            }

            tts.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "SIMMY_SPEECH"
            )
        }
    }

    override fun onDestroy() {
        if (::tts.isInitialized) {
            tts.stop()
            tts.shutdown()
        }
        super.onDestroy()
    }
}
