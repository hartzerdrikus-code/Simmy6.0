
package com.simmy.assistant

import android.Manifest
import android.app.Activity
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
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    private val language = Locale("en", "ZA")
    private val microphoneCode = 100
    private val contactsCode = 101
    private val bluetoothCode = 102

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
            isClickable = true
            isFocusable = true

            setOnClickListener {
                startListening()
            }

            setOnLongClickListener {
                speak(
                    "Background wake word is not configured yet. " +
                    "Please tap me to talk."
                )
                true
            }
        }

        val buttonSize = (190 * resources.displayMetrics.density).toInt()

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

            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                tts?.setLanguage(Locale.UK)
            }

            ttsReady = true
            speak("Hello. SIMMY is ready.")
        }
    }

    private fun startListening() {
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                microphoneCode
            )
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            speak("This phone version does not support Android on-device speech recognition through this feature.")
            return
        }

        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            speak(
                "Offline speech recognition is not available. " +
                "Please install an offline English speech recognition " +
                "language in your phone settings."
            )
            return
        }

        try {
            recognizer?.destroy()

            recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)

            recognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        speak("I'm listening.")
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        speak("I couldn't understand. Please try again.")
                        destroyRecognizer()
                    }

                    override fun onResults(results: Bundle?) {
                        val matches = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        val command = matches?.firstOrNull()

                        destroyRecognizer()

                        if (command.isNullOrBlank()) {
                            speak("I didn't hear a command.")
                        } else {
                            handleCommand(command.lowercase(Locale.ROOT))
                        }
                    }

                    override fun onPartialResults(results: Bundle?) {}
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
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            }

            recognizer?.startListening(intent)

        } catch (e: Exception) {
            destroyRecognizer()
            speak("I could not start offline listening.")
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
                val message = MessageNotificationService.latestMessage
                if (message.isNullOrBlank()) {
                    speak("I don't have a recent message notification.")
                } else {
                    speak("Your latest message says: $message")
                }
            }

            command.contains("bluetooth") -> {
                openBluetooth()
            }

            command.startsWith("call ") -> {
                findContactAndCall(command.removePrefix("call ").trim())
            }

            command.startsWith("text ") && command.contains(" saying ") -> {
                prepareSms(command)
            }

            command.startsWith("sms ") && command.contains(" saying ") -> {
                prepareSms(command)
            }

            command.contains("whatsapp") && command.contains(" saying ") -> {
                prepareWhatsApp(command)
            }

            command.contains("what time") ||
            command == "time" ||
            command.contains("tell me the time") -> {
                val time = SimpleDateFormat("h:mm a", Locale.UK).format(Date())
                speak("The time is $time")
            }

            command.contains("what date") ||
            command == "date" ||
            command.contains("today's date") -> {
                val date = SimpleDateFormat(
                    "EEEE, d MMMM yyyy",
                    Locale.UK
                ).format(Date())
                speak("Today is $date")
            }

            command.contains("open ") ||
            command.startsWith("launch ") -> {
                val appName = command
                    .replaceFirst(Regex("^open\\s+"), "")
                    .replaceFirst(Regex("^launch\\s+"), "")
                    .trim()

                openInstalledApp(appName)
            }

            command.contains("youtube") -> {
                openInstalledApp("YouTube")
            }

            command.contains("camera") -> {
                openCamera()
            }

            command.contains("settings") -> {
                openIntent(Intent(Settings.ACTION_SETTINGS))
            }

            else -> {
                speak("I don't know that command yet. Try saying open WhatsApp or open YouTube.")
            }
        }
    }

    private fun openInstalledApp(spokenName: String) {
        try {
            val apps = packageManager.getInstalledApplications(
                PackageManager.GET_META_DATA
            )

            val search = spokenName
                .lowercase(Locale.ROOT)
                .replace(Regex("[^a-z0-9 ]"), "")
                .trim()

            val match = apps.firstOrNull { app ->
                val label = packageManager
                    .getApplicationLabel(app)
                    .toString()
                    .lowercase(Locale.ROOT)

                label == search
            } ?: apps.firstOrNull { app ->
                val label = packageManager
                    .getApplicationLabel(app)
                    .toString()
                    .lowercase(Locale.ROOT)

                label.contains(search) || search.contains(label)
            }

            if (match == null) {
                speak("I couldn't find $spokenName on your phone.")
                return
            }

            val launchIntent = packageManager.getLaunchIntentForPackage(
                match.packageName
            )

            if (launchIntent == null) {
                speak("I found $spokenName, but Android cannot open it.")
                return
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launchIntent)
            speak("Opening $spokenName.")

        } catch (e: Exception) {
            speak("I couldn't open $spokenName.")
        }
    }

    private fun openCamera() {
        try {
            val intent = Intent("android.media.action.IMAGE_CAPTURE")
            openIntent(intent)
        } catch (_: Exception) {
            speak("I couldn't open the camera.")
        }
    }

    private fun openBluetooth() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
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

    private fun prepareWhatsApp(command: String) {
        val before = command.substringBefore(" saying ")
        val message = command.substringAfter(" saying ").trim()

        val name = before
            .replace("whatsapp", "")
            .replace("send", "")
            .replace("message", "")
            .replace(Regex("\\bto\\b"), "")
            .trim()

        if (name.isBlank() || message.isBlank()) {
            speak("Say WhatsApp, contact name, saying, then your message.")
            return
        }

        findContact(name) { phone ->
            if (phone.isNullOrBlank()) {
                speak("I couldn't find that contact.")
                return@findContact
            }

            val number = phone.filter { it.isDigit() || it == '+' }
            val encodedMessage = Uri.encode(message)

            openIntent(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://wa.me/$number?text=$encodedMessage")
                )
            )

            speak("Your WhatsApp message is ready. Please check it and press send.")
        }
    }

    private fun prepareSms(command: String) {
        val before = command.substringBefore(" saying ")
        val message = command.substringAfter(" saying ").trim()

        val name = before
            .replaceFirst(Regex("^(text|sms)\\s+"), "")
            .replace(Regex("\\bto\\b"), "")
            .trim()

        if (name.isBlank() || message.isBlank()) {
            speak("Say text, contact name, saying, then your message.")
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
            speak("Your SMS is ready. Please check it and press send.")
        }
    }

    private fun findContactAndCall(name: String) {
        findContact(name) { phone ->
            if (phone.isNullOrBlank()) {
                speak("I couldn't find that contact.")
            } else {
                speak("Opening the phone dialler.")
                openIntent(
                    Intent(
                        Intent.ACTION_DIAL,
                        Uri.parse("tel:${Uri.encode(phone)}")
                    )
                )
            }
        }
    }

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
            speak("Please allow SIMMY to access your contacts, then repeat your command.")
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

            callback(null)

        } catch (e: Exception) {
            callback(null)
        }
    }

    private fun openIntent(intent: Intent) {
        try {
            if (intent.resolveActivity(packageManager) != null) {
                startActivity(intent)
            } else {
                speak("That feature or app is not available.")
            }
        } catch (e: Exception) {
            speak("Android could not open that.")
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun speak(message: String) {
        if (ttsReady) {
            tts?.speak(
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

        val granted = grantResults.firstOrNull() ==
            PackageManager.PERMISSION_GRANTED

        when (requestCode) {
            microphoneCode -> {
                if (granted) {
                    speak("Microphone permission granted. Tap me to talk.")
                } else {
                    speak("Microphone permission is needed.")
                }
            }

            contactsCode -> {
                speak(
                    if (granted) "Contact permission granted. Please repeat your command."
                    else "Contact permission was not granted."
                )
            }

            bluetoothCode -> {
                if (granted) {
                    openBluetooth()
                } else {
                    speak("Bluetooth permission was not granted.")
                }
            }
        }
    }

    override fun onDestroy() {
        destroyRecognizer()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}

class MessageNotificationService :
    android.service.notification.NotificationListenerService() {

    override fun onNotificationPosted(
        sbn: android.service.notification.StatusBarNotification?
    ) {
        if (sbn == null || sbn.packageName == packageName) return

        val extras = sbn.notification.extras

        val title = extras.getCharSequence(
            android.app.Notification.EXTRA_TITLE
        )?.toString().orEmpty()

        val message = (
            extras.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(android.app.Notification.EXTRA_TEXT)
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
