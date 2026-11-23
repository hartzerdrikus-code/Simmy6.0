
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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class BackgroundWakeService : Service(), TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.simmy.assistant.START_WAKE"
        const val ACTION_STOP = "com.simmy.assistant.STOP_WAKE"

        private const val CHANNEL_ID = "simmy_background_channel"
        private const val NOTIFICATION_ID = 2001

        @Volatile
        var isRunning = false
    }

    private val handler = Handler(Looper.getMainLooper())

    private var speechRecognizer: SpeechRecognizer? = null
    private var textToSpeech: TextToSpeech? = null

    private var ttsReady = false
    private var listening = false
    private var awaitingCommand = false

    private var timerRunnable: Runnable? = null
    private var listenRunnable: Runnable? = null

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
                        initializeSpeech()
                    } catch (e: Exception) {
                        isRunning = false
                        Toast.makeText(
                            this,
                            "SIMMY could not start: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
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
            ).apply {
                description = "SIMMY voice assistant listening service"
            }

            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

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

    private fun initializeSpeech() {
        textToSpeech = TextToSpeech(this, this)

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(
                this,
                "Speech recognition is not available on this device",
                Toast.LENGTH_LONG
            ).show()
            stopSelf()
            return
        }

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

        speechRecognizer?.setRecognitionListener(
            object : RecognitionListener {

                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    listening = true
                }

                override fun onBeginningOfSpeech() {}

                override fun onRmsChanged(rmsdB: Float) {}

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    listening = false
                }

                override fun onError(error: Int) {
                    listening = false

                    if (isRunning) {
                        startListeningSoon(6000)
                    }
                }

                override fun onResults(results: android.os.Bundle?) {
                    listening = false

                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                    val spokenText = matches?.firstOrNull()

                    if (!spokenText.isNullOrBlank()) {
                        processRecognizedWords(spokenText.lowercase(Locale.ROOT))
                    } else {
                        startListeningSoon(2000)
                    }
                }

                override fun onPartialResults(
                    partialResults: android.os.Bundle?
                ) {}

                override fun onEvent(
                    eventType: Int,
                    params: android.os.Bundle?
                ) {}
            }
        )
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
                            if (isRunning) {
                                startListeningSoon(1000)
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            if (isRunning) {
                                startListeningSoon(2000)
                            }
                        }
                    }
                )

                speak("SIMMY is ready. Say Hi S.")
            }
        }
    }

    private fun speak(message: String) {
        if (!ttsReady || textToSpeech == null) {
            startListeningSoon(1500)
            return
        }

        textToSpeech?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "SIMMY_${System.currentTimeMillis()}"
        )
    }

    private fun startListeningSoon(delay: Long) {
        if (!isRunning) return

        listenRunnable?.let { handler.removeCallbacks(it) }

        listenRunnable = Runnable {
            if (!isRunning || listening || !ttsReady) return@Runnable

            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return@Runnable
            }

            try {
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-US")
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                    putExtra(
                        RecognizerIntent.EXTRA_PREFER_OFFLINE,
                        false
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                        30000L
                    )
                    putExtra(
                        RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                        30000L
                    )
                }

                speechRecognizer?.startListening(intent)
            } catch (e: Exception) {
                listening = false
                startListeningSoon(6000)
            }
        }

        handler.postDelayed(listenRunnable!!, delay)
    }

    private fun processRecognizedWords(words: String) {

        val wakeWords = listOf(
            "hi s",
            "hi see",
            "hi c",
            "hi ess",
            "hey s",
            "hello s"
        )

        val wakeWord = wakeWords.firstOrNull { words.contains(it) }

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
            return
        }

        startListeningSoon(1000)
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

            text.contains("date") || text.contains("today") -> {
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
                openSettings(
                    android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                )
                speak("Opening Bluetooth settings.")
            }

            text.contains("settings") -> {
                openSettings(android.provider.Settings.ACTION_SETTINGS)
                speak("Opening settings.")
            }

            text.contains("camera") -> {
                openApp("com.sec.android.app.camera")
            }

            text.contains("youtube") -> {
                openApp("com.google.android.youtube")
            }

            text.contains("timer") -> {
                startTimer(text)
            }

            text.startsWith("call ") ||
            text.startsWith("phone ") -> {
                makeCall(text.removePrefix("call ").removePrefix("phone ").trim())
            }

            text.startsWith("text ") ||
            text.startsWith("send sms ") -> {
                sendText(text.removePrefix("send sms ").removePrefix("text ").trim())
            }

            text.contains("whatsapp") -> {
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
                }, 1500)
            }

            else -> {
                speak("Sorry, I don't understand that command yet.")
            }
        }
    }

    private fun startTimer(command: String) {
        val number = Regex("\\d+").find(command)?.value?.toLongOrNull()

        if (number == null || number <= 0) {
            speak("Please tell me how many minutes.")
            return
        }

        val seconds = when {
            command.contains("second") -> number
            command.contains("hour") -> number * 3600
            else -> number * 60
        }

        timerRunnable?.let { handler.removeCallbacks(it) }

        timerRunnable = Runnable {
            if (isRunning) {
                speak("Your timer is finished.")
            }
        }

        handler.postDelayed(timerRunnable!!, seconds * 1000)

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

        val intent = Intent(
            Intent.ACTION_DIAL,
            Uri.parse("tel:$number")
        ).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        startActivity(intent)
        speak("Opening the phone.")
    }

    private fun findContactNumber(name: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
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
        } catch (e: Exception) {
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

        val intent = Intent(
            Intent.ACTION_SENDTO,
            Uri.parse("smsto:$number")
        ).apply {
            putExtra("sms_body", message)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        startActivity(intent)
        speak("Opening your messages.")
    }

    private fun openSettings(action: String) {
        try {
            startActivity(
                Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            speak("I couldn't open those settings.")
        }
    }

    private fun openApp(packageName: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)

            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                speak("I couldn't find that app.")
            }
        } catch (e: Exception) {
            speak("I couldn't open that app.")
        }
    }

    private fun openNamedApp(name: String) {
        val packages = packageManager.getInstalledPackages(0)

        val match = packages.firstOrNull { info ->
            val label = info.applicationInfo?.loadLabel(packageManager)
                ?.toString()
                ?.lowercase(Locale.ROOT)

            label == name || (label?.contains(name) == true)
        }

        val packageName = match?.packageName

        if (packageName != null) {
            openApp(packageName)
        } else {
            speak("I couldn't find $name.")
        }
    }

    override fun onDestroy() {
        isRunning = false
        listening = false
        awaitingCommand = false

        listenRunnable?.let { handler.removeCallbacks(it) }
        timerRunnable?.let { handler.removeCallbacks(it) }

        handler.removeCallbacksAndMessages(null)

        try {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null

            textToSpeech?.stop()
            textToSpeech?.shutdown()
            textToSpeech = null
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
