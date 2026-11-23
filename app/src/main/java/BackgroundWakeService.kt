
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
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import java.util.concurrent.Executors

class BackgroundWakeService : Service(),
    RecognitionListener,
    TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.simmy.assistant.START_WAKE"
        const val ACTION_STOP = "com.simmy.assistant.STOP_WAKE"

        private const val CHANNEL_ID = "simmy_background_channel"
        private const val NOTIFICATION_ID = 2001
        private const val MODEL_NAME = "vosk-model-small-en-us-0.15"

        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

        @Volatile
        var isRunning = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var textToSpeech: TextToSpeech? = null

    @Volatile
    private var modelLoading = false

    private var ttsReady = false
    private var awaitingCommand = false
    private var listeningStarted = false
    private var readyMessageSpoken = false
    private var speaking = false
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
                        startForegroundNotification("Starting SIMMY...")
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

    private fun buildNotification(message: String): Notification {

        val notificationIntent = Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SIMMY is active")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundNotification(message: String) {

        val notification = buildNotification(message)

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

    private fun updateNotification(message: String) {

        if (!isRunning) return

        val manager =
            getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    // MODEL DOWNLOAD AND EXTRACTION

    private fun loadVoskModel() {

        if (modelLoading) return

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            showMessage("Please allow microphone permission.")
            stopSelf()
            return
        }

        modelLoading = true
        updateNotification("Loading English speech model...")

        executor.execute {

            try {

                val modelDirectory = File(filesDir, MODEL_NAME)

                if (!isModelValid(modelDirectory)) {

                    modelDirectory.deleteRecursively()

                    val zipFile = File(cacheDir, "vosk-english.zip")

                    downloadModel(zipFile)
                    extractModel(zipFile, filesDir)
                    zipFile.delete()
                }

                if (!isModelValid(modelDirectory)) {
                    throw Exception("English model files are incomplete.")
                }

                val loadedModel = Model(modelDirectory.absolutePath)

                handler.post {

                    modelLoading = false

                    if (!isRunning) {
                        loadedModel.close()
                        return@post
                    }

                    model = loadedModel
                    startVoskListening()
                }

            } catch (e: Exception) {

                handler.post {
                    modelLoading = false
                    showMessage("English model failed: ${e.message}")
                    stopSelf()
                }
            }
        }
    }

    private fun isModelValid(directory: File): Boolean {

        return directory.exists() &&
            File(directory, "am").isDirectory &&
            File(directory, "conf").isDirectory &&
            File(directory, "graph").isDirectory
    }

    private fun downloadModel(destination: File) {

        val connection =
            URL(MODEL_URL).openConnection() as HttpURLConnection

        try {

            connection.connectTimeout = 30000
            connection.readTimeout = 120000
            connection.requestMethod = "GET"
            connection.connect()

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("Download failed. HTTP ${connection.responseCode}")
            }

            BufferedInputStream(connection.inputStream).use { input ->

                FileOutputStream(destination).use { output ->

                    val buffer = ByteArray(16384)

                    while (true) {

                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException()
                        }

                        val count = input.read(buffer)

                        if (count == -1) break

                        output.write(buffer, 0, count)
                    }

                    output.flush()
                }
            }

        } finally {
            connection.disconnect()
        }
    }

    private fun extractModel(
        zipFile: File,
        destination: File
    ) {

        val destinationPath =
            destination.canonicalPath + File.separator

        ZipInputStream(
            BufferedInputStream(zipFile.inputStream())
        ).use { zip ->

            var entry = zip.nextEntry

            while (entry != null) {

                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException()
                }

                val outputFile = File(destination, entry.name)
                val outputPath = outputFile.canonicalPath

                if (!outputPath.startsWith(destinationPath)) {
                    throw Exception("Invalid model archive.")
                }

                if (entry.isDirectory) {

                    outputFile.mkdirs()

                } else {

                    outputFile.parentFile?.mkdirs()

                    BufferedOutputStream(
                        FileOutputStream(outputFile)
                    ).use { output ->

                        val buffer = ByteArray(16384)

                        while (true) {

                            val count = zip.read(buffer)

                            if (count == -1) break

                            output.write(buffer, 0, count)
                        }

                        output.flush()
                    }
                }

                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    // VOSK LISTENING

    private fun startVoskListening() {

        if (!isRunning) return

        try {

            val loadedModel = model ?: return

            recognizer = Recognizer(loadedModel, 16000.0f)

            speechService = SpeechService(
                recognizer,
                16000.0f
            )

            speechService?.startListening(this)

            listeningStarted = true
            updateNotification("Listening for Hi S...")
            announceReady()

        } catch (e: Exception) {
            showMessage("SIMMY listening error: ${e.message}")
            stopSelf()
        }
    }

    private fun announceReady() {

        if (!isRunning ||
            !ttsReady ||
            !listeningStarted ||
            readyMessageSpoken
        ) return

        readyMessageSpoken = true
        speak("SIMMY is ready. Say Hi S.")
    }

    // TEXT TO SPEECH

    override fun onInit(status: Int) {

        if (status == TextToSpeech.SUCCESS) {

            val result = textToSpeech?.setLanguage(Locale.US)

            if (result != TextToSpeech.LANG_MISSING_DATA &&
                result != TextToSpeech.LANG_NOT_SUPPORTED
            ) {

                ttsReady = true

                textToSpeech?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {

                        override fun onStart(utteranceId: String?) {
                            speaking = true
                        }

                        override fun onDone(utteranceId: String?) {
                            handler.post {
                                speaking = false
                                resumeListening()
                            }
                        }

                        override fun onError(utteranceId: String?) {
                            handler.post {
                                speaking = false
                                resumeListening()
                            }
                        }
                    }
                )

                announceReady()

            } else {
                showMessage("English voice data is unavailable.")
            }

        } else {
            showMessage("SIMMY voice is unavailable.")
        }
    }

    private fun speak(message: String) {

        if (!isRunning) return

        if (!ttsReady || textToSpeech == null) {
            showMessage("SIMMY voice is not ready yet.")
            return
        }

        speechService?.setPause(true)

        val result = textToSpeech?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "SIMMY_${System.currentTimeMillis()}"
        )

        if (result == TextToSpeech.ERROR) {
            showMessage("SIMMY could not speak. Check phone text-to-speech settings.")
            resumeListening()
        }
    }

    private fun resumeListening() {

        if (!isRunning) return

        try {
            speechService?.setPause(false)
            updateNotification("Listening for Hi S...")
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

    // RECOGNITION CALLBACKS

    override fun onResult(hypothesis: String?) {
        processVoskResult(hypothesis)
    }

    override fun onPartialResult(hypothesis: String?) {
        // Complete phrases are processed in onResult.
    }

    override fun onFinalResult(hypothesis: String?) {
        processVoskResult(hypothesis)
    }

    override fun onError(exception: Exception?) {
        showMessage("SIMMY microphone error: ${exception?.message}")
    }

    override fun onTimeout() {
        // Continuous listening.
    }

    private fun processVoskResult(json: String?) {

        if (!isRunning || json.isNullOrBlank()) return

        val words = try {
            JSONObject(json)
                .optString("text", "")
                .trim()
                .lowercase(Locale.ROOT)
        } catch (_: Exception) {
            ""
        }

        if (words.isBlank()) return

        handler.post {

            if (!isRunning) return@post

            updateNotification("I heard: $words")
            showMessage("SIMMY heard: $words")

            processRecognizedWords(words)
        }
    }

    // WAKE WORD DETECTION

    private fun processRecognizedWords(words: String) {

        val normalised = words
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val wakeWords = listOf(
            "i s",
            "eye s",
            "hi s",
            "hi see",
            "high sea",
            "hey see",
            "hello s",
            "hi ess",
            "high s",
            "hey s",
            "hi c",
            "i see",
            "i c",
            "ice"
        )

        val wakeWord = wakeWords.firstOrNull {
            normalised.contains(it)
        }

        if (wakeWord != null) {

            val command = normalised
                .substringAfter(wakeWord)
                .trim()

            if (command.isNotBlank()) {
                awaitingCommand = false
                executeCommand(command)
            } else {
                awaitingCommand = true
                updateNotification("SIMMY is waiting for your command.")
                speak("Yes, I'm listening.")
            }

            return
        }

        if (awaitingCommand) {
            awaitingCommand = false
            executeCommand(normalised)
        }
    }

    // VOICE COMMANDS

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
            if (isRunning) speak("Your timer is finished.")
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
                if (it.moveToFirst()) it.getString(0) else null
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

    // CLEANUP

    override fun onDestroy() {

        isRunning = false
        awaitingCommand = false
        modelLoading = false
        speaking = false

        timerRunnable?.let {
            handler.removeCallbacks(it)
        }

        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()

        try {

            speechService?.cancel()
            speechService?.shutdown()
            speechService = null

            recognizer?.close()
            recognizer = null

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
