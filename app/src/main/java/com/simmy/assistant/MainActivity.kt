
package com.simmy.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
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
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private var ttsReady = false
    private var speechRecognizer: SpeechRecognizer? = null

    private val microphoneRequestCode = 100
    private val contactsRequestCode = 101

    private val simmyLanguage = Locale("en", "ZA")

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
            isFocusable = true
            setOnClickListener {
                startListening()
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

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts.setLanguage(simmyLanguage)

            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                tts.setLanguage(Locale.UK)
                speak("English voice selected.")
            } else {
                ttsReady = true
            }
        }
    }

    private fun startListening() {
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
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Speech recognition is not available on this phone.")
            return
        }

        try {
            speechRecognizer?.destroy()

            speechRecognizer = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }

            speechRecognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        speak("I could not hear you. Please try again.")
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        val command = matches?.firstOrNull()

                        if (!command.isNullOrBlank()) {
                            handleCommand(command.lowercase(Locale.ROOT))
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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-ZA")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-ZA")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {
            speak("I could not start listening.")
        }
    }

    private fun handleCommand(command: String) {
        when {
            command.contains("read my messages") ||
            command.contains("read messages") ||
            command == "read my message" -> readLatestMessage()

            command.contains("bluetooth") -> openBluetoothSettings()

            command.startsWith("call ") -> {
                val name = command.removePrefix("call ").trim()
                findContactAndCall(name)
            }

            command.contains("whatsapp") &&
            command.contains("saying") -> {
                prepareWhatsAppMessage(command)
            }

            (command.startsWith("text ") ||
             command.startsWith("sms ") ||
             command.startsWith("message ")) &&
            command.contains("saying") -> {
                prepareSmsMessage(command)
            }

            command.contains("time") -> {
                val time = SimpleDateFormat(
                    "HH:mm",
                    Locale.UK
                ).format(Date())
                speak("The time is $time")
            }

            command.contains("date") -> {
                val date = SimpleDateFormat(
                    "EEEE, d MMMM yyyy",
                    Locale.UK
                ).format(Date())
                speak("Today is $date")
            }

            command.contains("youtube") -> {
                openAppOrWebsite(
                    "com.google.android.youtube",
                    "https://www.youtube.com"
                )
            }

            command.contains("stop talking") ||
            command == "stop" -> {
                tts.stop()
            }

            command.contains("hello") ||
            command.contains("hi simmy") -> {
                speak("Hello! I am SIMMY. How can I help you?")
            }

            else -> {
                speak("I heard you. Please try another command.")
            }
        }
    }

    private fun openBluetoothSettings() {
        speak("Opening Bluetooth settings. Choose your device to pair or connect.")

        try {
            startActivity(
                Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
            )
        } catch (e: Exception) {
            speak("I could not open Bluetooth settings.")
        }
    }

    private fun readLatestMessage() {
        val message = MessageNotificationService.latestMessage

        if (message.isNullOrBlank()) {
            speak(
                "I have no recent message notifications. " +
                "Please enable SIMMY notification access in settings."
            )
        } else {
            speak("Your latest message says: $message")
        }
    }

    private fun prepareWhatsAppMessage(command: String) {
        val beforeSaying = command.substringBefore("saying").trim()
        val message = command.substringAfter("saying").trim()

        val name = beforeSaying
            .replace("whatsapp", "")
            .replace("send", "")
            .replace("message", "")
            .replace("to", "")
            .trim()

        if (name.isBlank() || message.isBlank()) {
            speak("Please say: WhatsApp, contact name, saying, then your message.")
            return
        }

        findContact(name) { phone ->
            try {
                if (!phone.isNullOrBlank()) {
                    val number = phone.filter { it.isDigit() || it == '+' }
                    val encodedMessage = URLEncoder.encode(message, "UTF-8")
                    val url = "https://wa.me/$number?text=$encodedMessage"

                    startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    )
                } else {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        setPackage("com.whatsapp")
                        putExtra(Intent.EXTRA_TEXT, message)
                    }
                    startActivity(intent)
                }

                speak("Your WhatsApp message is ready. Please check it and send it.")
            } catch (e: Exception) {
                speak("WhatsApp could not be opened. Is it installed?")
            }
        }
    }

    private fun prepareSmsMessage(command: String) {
        val beforeSaying = command.substringBefore("saying").trim()
        val message = command.substringAfter("saying").trim()

        val name = beforeSaying
            .removePrefix("text")
            .removePrefix("sms")
            .removePrefix("message")
            .replace("to", "")
            .trim()

        if (name.isBlank() || message.isBlank()) {
            speak("Please say: Text, contact name, saying, then your message.")
            return
        }

        findContact(name) { phone ->
            if (phone.isNullOrBlank()) {
                speak("I could not find that contact's phone number.")
                return@findContact
            }

            try {
                val intent = Intent(
                    Intent.ACTION_SENDTO,
                    Uri.parse("smsto:${Uri.encode(phone)}")
                ).apply {
                    putExtra("sms_body", message)
                }

                startActivity(intent)
                speak("Your text message is ready. Please check it and send it.")
            } catch (e: Exception) {
                speak("I could not open your messaging app.")
            }
        }
    }

    private fun findContactAndCall(name: String) {
        findContact(name) { phone ->
            if (phone.isNullOrBlank()) {
                speak("I could not find that contact's phone number.")
            } else {
                speak("Opening the phone dialler for $name.")
                val intent = Intent(
                    Intent.ACTION_DIAL,
                    Uri.parse("tel:${Uri.encode(phone)}")
                )
                startActivity(intent)
            }
        }
    }

    private fun findContact(
        name: String,
        callback: (String?) -> Unit
    ) {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS),
                contactsRequestCode
            )

            speak("Please allow SIMMY to access your contacts, then try again.")
            return
        }

        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI,
                Uri.encode(name)
            )

            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            contentResolver.query(
                uri,
                projection,
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val number = cursor.getString(
                        cursor.getColumnIndexOrThrow(
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                        )
                    )
                    callback(number)
                    return
                }
            }

            speak("I could not find $name in your contacts.")
            callback(null)

        } catch (e: Exception) {
            speak("I could not search your contacts.")
            callback(null)
        }
    }

    private fun openAppOrWebsite(
        packageName: String,
        website: String
    ) {
        try {
            val launchIntent =
                packageManager.getLaunchIntentForPackage(packageName)

            if (launchIntent != null) {
                startActivity(launchIntent)
            } else {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(website))
                )
            }
        } catch (e: Exception) {
            speak("I could not open that app.")
        }
    }

    private fun speak(message: String) {
        if (ttsReady) {
            tts.language = simmyLanguage
            tts.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "SIMMY_SPEECH"
            )
        } else {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
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
            speak("SIMMY microphone is ready.")
        }

        if (requestCode == contactsRequestCode &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            speak("Contact access is ready. Please repeat your command.")
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
