
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
import android.provider.Settings
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

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    private val language = Locale("en", "ZA")

    private val microphoneCode = 100
    private val contactsCode = 101
    private val bluetoothCode = 102

    private var triedEnglishFallback = false
    private var activeLanguage = "en-ZA"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.BLACK

        tts = TextToSpeech(this, this)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.TRANSPARENT)

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
            isClickable = true
            isFocusable = true
            contentDescription = "Tap to talk to SIMMY"

            setOnClickListener {
                startListening()
            }

            setOnLongClickListener {
                speak("Background wake word is not configured yet. Tap me to talk.")
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
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {

            val result = tts?.setLanguage(language)

            if (
                result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                tts?.setLanguage(Locale.UK)
            }

            ttsReady = true
            speak("Hello. SIMMY is ready.")
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    // START VOICE RECOGNITION

    private fun startListening() {

        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                microphoneCode
            )
            return
        }

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Speech recognition is not available on this phone.")
            return
        }

        triedEnglishFallback = false
        activeLanguage = "en-ZA"

        startSpeechRecognition()
    }

    private fun startSpeechRecognition() {

        try {

            destroyRecognizer()

            recognizer = SpeechRecognizer.createSpeechRecognizer(this)

            recognizer?.setRecognitionListener(
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

                        val shouldFallback =
                            !triedEnglishFallback &&
                            (
                                error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                                error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ||
                                error == SpeechRecognizer.ERROR_NO_MATCH
                            )

                        if (shouldFallback) {

                            triedEnglishFallback = true
                            activeLanguage = "en-GB"

                            destroyRecognizer()
                            startSpeechRecognition()
                            return
                        }

                        destroyRecognizer()

                        val message = when (error) {

                            SpeechRecognizer.ERROR_AUDIO ->
                                "There is a microphone problem."

                            SpeechRecognizer.ERROR_NETWORK ->
                                "Please check your internet connection."

                            SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Speech recognition timed out."

                            SpeechRecognizer.ERROR_NO_MATCH ->
                                "I couldn't recognise your words. Please try again."

                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "I didn't hear you. Please speak after tapping."

                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "Please allow microphone permission."

                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                "Speech recognition is busy. Try again."

                            SpeechRecognizer.ERROR_SERVER ->
                                "The speech recognition server had a problem."

                            else ->
                                "Speech recognition error $error. Please try again."
                        }

                        speak(message)
                    }

                    override fun onResults(results: Bundle?) {

                        val words = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        val command = words?.firstOrNull()

                        destroyRecognizer()

                        if (command.isNullOrBlank()) {
                            speak("I didn't hear a command. Please try again.")
                        } else {
                            handleCommand(
                                command.lowercase(Locale.ROOT).trim()
                            )
                        }
                    }

                    override fun onPartialResults(results: Bundle?) {}

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {}
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
                    activeLanguage
                )

                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                    activeLanguage
                )

                // Use the normal speech service.
                // Do not force offline recognition.

                putExtra(
                    RecognizerIntent.EXTRA_PREFER_OFFLINE,
                    false
                )

                putExtra(
                    RecognizerIntent.EXTRA_MAX_RESULTS,
                    5
                )

                putExtra(
                    RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                    true
                )
            }

            recognizer?.startListening(intent)

        } catch (e: Exception) {

            destroyRecognizer()
            speak("I could not start listening. Please try again.")
        }
    }

    private fun destroyRecognizer() {

        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (_: Exception) {
        }

        recognizer = null
    }

    // VOICE COMMANDS

    private fun handleCommand(command: String) {

        when {

            command.contains("hi simmy") ||
            command.contains("hello simmy") ||
            command == "hello" -> {
                speak("Hello! How can I help you?")
            }

            command.contains("stop talking") ||
            command == "stop" -> {
                tts?.stop()
            }

            command.contains("read my messages") ||
            command.contains("read messages") -> {

                val message =
                    MessageNotificationService.latestMessage

                if (message.isNullOrBlank()) {
                    speak("I have no recent message notifications.")
                } else {
                    speak("Your latest message says: $message")
                }
            }

            command.contains("bluetooth") -> {
                openBluetooth()
            }

            command.startsWith("call ") -> {
                findContactAndCall(
                    command.removePrefix("call ").trim()
                )
            }

            (
                command.startsWith("text ") ||
                command.startsWith("sms ")
            ) && command.contains(" saying ") -> {
                prepareSms(command)
            }

            command.contains("whatsapp") &&
            command.contains(" saying ") -> {
                prepareWhatsApp(command)
            }

            command.contains("what time") ||
            command == "time" -> {

                val time = SimpleDateFormat(
                    "h:mm a",
                    Locale.UK
                ).format(Date())

                speak("The time is $time")
            }

            command.contains("what date") ||
            command == "date" -> {

                val date = SimpleDateFormat(
                    "EEEE, d MMMM yyyy",
                    Locale.UK
                ).format(Date())

                speak("Today is $date")
            }

            command.startsWith("open ") ||
            command.startsWith("launch ") -> {

                val name = command
                    .replaceFirst(
                        Regex("^(open|launch)\\s+"),
                        ""
                    )
                    .trim()

                openInstalledApp(name)
            }

            command.contains("youtube") -> {
                openInstalledApp("YouTube")
            }

            command.contains("camera") -> {
                openCamera()
            }

            command.contains("settings") -> {
                openIntent(
                    Intent(Settings.ACTION_SETTINGS)
                )
            }

            else -> {
                speak("I don't understand that command yet.")
            }
        }
    }

    // OPEN INSTALLED APPS

    private fun openInstalledApp(spokenName: String) {

        try {

            val apps = packageManager.getInstalledApplications(
                PackageManager.GET_META_DATA
            )

            val search =
                spokenName.lowercase(Locale.ROOT).trim()

            val match = apps.firstOrNull {

                packageManager.getApplicationLabel(it)
                    .toString()
                    .lowercase(Locale.ROOT) == search

            } ?: apps.firstOrNull {

                packageManager.getApplicationLabel(it)
                    .toString()
                    .lowercase(Locale.ROOT)
                    .contains(search)
            }

            if (match == null) {
                speak("I couldn't find $spokenName.")
                return
            }

            val launch = packageManager.getLaunchIntentForPackage(
                match.packageName
            )

            if (launch == null) {
                speak("Android cannot open that app.")
                return
            }

            startActivity(launch)

        } catch (e: Exception) {
            speak("I couldn't open $spokenName.")
        }
    }

    // CAMERA

    private fun openCamera() {
        openIntent(
            Intent("android.media.action.IMAGE_CAPTURE")
        )
    }

    // BLUETOOTH

    private fun openBluetooth() {

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)
        ) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                bluetoothCode
            )

            return
        }

        speak("Opening Bluetooth settings.")

        openIntent(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        )
    }

    // WHATSAPP

    private fun prepareWhatsApp(command: String) {

        val before = command.substringBefore(" saying ")

        val message = command
            .substringAfter(" saying ")
            .trim()

        val name = before
            .replace("whatsapp", "")
            .replace("send", "")
            .replace("message", "")
            .replace(Regex("\\bto\\b"), "")
            .trim()

        if (name.isBlank() || message.isBlank()) {
            speak(
                "Say WhatsApp, contact name, saying, then your message."
            )
            return
        }

        findContact(name) { phone ->

            if (phone.isNullOrBlank()) {
                speak("I couldn't find that contact.")
                return@findContact
            }

            val number = phone.filter {
                it.isDigit() || it == '+'
            }

            val encoded = Uri.encode(message)

            openIntent(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse(
                        "https://wa.me/$number?text=$encoded"
                    )
                )
            )

            speak(
                "Your WhatsApp message is ready. Please review and send it."
            )
        }
    }

    // SMS

    private fun prepareSms(command: String) {

        val before = command.substringBefore(" saying ")

        val message = command
            .substringAfter(" saying ")
            .trim()

        val name = before
            .replaceFirst(
                Regex("^(text|sms)\\s+"),
                ""
            )
            .replace(Regex("\\bto\\b"), "")
            .trim()

        if (name.isBlank() || message.isBlank()) {
            speak(
                "Say text, contact name, saying, then your message."
            )
            return
        }

        findContact(name) { phone ->

            if (phone.isNullOrBlank()) {
                speak("I couldn't find that contact.")
                return@findContact
            }

            val intent = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("smsto:${Uri.encode(phone)}")
            ).apply {
                putExtra("sms_body", message)
            }

            openIntent(intent)

            speak(
                "Your SMS is ready. Please review and send it."
            )
        }
    }

    // CALL CONTACT

    private fun findContactAndCall(name: String) {

        findContact(name) { phone ->

            if (phone.isNullOrBlank()) {

                speak("I couldn't find that contact.")

            } else {

                openIntent(
                    Intent(
                        Intent.ACTION_DIAL,
                        Uri.parse("tel:${Uri.encode(phone)}")
                    )
                )
            }
        }
    }

    // SEARCH CONTACTS

    private fun findContact(
        name: String,
        callback: (String?) -> Unit
    ) {

        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {

            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_CONTACTS),
                contactsCode
            )

            speak(
                "Please allow contacts permission and repeat your command."
            )

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

                    val phone = cursor.getString(
                        cursor.getColumnIndexOrThrow(
                            ContactsContract.CommonDataKinds.Phone.NUMBER
                        )
                    )

                    callback(phone)
                    return
                }
            }

            callback(null)

        } catch (e: Exception) {
            callback(null)
        }
    }

    // OPEN ANDROID FEATURES

    private fun openIntent(intent: Intent) {

        try {

            if (intent.resolveActivity(packageManager) != null) {

                startActivity(intent)

            } else {

                speak(
                    "That app or feature is not available."
                )
            }

        } catch (e: Exception) {
            speak("I couldn't open that.")
        }
    }

    // SIMMY VOICE

    private fun speak(message: String) {

        if (ttsReady) {

            tts?.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "SIMMY_SPEECH"
            )

        } else {

            Toast.makeText(
                this,
                message,
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // PERMISSIONS

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

        val granted =
            grantResults.firstOrNull() ==
                PackageManager.PERMISSION_GRANTED

        when (requestCode) {

            microphoneCode -> {

                speak(
                    if (granted) {
                        "Microphone ready. Tap me to talk."
                    } else {
                        "Microphone permission is required."
                    }
                )
            }

            contactsCode -> {

                speak(
                    if (granted) {
                        "Contacts permission granted. Repeat your command."
                    } else {
                        "Contacts permission denied."
                    }
                )
            }

            bluetoothCode -> {

                if (granted) {
                    openBluetooth()
                } else {
                    speak("Bluetooth permission denied.")
                }
            }
        }
    }

    // CLOSE APP RESOURCES

    override fun onDestroy() {

        destroyRecognizer()

        tts?.stop()
        tts?.shutdown()
        tts = null

        super.onDestroy()
    }
}

// MESSAGE NOTIFICATION READER

class MessageNotificationService :
    android.service.notification.NotificationListenerService() {

    override fun onNotificationPosted(
        sbn: android.service.notification.StatusBarNotification?
    ) {

        if (sbn == null || sbn.packageName == packageName) {
            return
        }

        val extras = sbn.notification.extras

        val title = extras.getCharSequence(
            android.app.Notification.EXTRA_TITLE
        )?.toString().orEmpty()

        val message = (
            extras.getCharSequence(
                android.app.Notification.EXTRA_BIG_TEXT
            ) ?: extras.getCharSequence(
                android.app.Notification.EXTRA_TEXT
            )
        )?.toString().orEmpty()

        if (message.isNotBlank()) {

            latestMessage = if (title.isNotBlank()) {
                "$title says: $message"
            } else {
                message
            }
        }
    }

    companion object {

        @Volatile
        var latestMessage: String? = null
    }
}
