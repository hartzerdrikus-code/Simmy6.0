
package com.simmy.assistant

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var status: TextView
    private lateinit var heard: TextView

    private var tts: TextToSpeech? = null
    private var speechLocale = Locale("en", "ZA")
    private var ttsReady = false

    companion object {
        private const val MIC_PERMISSION = 101
        private const val SPEECH_REQUEST = 102
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full-screen mode
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

        window.decorView.systemUiVisibility =
            (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)

        // Initialize text views BEFORE starting TTS
        status = TextView(this).apply {
            text = "Tap SIMMY to talk"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        heard = TextView(this).apply {
            text = ""
            textSize = 16f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        // Initialize the voice engine
        tts = TextToSpeech(this, this)

        // Main screen
        val root = FrameLayout(this)

        // Full-screen SIMMY image
        val backgroundImage = ImageView(this).apply {
            setImageResource(R.drawable.simmy_face)
            scaleType = ImageView.ScaleType.CENTER_CROP
            contentDescription = "SIMMY assistant"
        }

        root.addView(
            backgroundImage,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Status text at bottom
        val statusParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        )

        statusParams.bottomMargin =
            (35 * resources.displayMetrics.density).toInt()

        root.addView(status, statusParams)

        // Recognized speech
        val heardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        )

        heardParams.topMargin =
            (35 * resources.displayMetrics.density).toInt()

        root.addView(heard, heardParams)

        // Invisible clickable button over SIMMY's glowing circle
        val talkButton = View(this).apply {

            setBackgroundColor(Color.TRANSPARENT)

            isClickable = true
            isFocusable = true

            contentDescription = "Talk to SIMMY"

            // Tap to talk
            setOnClickListener {
                checkMicrophonePermission()
            }

            // Long press to change language
            setOnLongClickListener {
                toggleLanguage()
                true
            }
        }

        val talkSize =
            (190 * resources.displayMetrics.density).toInt()

        val talkParams = FrameLayout.LayoutParams(
            talkSize,
            talkSize,
            Gravity.CENTER
        )

        root.addView(talkButton, talkParams)

        setContentView(root)
    }

    // Change between English and Afrikaans
    private fun toggleLanguage() {

        speechLocale =
            if (speechLocale.language == "en") {
                Locale("af", "ZA")
            } else {
                Locale("en", "ZA")
            }

        setVoiceLanguage()

        val message =
            if (speechLocale.language == "af") {
                "Afrikaans gekies"
            } else {
                "English selected"
            }

        reply(message)
    }

    // Microphone permission
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

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == MIC_PERMISSION) {

            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                startListening()

            } else {
                reply("I need microphone permission to hear you.")
            }
        }
    }

    // Start speech recognition
    private fun startListening() {

        if (!android.speech.SpeechRecognizer
                .isRecognitionAvailable(this)
        ) {
            reply("Speech recognition is not available.")
            return
        }

        val intent = Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                speechLocale.toLanguageTag()
            )

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                speechLocale.toLanguageTag()
            )

            putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                if (speechLocale.language == "af") {
                    "Praat met Simmy"
                } else {
                    "Speak to Simmy"
                }
            )

            putExtra(
                RecognizerIntent.EXTRA_MAX_RESULTS,
                5
            )

            putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                false
            )
        }

        try {

            status.text =
                if (speechLocale.language == "af") {
                    "Ek luister..."
                } else {
                    "Listening..."
                }

            startActivityForResult(
                intent,
                SPEECH_REQUEST
            )

        } catch (e: Exception) {
            reply("I could not start speech recognition.")
        }
    }

    @Deprecated("Legacy speech result callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {

        super.onActivityResult(
            requestCode,
            resultCode,
            data
        )

        if (requestCode != SPEECH_REQUEST) return

        if (resultCode == RESULT_OK && data != null) {

            val results = data.getStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS
            )

            val spoken = results?.firstOrNull()

            if (!spoken.isNullOrBlank()) {

                heard.text = "You said: $spoken"

                handleCommand(spoken)

            } else {
                reply("I didn't catch that. Please try again.")
            }

        } else {
            status.text = "Ready / Gereed"
        }
    }

    // Understand commands
    private fun handleCommand(raw: String) {

        val text = raw.lowercase(Locale.ROOT).trim()

        when {

            text.contains("open whatsapp") ||
            text.contains("maak whatsapp oop") ||
            text.contains("launch whatsapp") ||
            text.contains("start whatsapp") ->
                openApp("com.whatsapp", "WhatsApp")

            text.contains("open youtube") ||
            text.contains("maak youtube oop") ||
            text.contains("launch youtube") ||
            text.contains("start youtube") ->
                openApp(
                    "com.google.android.youtube",
                    "YouTube"
                )

            text.contains("open camera") ||
            text.contains("maak kamera oop") ||
            text.contains("open the camera") ->
                openCamera()

            text.contains("open settings") ||
            text.contains("phone settings") ||
            text.contains("maak settings oop") ->
                openSettings()

            text.contains("search for") ||
            text.contains("google") ||
            text.startsWith("search ") ||
            text.startsWith("soek ") ||
            text.contains("search the internet") ->
                webSearch(raw)

            text.contains("set timer") ||
            text.contains("timer vir") ||
            text.contains("start timer") ||
            text.contains("minute timer") ||
            text.contains("second timer") ->
                setTimer(raw)

            text.contains("set alarm") ||
            text.contains("alarm for") ||
            text.contains("alarm om") ||
            text.contains("wake me") ||
            text.contains("stel alarm") ->
                setAlarm(raw)

            text.contains("what time") ||
            text.contains("what is the time") ||
            text.contains("hoe laat is dit") ->
                tellTime()

            text.contains("hello") ||
            text.contains("hi simmy") ||
            text.contains("hallo simmy") ->
                reply("Hello! Ek is hier. What can I do for you?")

            text.contains("how are you") ||
            text.contains("hoe gaan dit") ->
                reply("I'm doing great, dankie!")

            text.contains("your name") ||
            text.contains("jou naam") ->
                reply("My name is Simmy, your personal assistant.")

            text.contains("thank you") ||
            text.contains("dankie") ->
                reply("You're welcome! Geen probleem.")

            else -> {

                if (!openAppByName(raw)) {
                    webSearch(raw)
                }
            }
        }
    }

    // Open known applications
    private fun openApp(
        packageName: String,
        appName: String
    ) {

        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName)

        if (launchIntent != null) {

            status.text = "Opening $appName..."

            speak("Opening $appName")

            startActivity(launchIntent)

        } else {

            reply("$appName is not installed.")

            webSearch(appName)
        }
    }

    // Camera
    private fun openCamera() {

        try {

            val intent = Intent(
                "android.media.action.IMAGE_CAPTURE"
            )

            speak("Opening camera")

            startActivity(intent)

        } catch (e: Exception) {
            reply("I couldn't open the camera.")
        }
    }

    // Settings
    private fun openSettings() {

        try {

            speak("Opening settings")

            startActivity(
                Intent(
                    android.provider.Settings.ACTION_SETTINGS
                )
            )

        } catch (e: Exception) {
            reply("I couldn't open settings.")
        }
    }

    // Google search
    private fun webSearch(query: String) {

        try {

            var clean = query

            val replacements = listOf(
                "search the internet for",
                "search for",
                "search",
                "soek vir",
                "soek",
                "google"
            )

            for (word in replacements) {

                clean = clean.replace(
                    Regex(word, RegexOption.IGNORE_CASE),
                    ""
                )
            }

            clean = clean.replace(
                Regex("^for ", RegexOption.IGNORE_CASE),
                ""
            ).trim()

            if (clean.isBlank()) {
                clean = query
            }

            val url =
                "https://www.google.com/search?q=" +
                Uri.encode(clean)

            status.text = "Searching the internet..."

            speak("I'll search the internet for $clean")

            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(url)
                )
            )

        } catch (e: Exception) {
            reply("I couldn't start the internet search.")
        }
    }

    // Timer
    private fun setTimer(raw: String) {

        val number = Regex("""(\d+)""")
            .find(raw)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()

        if (number == null || number <= 0) {

            reply("Tell me how long. For example, 10 minutes.")
            return
        }

        val seconds = when {

            raw.contains("second", true) ||
            raw.contains("sekonde", true) ->
                number

            raw.contains("hour", true) ||
            raw.contains("uur", true) ->
                number * 3600

            else ->
                number * 60
        }

        try {

            val intent = Intent(
                android.provider.AlarmClock.ACTION_SET_TIMER
            ).apply {

                putExtra(
                    android.provider.AlarmClock.EXTRA_LENGTH,
                    seconds
                )

                putExtra(
                    android.provider.AlarmClock.EXTRA_MESSAGE,
                    "Simmy timer"
                )

                putExtra(
                    android.provider.AlarmClock.EXTRA_SKIP_UI,
                    false
                )
            }

            speak("Setting your timer")

            startActivity(intent)

        } catch (e: Exception) {
            reply("I couldn't set the timer.")
        }
    }

    // Alarm
    private fun setAlarm(raw: String) {

        val time = Regex("""(\d{1,2})[:.](\d{2})""")
            .find(raw)

        if (time == null) {

            reply("Tell me the time. For example, 6:30.")
            return
        }

        val hour = time.groupValues[1].toInt()
        val minute = time.groupValues[2].toInt()

        if (hour !in 0..23 || minute !in 0..59) {

            reply("That is not a valid time.")
            return
        }

        try {

            val intent = Intent(
                android.provider.AlarmClock.ACTION_SET_ALARM
            ).apply {

                putExtra(
                    android.provider.AlarmClock.EXTRA_HOUR,
                    hour
                )

                putExtra(
                    android.provider.AlarmClock.EXTRA_MINUTES,
                    minute
                )

                putExtra(
                    android.provider.AlarmClock.EXTRA_MESSAGE,
                    "Simmy alarm"
                )
            }

            speak("Setting your alarm")

            startActivity(intent)

        } catch (e: Exception) {
            reply("I couldn't set the alarm.")
        }
    }

    // Tell time
    private fun tellTime() {

        val now = SimpleDateFormat(
            "HH:mm",
            Locale.getDefault()
        ).format(Date())

        reply("The time is $now")
    }

    // Reply
    private fun reply(message: String) {

        status.text = message

        speak(message)
    }

    // Text to speech initialization
    override fun onInit(result: Int) {

        if (result == TextToSpeech.SUCCESS) {

            ttsReady = true

            setVoiceLanguage()

        } else {

            status.text = "Voice engine failed to initialize."
        }
    }

    // Voice language
    private fun setVoiceLanguage() {

        val locale =
            if (speechLocale.language == "af") {
                Locale("af", "ZA")
            } else {
                Locale("en", "ZA")
            }

        val languageResult = tts?.setLanguage(locale)

        if (
            languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {

            tts?.setLanguage(Locale.US)
        }

        val femaleVoice = tts?.voices?.firstOrNull {

            it.name.contains(
                "female",
                ignoreCase = true
            ) && it.locale.language == speechLocale.language
        }

        if (femaleVoice != null) {
            tts?.voice = femaleVoice
        }
    }

    // Speak
    private fun speak(message: String) {

        if (ttsReady) {

            tts?.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "SIMMY_REPLY"
            )
        }
    }

    // Open any installed app by its displayed name
    private fun openAppByName(raw: String): Boolean {

        val text = raw.lowercase(Locale.ROOT).trim()

        val prefixes = listOf(
            "open ",
            "launch ",
            "start ",
            "maak ",
            "maak die "
        )

        val appName = prefixes.firstOrNull {
            text.startsWith(it)
        }?.let {
            text.removePrefix(it).trim()
        } ?: return false

        if (appName.isBlank()) return false

        val launcherIntent = Intent(
            Intent.ACTION_MAIN
        ).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }

        val apps = packageManager.queryIntentActivities(
            launcherIntent,
            0
        )

        val matchingApp = apps.firstOrNull {

            it.loadLabel(packageManager)
                .toString()
                .lowercase(Locale.ROOT) == appName

        } ?: apps.firstOrNull {

            it.loadLabel(packageManager)
                .toString()
                .lowercase(Locale.ROOT)
                .contains(appName)
        } ?: return false

        val packageName =
            matchingApp.activityInfo.packageName

        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName)
                ?: return false

        val name = matchingApp.loadLabel(packageManager)

        status.text = "Opening $name..."

        speak("Opening $name")

        startActivity(launchIntent)

        return true
    }

    // Clean up
    override fun onDestroy() {

        tts?.stop()
        tts?.shutdown()

        super.onDestroy()
    }
}
