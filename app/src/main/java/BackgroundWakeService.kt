
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

        @Volatile
        var isRunning = false
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
    private var pendingSpeech: String? = null
    private var waitingForCommand = false
    private var listening = false
    private var destroying = false

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

        if (!isRunning) {

            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                stopSelf()
                return START_NOT_STICKY
            }

            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification()
                )

                isRunning = true

                setupRecognizer()

                startListeningSoon(1500)

            } catch (e: Exception) {
                isRunning = false
                stopSelf()
            }
        }

        return START_STICKY
    }

    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                CHANNEL_ID,
                "SIMMY background listening",
                NotificationManager.IMPORTANCE_LOW
            )

            channel.description =
                "SIMMY is listening for its wake phrase"

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
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop",
                stopPendingIntent
            )
            .build()
    }

    override fun onInit(status: Int) {

        if (status == TextToSpeech.SUCCESS) {

            var result = tts?.setLanguage(
                Locale("en", "ZA")
            )

            if (result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                result = tts?.setLanguage(Locale.UK)
            }

            ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                    result != TextToSpeech.LANG_NOT_SUPPORTED &&
                    result != null &&
                    result >= TextToSpeech.LANG_AVAILABLE

            if (ttsReady) {

                tts?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {

                        override fun onStart(id: String?) {}

                        override fun onDone(id: String?) {
                            handler.post {
                                startListeningSoon(500)
                            }
                        }

                        override fun onError(id: String?) {
                            handler.post {
                                startListeningSoon(700)
                            }
                        }
                    }
                )

                val pending = pendingSpeech
                pendingSpeech = null

                if (pending != null) {
                    speak(pending)
                }
            }
        }
    }

    private fun setupRecognizer() {

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Speech recognition is not available on this phone.")
            return
        }

        recognizer?.destroy()

        recognizer = SpeechRecognizer.createSpeechRecognizer(this)

        recognizer?.setRecognitionListener(
            object : RecognitionListener {

                override fun onReadyForSpeech(params: Bundle?) {
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

                    if (!destroying && isRunning) {
                        startListeningSoon(1200)
                    }
                }

                override fun onResults(results: Bundle?) {

                    listening = false

                    val matches = results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                    val words = matches?.firstOrNull()
                        ?.lowercase(Locale.getDefault())
                        ?.replace(Regex("[^a-z0-9 ]"), " ")
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()

                    if (!words.isNullOrBlank()) {

                        val wasWaiting = waitingForCommand

                        processRecognizedWords(words)

                        if (wasWaiting) {
                            return
                        }

                        val wakeDetected = wakePhrases.any {
                            words.contains(it)
                        }

                        if (!wakeDetected) {
                            startListeningSoon(700)
                        }

                    } else {
                        startListeningSoon(700)
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
                stopSelf()
                return@Runnable
            }

            try {

                val intent = Intent(
                    RecognizerIntent.ACTION_RECOGNIZE_SPEECH
                ).apply {

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE,
                        "en-ZA"
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE,
                        "en-ZA"
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_MAX_RESULTS,
                        5
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                        true
                    )

                    // Do not force offline recognition.
                    // This allows Android to use its available
                    // online speech recognition service.
                    putExtra(
                        RecognizerIntent.EXTRA_PREFER_OFFLINE,
                        false
                    )
                }

                listening = true
                recognizer?.startListening(intent)

            } catch (e: Exception) {

                listening = false

                startListeningSoon(2000)
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

    private fun handleCommand(command: String) {

        when {

            command.contains("stop listening") ||
            command.contains("turn yourself off") -> {

                speak("Okay. I am stopping background listening.")

                handler.postDelayed({
                    stopSelf()
                }, 2500)
            }

            command.contains("stop talking") -> {

                tts?.stop()

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
            command.contains("today's date") ||
            command.contains("what is the date") -> {

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

                callContact(
                    command.removePrefix("call ").trim()
                )
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

                speak(
                    "I heard you, but I do not know that command yet."
                )
            }
        }
    }

    private fun setTimer(command: String) {

        val match = Regex(
            """(\d+)\s*(seconds?|minutes?|hours?)"""
        ).find(command)

        if (match == null) {

            speak(
                "Please say set timer for 5 minutes, for example."
            )

            return
        }

        val amount = match.groupValues[1].toLongOrNull() ?: return
        val unit = match.groupValues[2]

        val multiplier = when {

            unit.startsWith("hour") ->
                60L * 60L * 1000L

            unit.startsWith("minute") ->
                60L * 1000L

            else ->
                1000L
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

    private fun speak(message: String) {

        if (!ttsReady || tts == null) {

            pendingSpeech = message

            return
        }

        tts?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "SIMMY_${System.currentTimeMillis()}"
        )
    }

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

    private fun findContactNumber(name: String): String? {

        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {

            speak(
                "Please allow contacts permission in SIMMY settings."
            )

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

            speak(
                "Say text, the contact name, saying, and your message."
            )

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

        val content = command
            .removePrefix("whatsapp ")
            .trim()

        val parts = content.split(" saying ", limit = 2)

        if (parts.size != 2) {

            speak(
                "Say WhatsApp, the contact name, saying, and your message."
            )

            return
        }

        val number = findContactNumber(parts[0].trim())

        if (number.isNullOrBlank()) {

            speak("I could not find that contact.")

            return
        }

        val cleanNumber = number.filter {
            it.isDigit()
        }

        val url = Uri.parse(
            "https://wa.me/$cleanNumber?text=${Uri.encode(parts[1].trim())}"
        )

        speak("Opening WhatsApp.")

        openActivity(
            Intent(Intent.ACTION_VIEW, url)
        )
    }

    override fun onDestroy() {

        destroying = true
        isRunning = false

        handler.removeCallbacksAndMessages(null)

        timerRunnable = null
        listenRunnable = null

        try {

            recognizer?.cancel()
            recognizer?.destroy()

        } catch (_: Exception) {
        }

        recognizer = null

        tts?.stop()
        tts?.shutdown()
        tts = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }
}
