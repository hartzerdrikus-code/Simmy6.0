
package com.simmy.assistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.AnimatorSet
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var root: FrameLayout
    private lateinit var face: ImageView

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null

    private var ttsReady = false
    private var usingEnglishZA = true
    private var faceAnimator: AnimatorSet? = null

    private val englishZA = Locale("en", "ZA")
    private val englishUK = Locale.UK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createInterface()

        textToSpeech = TextToSpeech(this, this)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )
        } else {
            setupSpeechRecognizer()
        }
    }

    private fun createInterface() {
        root = FrameLayout(this)
        root.setBackgroundColor(Color.TRANSPARENT)

        face = ImageView(this).apply {
            setImageResource(R.drawable.simmy_face)
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "SIMMY"
        }

        root.addView(
            face,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // Transparent button in the centre of the screen.
        val centreButton = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true

            setOnClickListener {
                animateFace()
                startListening()
            }
        }

        val buttonSize = (190 * resources.displayMetrics.density).toInt()

        val buttonParams = FrameLayout.LayoutParams(
            buttonSize,
            buttonSize,
            Gravity.CENTER
        )

        root.addView(centreButton, buttonParams)

        setContentView(root)
    }

    // Animate SIMMY's face while listening.
    private fun animateFace() {
        stopFaceAnimation()

        val growX = ObjectAnimator.ofFloat(
            face,
            View.SCALE_X,
            1f,
            1.025f
        )

        val growY = ObjectAnimator.ofFloat(
            face,
            View.SCALE_Y,
            1f,
            1.025f
        )

        faceAnimator = AnimatorSet().apply {
            playTogether(growX, growY)
            duration = 650
            interpolator = AccelerateDecelerateInterpolator()
            startDelay = 0

            growX.repeatCount = ObjectAnimator.INFINITE
            growY.repeatCount = ObjectAnimator.INFINITE

            growX.repeatMode = ObjectAnimator.REVERSE
            growY.repeatMode = ObjectAnimator.REVERSE

            start()
        }
    }

    private fun stopFaceAnimation() {
        faceAnimator?.cancel()
        faceAnimator = null

        face.scaleX = 1f
        face.scaleY = 1f
        face.alpha = 1f
    }

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Speech recognition is not available on this device.")
            return
        }

        speechRecognizer?.destroy()

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer?.setRecognitionListener(
            object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
                    Toast.makeText(
                        this@MainActivity,
                        "SIMMY is listening...",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    if (
                        usingEnglishZA &&
                        (
                            error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                            error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                        )
                    ) {
                        usingEnglishZA = false
                        startListeningWithLanguage(englishUK)
                        return
                    }

                    stopFaceAnimation()

                    if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                        error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                    ) {
                        Toast.makeText(
                            this@MainActivity,
                            "Speech recognition error: $error",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onResults(results: Bundle?) {
                    stopFaceAnimation()

                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                    val spokenText = matches?.firstOrNull()

                    if (!spokenText.isNullOrBlank()) {
                        handleCommand(spokenText.lowercase(Locale.getDefault()))
                    } else {
                        speak("Sorry, I did not hear you.")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}

                override fun onEvent(eventType: Int, params: Bundle?) {}
            }
        )
    }

    private fun startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )
            return
        }

        if (speechRecognizer == null) {
            setupSpeechRecognizer()
        }

        usingEnglishZA = true
        startListeningWithLanguage(englishZA)
    }

    private fun startListeningWithLanguage(language: Locale) {
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE,
                    language.toLanguageTag()
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                    language.toLanguageTag()
                )

                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
            }

            animateFace()
            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {
            stopFaceAnimation()
            speak("I cannot start listening right now.")
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(englishZA)

            if (
                result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                textToSpeech?.setLanguage(englishUK)
            }

            ttsReady = true
        }
    }

    private fun speak(message: String) {
        if (ttsReady) {
            textToSpeech?.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "SIMMY_SPEECH"
            )
        }
    }

    private fun handleCommand(command: String) {

        when {

            command.contains("hi simmy") ||
            command.contains("hello simmy") ||
            command == "hello" ||
            command == "hi" -> {
                speak("Hello! I am listening. How can I help you?")
            }

            command.contains("stop talking") ||
            command == "stop" -> {
                textToSpeech?.stop()
                speak("Okay.")
            }

            command.contains("what time") ||
            command == "time" -> {
                val time = SimpleDateFormat(
                    "h:mm a",
                    Locale("en", "ZA")
                ).format(Date())

                speak("The time is $time")
            }

            command.contains("what date") ||
            command.contains("today's date") -> {
                val date = SimpleDateFormat(
                    "EEEE, d MMMM yyyy",
                    Locale("en", "ZA")
                ).format(Date())

                speak("Today is $date")
            }

            command.contains("read my messages") ||
            command.contains("read messages") -> {
                val message = MessageNotificationService.latestMessage

                if (message.isNullOrBlank()) {
                    speak("I cannot find any recent message notifications.")
                } else {
                    speak("Your latest message says: $message")
                }
            }

            command.contains("bluetooth") -> {
                speak("Opening Bluetooth settings.")
                startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
            }

            command.contains("open youtube") ||
            command.contains("launch youtube") -> {
                openApp(
                    "com.google.android.youtube",
                    "Opening YouTube."
                )
            }

            command.contains("open camera") ||
            command.contains("launch camera") -> {
                try {
                    speak("Opening camera.")
                    startActivity(Intent("android.media.action.IMAGE_CAPTURE"))
                } catch (e: Exception) {
                    speak("I cannot open the camera.")
                }
            }

            command.contains("open settings") -> {
                speak("Opening settings.")
                startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
            }

            command.startsWith("call ") -> {
                val name = command.removePrefix("call ").trim()
                callContact(name)
            }

            command.startsWith("text ") ||
            command.startsWith("sms ") -> {
                sendTextCommand(command)
            }

            command.startsWith("whatsapp ") -> {
                sendWhatsAppCommand(command)
            }

            command.startsWith("open ") ||
            command.startsWith("launch ") -> {
                val appName = command
                    .removePrefix("open ")
                    .removePrefix("launch ")
                    .trim()

                openAppByName(appName)
            }

            else -> {
                speak("Sorry, I do not understand that command yet.")
            }
        }
    }

    private fun openApp(packageName: String, message: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)

            if (launchIntent != null) {
                speak(message)
                startActivity(launchIntent)
            } else {
                speak("That app is not installed.")
            }
        } catch (e: Exception) {
            speak("I could not open that app.")
        }
    }

    private fun openAppByName(appName: String) {
        val applications = packageManager.getInstalledApplications(
            PackageManager.GET_META_DATA
        )

        val match = applications.firstOrNull {
            packageManager.getApplicationLabel(it)
                .toString()
                .lowercase(Locale.getDefault())
                .contains(appName)
        }

        if (match != null) {
            val launchIntent = packageManager.getLaunchIntentForPackage(
                match.packageName
            )

            if (launchIntent != null) {
                speak("Opening $appName.")
                startActivity(launchIntent)
                return
            }
        }

        speak("I could not find that app.")
    }

    private fun findContactNumber(name: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.READ_CONTACTS),
                101
            )
            speak("Please allow SIMMY to access your contacts, then try again.")
            return null
        }

        val cursor = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null,
            null,
            null
        )

        cursor?.use {
            while (it.moveToNext()) {
                val contactName = it.getString(0) ?: ""
                val number = it.getString(1) ?: ""

                if (contactName.contains(name, ignoreCase = true)) {
                    return number
                }
            }
        }

        return null
    }

    private fun callContact(name: String) {
        val number = findContactNumber(name)

        if (number.isNullOrBlank()) {
            speak("I could not find that contact.")
            return
        }

        speak("Opening the dialler for $name.")

        val intent = Intent(
            Intent.ACTION_DIAL,
            Uri.parse("tel:${Uri.encode(number)}")
        )

        startActivity(intent)
    }

    private fun sendTextCommand(command: String) {
        val content = command
            .removePrefix("text ")
            .removePrefix("sms ")

        val parts = content.split(" saying ", limit = 2)

        if (parts.size != 2) {
            speak("Say text, the contact name, saying, and your message.")
            return
        }

        val name = parts[0].trim()
        val message = parts[1].trim()

        val number = findContactNumber(name)

        if (number.isNullOrBlank()) {
            speak("I could not find that contact.")
            return
        }

        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("smsto:${Uri.encode(number)}")
        ).apply {
            putExtra("sms_body", message)
        }

        speak("Opening your text message.")
        startActivity(intent)
    }

    private fun sendWhatsAppCommand(command: String) {
        val content = command.removePrefix("whatsapp ").trim()
        val parts = content.split(" saying ", limit = 2)

        if (parts.size != 2) {
            speak("Say WhatsApp, the contact name, saying, and your message.")
            return
        }

        val name = parts[0].trim()
        val message = parts[1].trim()

        val number = findContactNumber(name)

        if (number.isNullOrBlank()) {
            speak("I could not find that contact.")
            return
        }

        val cleanNumber = number.filter { it.isDigit() }

        val url = Uri.parse(
            "https://wa.me/$cleanNumber?text=${Uri.encode(message)}"
        )

        try {
            speak("Opening WhatsApp.")
            startActivity(Intent(Intent.ACTION_VIEW, url))
        } catch (e: Exception) {
            speak("I could not open WhatsApp.")
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

        if (requestCode == 100) {
            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                setupSpeechRecognizer()
                startListening()
            } else {
                speak("I need microphone permission to listen.")
            }
        }
    }

    override fun onDestroy() {
        stopFaceAnimation()

        speechRecognizer?.destroy()
        speechRecognizer = null

        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null

        super.onDestroy()
    }
}
