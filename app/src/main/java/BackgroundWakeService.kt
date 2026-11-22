
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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackgroundWakeService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.simmy.assistant.START_WAKE"
        const val ACTION_STOP = "com.simmy.assistant.STOP_WAKE"

        private const val CHANNEL_ID = "simmy_background_channel"
        private const val NOTIFICATION_ID = 2001
        private const val TAG = "SIMMY"
    }

    private val handler = Handler(Looper.getMainLooper())

    private val wakePhrases = listOf(
        "hi s",
        "hi see",
        "hi c",
        "hi ess",
        "hey s",
        "hey see",
        "hello s",
        "hello see"
    )

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private var ttsReady = false
    private var listening = false
    private var waitingForCommand = false
    private var destroying = false
    private var stopAfterSpeaking = false

    private var pendingSpeech: String? = null
    private var listenRunnable: Runnable? = null
    private var timerRunnable: Runnable? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        tts = TextToSpeech(this, this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (isRunning) {
            return START_STICKY
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showError("SIMMY needs microphone permission.")
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val notification = buildNotification()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }

            isRunning = true

            setupRecognizer()

            Log.d(TAG, "Background service started")

        } catch (e: Exception) {
            isRunning = false

            Log.e(TAG, "Service startup failed", e)

            showError(
                "SIMMY startup failed: ${e.javaClass.simpleName}: ${e.message}"
            )

            stopSelf()
        }

        return START_STICKY
    }

    private fun showError(message: String) {
        handler.post {
            Toast.makeText(
                applicationContext,
                message,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "SIMMY background listening",
                NotificationManager.IMPORTANCE_LOW
            )

            channel.description = "SIMMY voice assistant"

            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {

        val stopIntent = Intent(
            this,
            BackgroundWakeService::class.java
        ).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            22,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)

        val openPendingIntent = PendingIntent.getActivity(
            this,
            23,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("SIMMY is active")
            .setContentText("Listening for Hi S")
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    // TEXT TO SPEECH

    override fun onInit(status: Int) {

        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "Text-to-speech initialization failed")
            showError("SIMMY could not start its speaking voice.")
            startListeningSoon(3000)
            return
        }

        val result = tts?.setLanguage(Locale.US)

        ttsReady =
            result != null &&
            result >= TextToSpeech.LANG_AVAILABLE

        if (!ttsReady) {
            Log.e(TAG, "English voice is unavailable")
            showError("Please install an English voice on your phone.")
            startListeningSoon(3000)
            return
        }

        tts?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {

                override fun onStart(id: String?) {
                    Log.d(TAG, "SIMMY speaking")
                }

                override fun onDone(id: String?) {
                    handler.post {
                        if (stopAfterSpeaking) {
                            stopAfterSpeaking = false
                            stopSelf()
                        } else {
                            startListeningSoon(1000)
                        }
                    }
                }

                override fun onError(id: String?) {
                    handler.post {
                        if (stopAfterSpeaking) {
                            stopAfterSpeaking = false
                            stopSelf()
                        } else {
                            startListeningSoon(2000)
                        }
                    }
                }
            }
        )

        val pending = pendingSpeech
        pendingSpeech = null

        if (pending != null) {
            speak(pending)
        } else {
            speak("SIMMY is ready. Say Hi S.")
        }
    }

    private fun speak(message: String) {

        if (!ttsReady || tts == null) {
            pendingSpeech = message
            return
        }

        try {
            tts?.speak(
                message,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "SIMMY_${System.currentTimeMillis()}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Speech output failed", e)
            startListeningSoon(3000)
        }
    }

    // SPEECH RECOGNITION

    private fun setupRecognizer() {

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            showError("Speech recognition is unavailable.")
            return
        }

        try {
            recognizer?.destroy()

            recognizer = SpeechRecognizer.createSpeechRecognizer(this)

            recognizer?.setRecognitionListener(
                object : RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        listening = true
                        Log.d(TAG, "Recognizer ready")
                    }

                    override fun onBeginningOfSpeech() {
                        Log.d(TAG, "Speech detected")
                    }

                    override fun onRmsChanged(rmsdB: Float) {}

                    override fun onBufferReceived(buffer: ByteArray?) {}

                    override fun onEndOfSpeech() {
                        listening = false
                    }

                    override fun onError(error: Int) {
                        listening = false

                        Log.w(TAG, "Recognition error: $error")

                        if (destroying || !isRunning) return

                        // Do not restart immediately. This helps
                        // prevent rapid repeated recognition beeps.
                        startListeningSoon(6000)
                    }

                    override fun onResults(results: Bundle?) {
                        listening = false

                        val matches = results?.getStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION
                        )

                        val words = matches?.firstOrNull()
                            ?.lowercase(Locale.US)
                            ?.replace(Regex("[^a-z0-9 ]"), " ")
                            ?.replace(Regex("\\s+"), " ")
                            ?.trim()

                        Log.d(TAG, "Recognized words: $words")

                        if (words.isNullOrBlank()) {
                            startListeningSoon(2000)
                            return
                        }

                        val wasWaiting = waitingForCommand

                        processRecognizedWords(words)

                        if (!wasWaiting) {
                            val wakeDetected = wakePhrases.any {
                                words.contains(it)
                            }

                            if (!wakeDetected) {
                                startListeningSoon(1500)
                            }
                        }
                    }

                    override fun onPartialResults(
                        partialResults: Bundle?
                    ) {}

                    override fun onEvent(
                        eventType: Int,
                        params: Bundle?
                    ) {}
                }
            )

            // Wait until TTS says SIMMY is ready before listening.
            // If TTS is already ready, start listening now.
            if (ttsReady) {
                startListeningSoon(1000)
            }

        } catch (e: Exception) {
            Log.e(TAG, "Recognizer setup failed", e)
            showError("SIMMY could not prepare speech recognition.")
        }
    }

    private fun startListeningSoon(delay: Long) {

        if (destroying || !isRunning) return

        listenRunnable?.let {
            handler.removeCallbacks(it)
        }

        val runnable = Runnable {

            if (destroying || !isRunning || listening) {
                return@Runnable
            }

            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                showError("SIMMY needs microphone permission.")
                stopSelf()
                return@Runnable
            }

            try {
                val speechIntent = Intent(
                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                ).apply {

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE,
                        "en-US"
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                        "en-US"
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_MAX_RESULTS,
                        5
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                        false
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_PREFER_OFFLINE,
                        false
                    )

                    // Ask the recognition service to allow
                    // longer pauses before ending a session.
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        30000L
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        30000L
                    )
                }

                listening = true

                recognizer?.startListening(speechIntent)

                Log.d(TAG, "Listening session started")

            } catch (e: Exception) {
                listening = false

                Log.e(TAG, "Could not start listening", e)

                startListeningSoon(6000)
            }
        }

        listenRunnable = runnable
        handler.postDelayed(runnable, delay)
    }

    private fun processRecognizedWords(words: String) {

        if (waitingForCommand) {
            waitingForCommand = false
            handleCommand(words)
            return
        }

        val wakePhrase = wakePhrases.firstOrNull {
            words.contains(it)
        } ?: return

        val command = words.substringAfter(wakePhrase).trim()

        if (command.isNotBlank()) {
            handleCommand(command)
        } else {
            waitingForCommand = true
            speak("Yes, I'm listening. What can I do for you?")
        }
    }

    // COMMANDS

    private fun handleCommand(command: String) {

        when {

            command.contains("stop listening") ||
            command.contains("turn yourself off") -> {
                stopAfterSpeaking = true
                speak("Okay. I am stopping background listening.")
            }

            command.contains("stop talking") -> {
                tts?.stop()
                speak("Okay.")
            }

            command.contains("what time") ||
            command == "time" -> {
                val time = SimpleDateFormat(
                    "h:mm a",
                    Locale.US
                ).format(Date())

                speak("The time is $time")
            }

            command.contains("what date") ||
            command.contains("today's date") ||
            command.contains("what is the date") -> {
                val date = SimpleDateFormat(
                    "EEEE, d MMMM yyyy",
                    Locale.US
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
                openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            }

            command.contains("open settings") -> {
                speak("Opening settings.")
                openSettings(Settings.ACTION_SETTINGS)
            }

            command.contains("open camera") -> {
                speak("Opening camera.")
                openActivity(
                    Intent("android.media.action.IMAGE_CAPTURE")
                )
            }

            command.contains("open youtube") ||
            command.contains("launch youtube") -> {
                openApp(
                    "com.google.android.youtube",
                    "Opening YouTube."
                )
            }

            command.startsWith("set timer") ||
            command.startsWith("start timer") ||
            command.contains("timer for") -> {
                setTimer(command)
            }

            command.startsWith("call ") -> {
                callContact(command.removePrefix("call ").trim())
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
                speak("I heard you, but I do not know that command yet.")
            }
        }
    }

    // TIMER

    private fun setTimer(command: String) {

        val match = Regex(
            """(\d+)\s*(seconds?|minutes?|hours?)"""
        ).find(command)

        if (match == null) {
            speak("Please say set timer for 5 minutes, for example.")
            return
        }

        val amount = match.groupValues[1].toLongOrNull() ?: return
        val unit = match.groupValues[2]

        val multiplier = when {
            unit.startsWith("hour") -> 3600000L
            unit.startsWith("minute") -> 60000L
            else -> 1000L
        }

        val duration = amount * multiplier

        timerRunnable?.let {
            handler.removeCallbacks(it)
        }

        timerRunnable = Runnable {
            speak("Your timer is finished.")
            showTimerNotification()
        }

        handler.postDelayed(timerRunnable!!, duration)

        speak("Timer set for $amount $unit.")
    }

    private fun showTimerNotification() {

        val notification = NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("SIMMY timer")
            .setContentText("Your timer is finished.")
            .setAutoCancel(true)
            .build()

        val manager = getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        manager.notify(2002, notification)
    }

    // PHONE SETTINGS AND APPS

    private fun openSettings(action: String) {
        try {
            val intent = Intent(action).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            speak("I could not open those settings.")
        }
    }

    private fun openActivity(intent: Intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            speak("I could not open that.")
        }
    }

    private fun openApp(
        packageName: String,
        message: String
    ) {
        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName)

        if (launchIntent == null) {
            speak("That app is not installed.")
            return
        }

        speak(message)
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        openActivity(launchIntent)
    }

    private fun openAppByName(appName: String) {

        val apps = packageManager.getInstalledApplications(
            PackageManager.GET_META_DATA
        )

        val match = apps.firstOrNull {
            packageManager.getApplicationLabel(it)
                .toString()
                .contains(appName, ignoreCase = true)
        }

        if (match == null) {
            speak("I could not find that app.")
            return
        }

        val launchIntent =
            packageManager.getLaunchIntentForPackage(match.packageName)

        if (launchIntent != null) {
            speak("Opening $appName.")
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            openActivity(launchIntent)
        } else {
            speak("I could not open that app.")
        }
    }

    // CONTACTS AND MESSAGES

    private fun findContactNumber(name: String): String? {

        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            speak("Please allow contacts permission in SIMMY settings.")
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

        openActivity(
            Intent(
                Intent.ACTION_DIAL,
                Uri.parse("tel:${Uri.encode(number)}")
            )
        )
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

        val number = findContactNumber(parts[0].trim())

        if (number.isNullOrBlank()) {
            speak("I could not find that contact.")
            return
        }

        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("smsto:${Uri.encode(number)}")
        ).apply {
            putExtra("sms_body", parts[1].trim())
        }

        speak("Opening your text message.")
        openActivity(intent)
    }

    private fun sendWhatsAppCommand(command: String) {

        val content = command.removePrefix("whatsapp ").trim()
        val parts = content.split(" saying ", limit = 2)

        if (parts.size != 2) {
            speak("Say WhatsApp, the contact name, saying, and your message.")
            return
        }

        val number = findContactNumber(parts[0].trim())

        if (number.isNullOrBlank()) {
            speak("I could not find that contact.")
            return
        }

        val cleanNumber = number.filter { it.isDigit() }

        val url = Uri.parse(
            "https://wa.me/$cleanNumber?text=${Uri.encode(parts[1].trim())}"
        )

        speak("Opening WhatsApp.")
        openActivity(Intent(Intent.ACTION_VIEW, url))
    }

    // CLEANUP

    override fun onDestroy() {

        destroying = true
        isRunning = false

        handler.removeCallbacksAndMessages(null)

        timerRunnable = null
        listenRunnable = null

        try {
            recognizer?.cancel()
            recognizer?.destroy()
        } catch (e: Exception) {
            Log.e(TAG, "Recognizer cleanup failed", e)
        }

        recognizer = null

        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            Log.e(TAG, "TTS cleanup failed", e)
        }

        tts = null
        ttsReady = false

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
