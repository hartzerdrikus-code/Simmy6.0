
package com.simmy.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

            setOnLongClickListener {
                startBackgroundListening()
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
            val result = tts.setLanguage(simmyLanguage)

            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                tts.setLanguage(Locale.UK)
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

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Speech recognition is not available.")
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
                        val words = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )
                        words?.firstOrNull()?.let {
                            handleCommand(it.lowercase(Locale.ROOT))
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
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            speechRecognizer?.startListening(intent)

        } catch (e: Exception) {
            speak("I could not start listening.")
        }
    }

    private fun handleCommand(command: String) {
        when {
            command.contains("hi simmy") ||
            command.contains("hello") -> {
                speak("Hello! I am SIMMY. How can I help?")
            }

            command.contains("read my messages") ||
            command.contains("read messages") -> {
                val message = MessageNotificationService.latestMessage
                if (message.isNullOrBlank()) {
                    speak("I have no recent message notifications.")
                } else {
                    speak("Your latest message says: $message")
                }
            }

            command.contains("bluetooth") -> {
                speak("Opening Bluetooth settings.")
                openIntent(
                    Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                )
            }

            command.startsWith("call ") -> {
                findContactAndCall(command.removePrefix("call ").trim())
            }

            command.contains("whatsapp") &&
            command.contains("saying") -> {
                prepareWhatsAppMessage(command)
            }

            (command.startsWith("text ") ||
             command.startsWith("sms ")) &&
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

            else -> {
                speak("I heard you, but I do not understand that command yet.")
            }
        }
    }

    private fun startBackgroundListening() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            checkMicrophonePermission()
            return
        }

        try {
            val intent = Intent(
                this,
                BackgroundWakeService::class.java
            ).apply {
                action = BackgroundWakeService.ACTION_START
            }

            ContextCompat.startForegroundService(this, intent)

            Toast.makeText(
                this,
                "SIMMY background listening started.",
                Toast.LENGTH_LONG
            ).show()

        } catch (e: Exception) {
            speak("I could not start background listening.")
        }
    }

    private fun prepareWhatsAppMessage(command: String) {
        val before = command.substringBefore("saying")
        val message = command.substringAfter("saying").trim()

        val name = before
            .replace("whatsapp", "")
            .replace("send", "")
            .replace("message", "")
            .replace(Regex("\\bto\\b"), "")
            .trim()

        if (name.isBlank() || message.isBlank()) {
            speak("Say WhatsApp, contact name, saying, and your message.")
            return
        }

        findContact(name) { phone ->
            try {
                val encoded = URLEncoder.encode(message, "UTF-8")

                if (!phone.isNullOrBlank()) {
                    val number = phone.filter {
                        it.isDigit() || it == '+'
                    }

                    openIntent(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://wa.me/$number?text=$encoded")
                        )
                    )
                } else {
                    val intent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        setPackage("com.whatsapp")
                        putExtra(Intent.EXTRA_TEXT, message)
                    }
                    openIntent(intent)
                }

                speak("Your WhatsApp message is ready. Please review and send it.")

            } catch (e: Exception) {
                speak("I could not open WhatsApp.")
            }
        }
    }

    private fun prepareSmsMessage(command: String) {
        val before = command.substringBefore("saying")
        val message = command.substringAfter("saying").trim()

        val name = before
            .replace("text", "")
            .replace("sms", "")
            .replace(Regex("\\bto\\b"), "")
            .trim()

        if (name.isBlank() || message.isBlank()) {
            speak("Say text, contact name, saying, and your message.")
            return
        }

        findContact(name) { phone ->
            if (phone.isNullOrBlank()) {
                speak("I could not find that contact.")
                return@findContact
            }

            val intent = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("smsto:${Uri.encode(phone)}")
            ).apply {
                putExtra("sms_body", message)
            }

            openIntent(intent)
            speak("Your SMS is ready. Please review and send it.")
        }
    }

    private fun findContactAndCall(name: String) {
        findContact(name) { phone ->
            if (phone.isNullOrBlank()) {
                speak("I could not find that contact.")
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
            speak("Please allow SIMMY to access your contacts.")
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

    private fun openAppOrWebsite(
        packageName: String,
        website: String
    ) {
        val launch = packageManager.getLaunchIntentForPackage(packageName)

        if (launch != null) {
            startActivity(launch)
        } else {
            openIntent(
                Intent(Intent.ACTION_VIEW, Uri.parse(website))
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
            speak("I could not open that.")
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
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            speak("SIMMY microphone is ready.")
        }

        if (requestCode == contactsRequestCode &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            speak("Contact access granted. Please repeat your command.")
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

class BackgroundWakeService : Service() {

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var stopped = false

    companion object {
        const val ACTION_START = "com.simmy.assistant.START_WAKE"
        const val ACTION_STOP = "com.simmy.assistant.STOP_WAKE"
        private const val CHANNEL_ID = "simmy_wake_channel"
        private const val NOTIFICATION_ID = 6201
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            stopWakeService()
            return START_NOT_STICKY
        }

        stopped = false

        val notification = makeNotification(
            "Listening for Hi SIMMY"
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startRecognition()

        return START_NOT_STICKY
    }

    private fun startRecognition() {
        if (stopped) return

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            updateNotification("Speech recognition unavailable")
            return
        }

        try {
            recognizer?.destroy()

            recognizer = if (
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(this)
            ) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
            } else {
                SpeechRecognizer.createSpeechRecognizer(this)
            }

            recognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        updateNotification("Listening for Hi SIMMY")
                    }

                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}

                    override fun onError(error: Int) {
                        restartRecognition()
                    }

                    override fun onResults(results: Bundle?) {
                        checkWakeWord(results)
                        restartRecognition()
                    }

                    override fun onPartialResults(results: Bundle?) {
                        checkWakeWord(results)
                    }

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
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-ZA")
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            }

            recognizer?.startListening(intent)

        } catch (e: Exception) {
            restartRecognition()
        }
    }

    private fun checkWakeWord(results: Bundle?) {
        val words = results?.getStringArrayList(
            SpeechRecognizer.RESULTS_RECOGNITION
        )

        val heard = words?.firstOrNull()
            ?.lowercase(Locale.ROOT)
            .orEmpty()

        if (
            heard.contains("hi simmy") ||
            heard.contains("hey simmy")
        ) {
            updateNotification(
                "SIMMY heard you! Tap to open SIMMY."
            )

            val openIntent = Intent(
                this,
                MainActivity::class.java
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                this,
                50,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or
                    PendingIntent.FLAG_IMMUTABLE
            )

            try {
                pendingIntent.send()
            } catch (_: Exception) {
                // Android may prevent opening an activity from the background.
            }
        }
    }

    private fun restartRecognition() {
        if (stopped) return

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!stopped) startRecognition()
        }, 1500)
    }

    private fun makeNotification(message: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)

        val openPendingIntent = PendingIntent.getActivity(
            this,
            1,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(
            this,
            BackgroundWakeService::class.java
        ).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            2,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("SIMMY is active")
            .setContentText(message)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop listening",
                stopPendingIntent
            )
            .build()
    }

    private fun updateNotification(message: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, makeNotification(message))
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SIMMY Background Listening",
                NotificationManager.IMPORTANCE_LOW
            )

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun stopWakeService() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

class MessageNotificationService : android.service.notification.NotificationListenerService() {

    override fun onNotificationPosted(
        sbn: android.service.notification.StatusBarNotification?
    ) {
        if (sbn == null || sbn.packageName == packageName) return

        val extras = sbn.notification.extras

        val title = extras.getCharSequence(
            Notification.EXTRA_TITLE
        )?.toString().orEmpty()

        val message = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
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
