
package com.simmy.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackgroundWakeService : Service(), RecognitionListener,
    TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.simmy.assistant.START_WAKE"
        const val ACTION_STOP = "com.simmy.assistant.STOP_WAKE"

        private const val CHANNEL_ID = "simmy_background_channel"
        private const val NOTIFICATION_ID = 2001

        @Volatile
        var isRunning = false
    }

    private val handler = Handler(Looper.getMainLooper())

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var textToSpeech: TextToSpeech? = null

    private var ttsReady = false
    private var awaitingCommand = false
    private var modelLoading = false

    private var timerRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                if (!isRunning) {
                    try {
                        startForegroundNotification()
                        isRunning = true

                        textToSpeech = TextToSpeech(this, this)

                        loadVoskModel()

                    } catch (e: Exception) {
                        showMessage("SIMMY could not start: ${e.message}")
                        stopSelf()
                    }
                }
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SIMMY Background Listening",
                NotificationManager.IMPORTANCE_LOW
            )

            channel.description = "SIMMY voice assistant listening"

            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE)
                    as NotificationManager

            manager.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val notificationIntent = Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SIMMY is active")
            .setContentText("Say Hi S to talk to SIMMY")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun loadVoskModel() {
        if (modelLoading) return
        modelLoading = true

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showMessage("Please allow microphone permission.")
            stopSelf()
            return
        }

        StorageService.unpack(
            this,
            "model-en-us",
            "model",
            { loadedModel ->
                modelLoading = false

                if (!isRunning) {
                    loadedModel.close()
                    return@unpack
                }

                model = loadedModel

                startVoskListening()

            },
            { exception ->
                modelLoading = false

                showMessage(
                    "Could not load English model: ${exception.message}"
                )

                stopSelf()
            }
        )
    }

    private fun startVoskListening() {
        if (!isRunning) return

        try {
            val loadedModel = model ?: return

            speechService?.stop()
            speechService?.shutdown()
            speechService = null

            val recognizer = Recognizer(loadedModel, 16000.0f)

            speechService = SpeechService(
                recognizer,
                16000.0f
            )

            speechService?.startListening(this)

            speak("SIMMY is ready. Say Hi S.")

        } catch (e: Exception) {
            showMessage("Listening error: ${e.message}")
            stopSelf()
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = textToSpeech?.setLanguage(Locale.US)

            if (result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                ttsReady = true

                textToSpeech?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {

                        override fun onStart(utteranceId: String?) {}

                        override fun onDone(utteranceId: String?) {
                            handler.post {
                                resumeListening()
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            handler.post {
                                resumeListening()
                            }
                        }
                    }
                )
            }
        }
    }

    private fun speak(message: String) {
        if (!isRunning) return

        if (!ttsReady || textToSpeech == null) {
            return
        }

        speechService?.setPause(true)

        textToSpeech?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "SIMMY_${System.currentTimeMillis()}"
        )
    }

    private fun resumeListening() {
        if (!isRunning) return

        try {
            speechService?.setPause(false)
        } catch (_: Exception) {
        }
    }

    private fun showMessage(message: String) {
        handler.post {
            Toast.makeText(
                applicationContext,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onResult(hypothesis: String?) {
        processVoskResult(hypothesis)
    }

    override fun onPartialResult(hypothesis: String?) {
        // Wait for a complete spoken phrase before processing commands.
    }

    override fun onFinalResult(hypothesis: String?) {
        processVoskResult(hypothesis)
    }

    override fun onError(exception: Exception?) {
        showMessage("SIMMY listening error: ${exception?.message}")
    }

    override fun onTimeout() {
        // Vosk normally continues listening through SpeechService.
    }

    private fun processVoskResult(json: String?) {
        if (!isRunning || json.isNullOrBlank()) return

        val words = try {
            JSONObject(json).optString("text", "")
                .trim()
                .lowercase(Locale.ROOT)
        } catch (_: Exception) {
            ""
        }

        if (words.isBlank()) return

        processRecognizedWords(words)
    }

    private fun processRecognizedWords(words: String) {
        val wakeWords = listOf(
            "hi s",
            "hi see",
            "hi c",
            "hi ess",
            "hey s",
            "hello s",
            "high s"
        )

        val wakeWord = wakeWords.firstOrNull {
            words.contains(it)
        }

        if (wakeWord != null) {
            val command = words.substringAfter(wakeWord).trim()

            if (command.isNotBlank()) {
                awaitingCommand = false
                executeCommand(command)
            } else {
                awaitingCommand = true
                speak("Yes, I'm listening.")
            }

            return
        }

        if (awaitingCommand) {
            awaitingCommand = false
            executeCommand(words)
        }
    }

    private fun executeCommand(command: String) {
        val text = command.lowercase(Locale.ROOT)

        when {
            text.contains("time") -> {
                val time = SimpleDateFormat(
                    "h:mm a",
                    Locale.getDefault()
                ).format(Date())

                speak("The time is $time")
            }

            text.contains("date") ||
                text.contains("today") -> {

                val date = SimpleDateFormat(
                    "EEEE, d MMMM yyyy",
                    Locale.getDefault()
                ).format(Date())

                speak("Today is $date")
            }

            text.contains("read messages") ||
                text.contains("my messages") -> {

                val message = MessageNotificationService.latestMessage

                if (message.isNullOrBlank()) {
                    speak("I don't have any new messages.")
                } else {
                    speak(message)
                }
            }

            text.contains("bluetooth") -> {
                speak("Opening Bluetooth settings.")
                openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            }

            text.contains("settings") -> {
                speak("Opening settings.")
                openSettings(Settings.ACTION_SETTINGS)
            }

            text.contains("camera") -> {
                speak("Opening camera.")
                openApp("com.sec.android.app.camera")
            }

            text.contains("youtube") -> {
                speak("Opening YouTube.")
                openApp("com.google.android.youtube")
            }

            text.contains("timer") -> {
                startTimer(text)
            }

            text.startsWith("call ") ||
                text.startsWith("phone ") -> {

                val name = text
                    .removePrefix("call ")
                    .removePrefix("phone ")
                    .trim()

                makeCall(name)
            }

            text.startsWith("text ") ||
                text.startsWith("send sms ") -> {

                val details = text
                    .removePrefix("send sms ")
                    .removePrefix("text ")
                    .trim()

                sendText(details)
            }

            text.contains("whatsapp") -> {
                speak("Opening WhatsApp.")
                openApp("com.whatsapp")
            }

            text.startsWith("open ") -> {
                openNamedApp(text.removePrefix("open ").trim())
            }

            text.contains("stop listening") ||
                text.contains("goodbye") ||
                text.contains("go to sleep") -> {

                speak("Goodbye.")

                handler.postDelayed({
                    stopSelf()
                }, 2000)
            }

            else -> {
                speak("Sorry, I don't understand that command yet.")
            }
        }
    }

    private fun startTimer(command: String) {
        val number = Regex("\\d+")
            .find(command)
            ?.value
            ?.toLongOrNull()

        if (number == null || number <= 0) {
            speak("Please tell me how many minutes.")
            return
        }

        val seconds = when {
            command.contains("second") -> number
            command.contains("hour") -> number * 3600
            else -> number * 60
        }

        timerRunnable?.let {
            handler.removeCallbacks(it)
        }

        timerRunnable = Runnable {
            if (isRunning) {
                speak("Your timer is finished.")
            }
        }

        handler.postDelayed(
            timerRunnable!!,
            seconds * 1000
        )

        speak("Timer started.")
    }

    private fun makeCall(name: String) {
        if (name.isBlank()) {
            speak("Please say the contact name.")
            return
        }

        val number = findContactNumber(name)

        if (number.isNullOrBlank()) {
            speak("I couldn't find that contact.")
            return
        }

        try {
            val intent = Intent(
                Intent.ACTION_DIAL,
                Uri.parse("tel:$number")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            startActivity(intent)
            speak("Opening the phone.")

        } catch (_: Exception) {
            speak("I couldn't open the phone.")
        }
    }

    private fun findContactNumber(name: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            speak("Please allow contacts permission first.")
            return null
        }

        return try {
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(0)
                } else {
                    null
                }
            }

        } catch (_: Exception) {
            null
        }
    }

    private fun sendText(details: String) {
        val parts = details.split(" ", limit = 2)

        if (parts.size < 2) {
            speak("Please say the contact name and message.")
            return
        }

        val name = parts[0]
        val message = parts[1]
        val number = findContactNumber(name)

        if (number.isNullOrBlank()) {
            speak("I couldn't find that contact.")
            return
        }

        try {
            val intent = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("smsto:$number")
            ).apply {
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            startActivity(intent)
            speak("Opening your messages.")

        } catch (_: Exception) {
            speak("I couldn't open your messages.")
        }
    }

    private fun openSettings(action: String) {
        try {
            val intent = Intent(action).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            startActivity(intent)

        } catch (_: Exception) {
            speak("I couldn't open those settings.")
        }
    }

    private fun openApp(packageName: String) {
        try {
            val intent =
                packageManager.getLaunchIntentForPackage(packageName)

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                speak("I couldn't find that app.")
            }

        } catch (_: Exception) {
            speak("I couldn't open that app.")
        }
    }

    private fun openNamedApp(name: String) {
        try {
            val packages = packageManager.getInstalledPackages(0)

            val match = packages.firstOrNull { info ->
                val label = info.applicationInfo
                    ?.loadLabel(packageManager)
                    ?.toString()
                    ?.lowercase(Locale.ROOT)

                label == name || label?.contains(name) == true
            }

            val packageName = match?.packageName

            if (packageName != null) {
                speak("Opening $name.")
                openApp(packageName)
            } else {
                speak("I couldn't find $name.")
            }

        } catch (_: Exception) {
            speak("I couldn't find that app.")
        }
    }

    override fun onDestroy() {
        isRunning = false
        awaitingCommand = false
        modelLoading = false

        timerRunnable?.let {
            handler.removeCallbacks(it)
        }

        handler.removeCallbacksAndMessages(null)

        try {
            speechService?.stop()
            speechService?.shutdown()
            speechService = null

            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null

            model?.close()
            model = null

        } catch (_: Exception) {
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        super.onDestroy()
    }
}
