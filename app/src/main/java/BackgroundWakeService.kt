package com.simmy.assistant

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.ImageView
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
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

class BackgroundWakeService : Service(),
    RecognitionListener,
    TextToSpeech.OnInitListener {

    companion object {
        const val ACTION_START = "com.simmy.assistant.START_WAKE"
        const val ACTION_STOP = "com.simmy.assistant.STOP_WAKE"

        private const val CHANNEL_ID = "simmy_background_channel"
        private const val APP_LAUNCH_CHANNEL_ID = "simmy_app_launch_channel"
        private const val NOTIFICATION_ID = 2001
        private const val OVERLAY_NOTIFICATION_ID = 7001
        private const val MODEL_NAME = "vosk-model-small-en-us-0.15"

        private const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip"

        private const val ONLINE_LANGUAGE = "en-ZA"

        private const val NOTES_PACKAGE = "com.samsung.android.app.notes"
        private const val URECORDER_PACKAGE =
            "voicerecorder.audiorecorder.voice"

        private const val CONVERSATION_TIMEOUT_MS = 20_000L

        @Volatile
        var isRunning = false
    }

    private data class LaunchableApp(
        val label: String,
        val packageName: String
    )

    private data class ContactMatch(
        val name: String,
        val number: String
    )

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var textToSpeech: TextToSpeech? = null

    private var onlineSpeechRecognizer: SpeechRecognizer? = null
    private var onlineRecognitionActive = false
    private var startOnlineRecognitionAfterSpeech = false

    private var windowManager: WindowManager? = null
    private var overlayView: ImageView? = null
    private var overlayPermissionNotificationShown = false

    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var modelLoading = false

    private var ttsReady = false
    private var awaitingCommand = false
    private var awaitingNoteContent = false
    private var listeningStarted = false
    private var readyMessageSpoken = false
    private var speaking = false
    private var recoveryScheduled = false

    // CONVERSATION MODE

    private var conversationMode = false
    private var conversationTimeoutRunnable: Runnable? = null

    private var timerRunnable: Runnable? = null
    private var timerEndTimeMillis: Long? = null

    private var lastProcessedText = ""
    private var lastProcessedTime = 0L

    private var currentSpeechLanguage = "en-ZA"

    private val allowedSpeechLanguages = arrayListOf(
        "en-ZA",
        "af-ZA"
    )

    private val overlayPermissionChecker = object : Runnable {
        override fun run() {
            if (!isRunning) return

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this@BackgroundWakeService)
            ) {
                createSimmyOverlay()
            } else {
                if (!overlayPermissionNotificationShown) {
                    overlayPermissionNotificationShown = true
                    showOverlayPermissionNotification()
                }

                handler.postDelayed(this, 2000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
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
                        acquireWakeLock()
                        startOverlayPermissionChecker()
                        textToSpeech = TextToSpeech(this, this)
                        loadVoskModel()
                    } catch (e: Exception) {
                        showMessage("SIMMY could not start: ${e.message}")
                        stopSelf()
                    }
                } else {
                    startOverlayPermissionChecker()
                }
            }
        }

        return START_STICKY
    }

    // CPU WAKE LOCK

    private fun acquireWakeLock() {
        try {
            val powerManager =
                getSystemService(Context.POWER_SERVICE) as PowerManager

            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SIMMY::BackgroundListening"
            )

            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire()
            }
        } catch (e: Exception) {
            showMessage("SIMMY could not keep the processor awake: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {
        }

        wakeLock = null
    }

    // NOTIFICATIONS

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            val channel = NotificationChannel(
                CHANNEL_ID,
                "SIMMY Background Listening",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "SIMMY voice assistant listening"
            manager.createNotificationChannel(channel)

            val appChannel = NotificationChannel(
                APP_LAUNCH_CHANNEL_ID,
                "SIMMY App Opening",
                NotificationManager.IMPORTANCE_HIGH
            )
            appChannel.description = "SIMMY app opening notifications"
            manager.createNotificationChannel(appChannel)
        }
    }

    private fun buildNotification(message: String): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
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
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun showOverlayPermissionNotification() {
        try {
            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )

            val settingsPendingIntent = PendingIntent.getActivity(
                this,
                OVERLAY_NOTIFICATION_ID,
                settingsIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = Notification.Builder(
                this,
                APP_LAUNCH_CHANNEL_ID
            )
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Allow SIMMY to open apps")
                .setContentText("Allow display over other apps.")
                .setContentIntent(settingsPendingIntent)
                .setAutoCancel(true)
                .build()

            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            manager.notify(OVERLAY_NOTIFICATION_ID, notification)
        } catch (_: Exception) {
            showMessage("Could not open SIMMY permission settings.")
        }
    }

    private fun showActionNotification(
        title: String,
        message: String,
        intent: Intent,
        requestCode: Int
    ) {
        try {
            val pendingIntent = PendingIntent.getActivity(
                this,
                requestCode,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = Notification.Builder(
                this,
                APP_LAUNCH_CHANNEL_ID
            )
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(message)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .build()

            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            manager.notify(requestCode, notification)
        } catch (_: Exception) {
            showMessage("Could not create the notification.")
        }
    }

    private fun showAppLaunchNotification(
        packageName: String,
        appLabel: String
    ) {
        val launchIntent =
            packageManager.getLaunchIntentForPackage(packageName) ?: return

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )

        val requestCode = (packageName.hashCode() and 0x7fffffff) + 3000

        showActionNotification(
            "SIMMY: Open $appLabel",
            "Tap here to open $appLabel.",
            launchIntent,
            requestCode
        )
    }

    // FLOATING SIMMY LOGO BUBBLE

    private fun startOverlayPermissionChecker() {
        handler.removeCallbacks(overlayPermissionChecker)
        handler.post(overlayPermissionChecker)
    }

    private fun createSimmyOverlay() {
        if (!isRunning || overlayView != null) return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(this)
        ) return

        try {
            val density = resources.displayMetrics.density
            val bubbleSize = (76 * density).toInt()
            val borderWidth = (3 * density).toInt()

            val bubbleBackground = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(15, 20, 45))
                setStroke(
                    borderWidth,
                    Color.rgb(0, 220, 255)
                )
            }

            val appIcon = packageManager.getApplicationIcon(packageName)

            val bubble = ImageView(this).apply {
                setImageDrawable(appIcon)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = bubbleBackground
                clipToOutline = true
                setPadding(0, 0, 0, 0)
                elevation = 12f
                contentDescription = "SIMMY floating assistant"

                setOnClickListener {
                    try {
                        val intent = Intent(
                            this@BackgroundWakeService,
                            MainActivity::class.java
                        ).apply {
                            flags =
                                Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        startActivity(intent)
                    } catch (_: Exception) {
                        showMessage("Could not open SIMMY.")
                    }
                }
            }

            val overlayType =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                }

            val params = WindowManager.LayoutParams(
                bubbleSize,
                bubbleSize,
                overlayType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = 20
                y = 150
            }

            windowManager?.addView(bubble, params)
            overlayView = bubble
            overlayPermissionNotificationShown = false

            val manager =
                getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            manager.cancel(OVERLAY_NOTIFICATION_ID)

            updateNotification(
                "Listening for Mamma, Hi, Simon, Make a note, or Start recording."
            )

        } catch (e: Exception) {
            showMessage("SIMMY floating indicator failed: ${e.message}")
        }
    }

    private fun removeSimmyOverlay() {
        try {
            overlayView?.let {
                windowManager?.removeView(it)
            }
        } catch (_: Exception) {
        }
        overlayView = null
    }

    // VOSK MODEL DOWNLOAD

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
        updateNotification("Loading English offline speech model...")

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

    private fun extractModel(zipFile: File, destination: File) {
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

    // VOSK LISTENING AND RECOVERY

    private fun startVoskListening() {
        if (!isRunning || onlineRecognitionActive || speaking) return

        try {
            val loadedModel = model ?: return

            listeningStarted = false

            recognizer = Recognizer(loadedModel, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(this)

            listeningStarted = true
            updateNotification(
                "Listening for Mamma, Hi, Simon, Make a note, or Start recording."
            )
            announceReady()
        } catch (e: Exception) {
            listeningStarted = false
            showMessage("SIMMY listening error: ${e.message}")
            scheduleVoskRecovery()
        }
    }

    private fun scheduleVoskRecovery() {
        if (!isRunning ||
            onlineRecognitionActive ||
            speaking ||
            modelLoading ||
            recoveryScheduled
        ) return

        recoveryScheduled = true
        listeningStarted = false

        updateNotification("SIMMY is restarting its microphone...")

        handler.postDelayed({
            recoveryScheduled = false

            if (!isRunning ||
                onlineRecognitionActive ||
                speaking
            ) return@postDelayed

            try {
                speechService?.cancel()
                speechService?.shutdown()
                speechService = null

                recognizer?.close()
                recognizer = null

                startVoskListening()
            } catch (e: Exception) {
                showMessage("SIMMY is trying to restart listening.")
                scheduleVoskRecovery()
            }
        }, 2000)
    }

    private fun announceReady() {
        if (!isRunning ||
            !ttsReady ||
            !listeningStarted ||
            readyMessageSpoken
        ) return

        readyMessageSpoken = true
        speak(
            "SIMMY is ready. Say Mamma, Hi, or Simon, Make a note, or Start recording."
        )
    }

    // CONVERSATION MODE

    private fun startConversationMode() {
        conversationMode = true
        awaitingCommand = false
        refreshConversationTimeout()

        updateNotification(
            "Conversation mode active. SIMMY is listening for your next command."
        )
    }

    private fun refreshConversationTimeout() {
        conversationTimeoutRunnable?.let {
            handler.removeCallbacks(it)
        }

        conversationTimeoutRunnable = Runnable {
            if (!isRunning || !conversationMode) return@Runnable

            conversationMode = false
            awaitingCommand = false
            startOnlineRecognitionAfterSpeech = false

            if (onlineRecognitionActive) {
                onlineRecognitionActive = false
                destroyOnlineRecognizer()
            }

            updateNotification(
                "Conversation ended. Listening for Mamma, Hi, or Simon."
            )

            resumeListening()
        }

        handler.postDelayed(
            conversationTimeoutRunnable!!,
            CONVERSATION_TIMEOUT_MS
        )
    }

    private fun endConversationMode() {
        conversationMode = false
        awaitingCommand = false
        startOnlineRecognitionAfterSpeech = false

        conversationTimeoutRunnable?.let {
            handler.removeCallbacks(it)
        }
        conversationTimeoutRunnable = null

        if (onlineRecognitionActive) {
            onlineRecognitionActive = false
            destroyOnlineRecognizer()
        }

        updateNotification(
            "Conversation ended. Listening for Mamma, Hi, or Simon."
        )
    }

    // TEXT TO SPEECH

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            setSpeechOutputLanguage("en-ZA")

            ttsReady = true

            textToSpeech?.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        speaking = true
                    }

                    override fun onDone(utteranceId: String?) {
                        handler.post {
                            speaking = false

                            if (startOnlineRecognitionAfterSpeech) {
                                startOnlineRecognitionAfterSpeech = false

                                if (conversationMode) {
                                    startOnlineCommandListening()
                                } else {
                                    resumeListening()
                                }
                            } else if (conversationMode &&
                                !onlineRecognitionActive
                            ) {
                                startOnlineCommandListening()
                            } else if (!onlineRecognitionActive) {
                                resumeListening()
                            }
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        handler.post {
                            speaking = false

                            if (startOnlineRecognitionAfterSpeech) {
                                startOnlineRecognitionAfterSpeech = false

                                if (conversationMode) {
                                    startOnlineCommandListening()
                                } else {
                                    resumeListening()
                                }
                            } else if (conversationMode &&
                                !onlineRecognitionActive
                            ) {
                                startOnlineCommandListening()
                            } else if (!onlineRecognitionActive) {
                                resumeListening()
                            }
                        }
                    }
                }
            )

            announceReady()
        } else {
            showMessage("SIMMY voice is unavailable.")
        }
    }

    private fun setSpeechOutputLanguage(languageTag: String) {
        currentSpeechLanguage = languageTag

        val locale = Locale.forLanguageTag(languageTag)
        val result = textToSpeech?.setLanguage(locale)

        if (result == TextToSpeech.LANG_MISSING_DATA ||
            result == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            currentSpeechLanguage = "en-ZA"
            textToSpeech?.setLanguage(Locale("en", "ZA"))
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

            if (conversationMode) {
                startOnlineCommandListening()
            } else {
                resumeListening()
            }
        }
    }

    private fun resumeListening() {
        if (!isRunning || onlineRecognitionActive || conversationMode) return

        try {
            if (speechService == null || !listeningStarted) {
                scheduleVoskRecovery()
            } else {
                speechService?.setPause(false)
                updateNotification("SIMMY is listening.")
            }
        } catch (_: Exception) {
            scheduleVoskRecovery()
        }
    }

    // ONLINE ENGLISH / AFRIKAANS RECOGNITION

    private fun startOnlineCommandListening() {
        if (!isRunning || !conversationMode) return

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            onlineRecognitionActive = false
            awaitingCommand = true
            speak("Online speech recognition is not available. Please try again.")
            return
        }

        try {
            speechService?.setPause(true)

            destroyOnlineRecognizer()
            onlineSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)

            onlineRecognitionActive = true
            awaitingCommand = false
            updateNotification("Conversation mode: listening for your command.")

            onlineSpeechRecognizer?.setRecognitionListener(
                object : android.speech.RecognitionListener {

                    override fun onReadyForSpeech(params: Bundle?) {
                        updateNotification("SIMMY is listening to your command.")
                    }

                    override fun onBeginningOfSpeech() {
                    }

                    override fun onRmsChanged(rmsdB: Float) {
                    }

                    override fun onBufferReceived(buffer: ByteArray?) {
                    }

                    override fun onEndOfSpeech() {
                        updateNotification("SIMMY is understanding your command.")
                    }

                    override fun onError(error: Int) {
                        handler.post {
                            finishOnlineRecognitionWithFallback(error)
                        }
                    }

                    override fun onResults(results: Bundle?) {
                        handler.post {
                            val matches = results?.getStringArrayList(
                                SpeechRecognizer.RESULTS_RECOGNITION
                            )

                            val command = matches
                                ?.firstOrNull()
                                ?.trim()
                                ?.lowercase(Locale.ROOT)

                            onlineRecognitionActive = false
                            destroyOnlineRecognizer()

                            if (!command.isNullOrBlank()) {
                                awaitingCommand = false
                                refreshConversationTimeout()
                                updateNotification("SIMMY heard: $command")

                                val normalisedCommand =
                                    normaliseCommand(command)

                                if (awaitingNoteContent) {
                                    awaitingNoteContent = false
                                    saveNoteInSamsungNotes(normalisedCommand)
                                } else {
                                    executeCommand(normalisedCommand)
                                }
                            } else {
                                awaitingCommand = true
                                speak("I didn't catch that. Please say your command again.")
                            }
                        }
                    }

                    override fun onPartialResults(partialResults: Bundle?) {
                    }

                    override fun onEvent(eventType: Int, params: Bundle?) {
                    }

                    override fun onLanguageDetection(results: Bundle) {
                        if (Build.VERSION.SDK_INT >= 34) {
                            val detectedLanguage = results.getString(
                                SpeechRecognizer.DETECTED_LANGUAGE
                            )

                            handler.post {
                                when (detectedLanguage) {
                                    "af-ZA" -> setSpeechOutputLanguage("af-ZA")
                                    "en-ZA" -> setSpeechOutputLanguage("en-ZA")
                                }

                                if (!detectedLanguage.isNullOrBlank()) {
                                    updateNotification(
                                        "SIMMY detected language: $detectedLanguage"
                                    )
                                }
                            }
                        }
                    }
                }
            )

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                )
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, ONLINE_LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, ONLINE_LANGUAGE)
                putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(
                    RecognizerIntent.EXTRA_PROMPT,
                    "SIMMY is listening. Speak your command."
                )

                if (Build.VERSION.SDK_INT >= 34) {
                    putExtra(
                        RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION,
                        true
                    )

                    putStringArrayListExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                        ArrayList(allowedSpeechLanguages)
                    )

                    putExtra(
                        RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                        RecognizerIntent.LANGUAGE_SWITCH_BALANCED
                    )

                    putStringArrayListExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                        ArrayList(allowedSpeechLanguages)
                    )
                }
            }

            onlineSpeechRecognizer?.startListening(intent)

        } catch (_: Exception) {
            onlineRecognitionActive = false
            destroyOnlineRecognizer()
            awaitingCommand = true
            speak("Online recognition could not start. Please try your command again.")
        }
    }

    private fun finishOnlineRecognitionWithFallback(error: Int) {
        if (!onlineRecognitionActive || !isRunning) return

        onlineRecognitionActive = false
        destroyOnlineRecognizer()
        awaitingCommand = true

        val reason = when (error) {
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "There is a network problem."

            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                "The requested language is not available on the speech service."

            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                "I didn't hear a clear command."

            else -> "Online speech recognition is unavailable."
        }

        updateNotification("Online recognition failed. Please try again.")
        speak("$reason Please say your command again.")
    }

    private fun destroyOnlineRecognizer() {
        try {
            onlineSpeechRecognizer?.cancel()
            onlineSpeechRecognizer?.destroy()
        } catch (_: Exception) {
        }

        onlineSpeechRecognizer = null
    }

    // COMMAND NORMALISATION AND AFRIKAANS PHRASES

    private fun normaliseCommand(command: String): String {
        var text = command.lowercase(Locale.ROOT)
            .replace("'", "")
            .replace("’", "")
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        text = text
            .replace("wi fi", "wifi")
            .replace("you tube", "youtube")
            .replace("face book", "facebook")
            .replace("whats app", "whatsapp")
            .replace("what s app", "whatsapp")
            .replace("blue tooth", "bluetooth")

        val prefixes = listOf(
            "can you please ", "could you please ", "would you please ",
            "will you please ", "please can you ", "please could you ",
            "can you ", "could you ", "would you ", "will you ",
            "i want you to ", "i would like you to ", "i need you to ",
            "i want to ", "i would like to ", "please "
        )
        var removedPrefix: Boolean
        do {
            removedPrefix = false
            for (prefix in prefixes) {
                if (text.startsWith(prefix)) {
                    text = text.removePrefix(prefix).trim()
                    removedPrefix = true
                    break
                }
            }
        } while (removedPrefix)

        val translations = listOf(
            "hoe laat is dit nou" to "what time is it",
            "hoe laat is dit" to "what time is it",
            "wat is die tyd nou" to "what time is it",
            "wat is die tyd" to "what time is it",
            "sê vir my die tyd" to "what time is it",
            "wat is die datum" to "what date is it",
            "watter datum is dit" to "what date is it",
            "watter dag is dit vandag" to "what date is it",
            "watter dag is dit" to "what date is it",
            "vandag se datum" to "what date is it",
            "lees my boodskappe" to "read messages",
            "lees die boodskappe" to "read messages",
            "lees boodskappe" to "read messages",
            "lees my messages" to "read messages",
            "maak n nuwe nota" to "make a note",
            "maak n nota" to "make a note",
            "maak nota" to "make a note",
            "skryf n nota" to "make a note",
            "skryf nota" to "make a note",
            "begin die opname" to "start recording",
            "begin opname" to "start recording",
            "neem n opname" to "start recording",
            "neem opname" to "start recording",
            "begin opneem" to "start recording",
            "hou op om te luister" to "stop listening",
            "hou op luister" to "stop listening",
            "gaan slaap" to "go to sleep",
            "tot siens" to "goodbye",
            "skakel die flitslig aan" to "turn flashlight on",
            "skakel flitslig aan" to "turn flashlight on",
            "sit die flitslig aan" to "turn flashlight on",
            "sit flitslig aan" to "turn flashlight on",
            "skakel flits aan" to "turn flashlight on",
            "skakel die flitslig af" to "turn flashlight off",
            "skakel flitslig af" to "turn flashlight off",
            "sit die flitslig af" to "turn flashlight off",
            "sit flitslig af" to "turn flashlight off",
            "skakel flits af" to "turn flashlight off",
            "maak die volume harder" to "increase volume",
            "sit die volume harder" to "increase volume",
            "volume harder" to "increase volume",
            "maak harder" to "increase volume",
            "maak die volume sagter" to "decrease volume",
            "sit die volume sagter" to "decrease volume",
            "volume sagter" to "decrease volume",
            "maak sagter" to "decrease volume",
            "maak die bluetooth oop" to "open bluetooth",
            "maak bluetooth oop" to "open bluetooth",
            "skakel bluetooth aan" to "open bluetooth",
            "maak die wifi oop" to "open wifi",
            "maak wifi oop" to "open wifi",
            "maak die instellings oop" to "open settings",
            "maak instellings oop" to "open settings",
            "maak die kamera oop" to "open camera",
            "maak kamera oop" to "open camera",
            "maak youtube oop" to "open youtube",
            "maak whatsapp oop" to "open whatsapp",
            "maak facebook oop" to "open facebook",
            "maak instagram oop" to "open instagram",
            "maak tiktok oop" to "open tiktok",
            "maak google oop" to "open google",
            "maak chrome oop" to "open chrome",
            "maak boodskappe oop" to "open messages",
            "maak kontakte oop" to "open contacts",
            "maak gallery oop" to "open gallery",
            "maak kalender oop" to "open calendar"
        )

        for ((afrikaans, english) in translations.sortedByDescending { it.first.length }) {
            if (text == afrikaans) {
                text = english
                break
            }
            if (text.startsWith("$afrikaans ")) {
                text = english + text.removePrefix(afrikaans)
                break
            }
        }

        text = text
            .replace("maak asseblief ", "maak ")
            .replace("maak vir my ", "maak ")
            .replace("maak die ", "maak ")

        Regex("^maak (.+) oop$").find(text)?.let {
            text = "open ${it.groupValues[1]}"
        }
        Regex("^maak oop (.+)$").find(text)?.let {
            text = "open ${it.groupValues[1]}"
        }
        Regex("^open die (.+)$").find(text)?.let {
            text = "open ${it.groupValues[1]}"
        }

        if (text.startsWith("bel ")) {
            text = "call " + text.removePrefix("bel ").trim()
        } else if (text.startsWith("skakel ")) {
            val rest = text.removePrefix("skakel ").trim()
            if (rest.isNotBlank() &&
                !rest.startsWith("flitslig") &&
                !rest.startsWith("die flitslig") &&
                !rest.startsWith("flits ")
            ) {
                text = "call $rest"
            }
        }

        val englishMappings = listOf(
            "tell me the time" to "what time is it",
            "whats the time" to "what time is it",
            "what is the time" to "what time is it",
            "tell me the date" to "what date is it",
            "whats the date" to "what date is it",
            "what is todays date" to "what date is it",
            "open up " to "open ",
            "launch " to "open "
        )
        for ((phrase, replacement) in englishMappings) {
            if (text == phrase.trim()) {
                text = replacement.trim()
                break
            }
            if (text.startsWith(phrase)) {
                text = replacement + text.removePrefix(phrase)
                break
            }
        }

        text = text
            .replace(Regex("^open the "), "open ")
            .replace(Regex("^open my "), "open ")

        val endings = listOf(
            " for me please", " please", " for me",
            " thanks", " thank you"
        )
        var removedEnding: Boolean
        do {
            removedEnding = false
            for (ending in endings) {
                if (text.endsWith(ending)) {
                    text = text.removeSuffix(ending).trim()
                    removedEnding = true
                    break
                }
            }
        } while (removedEnding)

        return text.replace(Regex("\\s+"), " ").trim()
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
    }

    override fun onFinalResult(hypothesis: String?) {
        processVoskResult(hypothesis)
    }

    override fun onError(exception: Exception?) {
        showMessage("SIMMY microphone error. Restarting listening...")
        handler.post {
            scheduleVoskRecovery()
        }
    }

    override fun onTimeout() {
        handler.post {
            scheduleVoskRecovery()
        }
    }

    private fun processVoskResult(json: String?) {
        if (!isRunning || onlineRecognitionActive || json.isNullOrBlank()) return

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
            if (!isRunning || onlineRecognitionActive) return@post

            val now = System.currentTimeMillis()
            if (words == lastProcessedText &&
                now - lastProcessedTime < 1800
            ) return@post

            lastProcessedText = words
            lastProcessedTime = now

            processRecognizedWords(words)
        }
    }

    // WAKE WORDS AND DIRECT ACTION PHRASES

    private fun processRecognizedWords(words: String) {
        val normalised = normaliseCommand(words)

        if (normalised.isBlank()) return

        updateNotification("SIMMY heard: $normalised")

        if (awaitingNoteContent) {
            awaitingNoteContent = false
            saveNoteInSamsungNotes(normalised)
            return
        }

        if (normalised == "make a note" ||
            normalised.startsWith("make a note ")
        ) {
            startMakingNote()
            return
        }

        if (normalised == "start recording" ||
            normalised.startsWith("start recording ")
        ) {
            startVoiceRecording()
            return
        }

        val wakeWords = listOf(
            "mamma",
            "mama",
            "momma",
            "hi",
            "simon"
        )

        val wakeWord = wakeWords.firstOrNull { word ->
            normalised == word || normalised.startsWith("$word ")
        }

        if (wakeWord != null) {
            startConversationMode()

            val command = normalised
                .removePrefix(wakeWord)
                .trim()

            if (command.isNotBlank()) {
                executeCommand(command)
            } else {
                startOnlineRecognitionAfterSpeech = true
                updateNotification("SIMMY is waiting for your command.")
                speak("Yes, I'm listening.")
            }

            return
        }
    }

    // NOTES

    private fun startMakingNote() {
        awaitingCommand = false
        awaitingNoteContent = true
        updateNotification("SIMMY is listening for your note.")
        speak("What would you like me to write?")
    }

    private fun saveNoteInSamsungNotes(noteText: String) {
        if (noteText.isBlank()) {
            speak("I didn't hear your note. Please try again.")
            return
        }

        try {
            val noteIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                setPackage(NOTES_PACKAGE)
                putExtra(Intent.EXTRA_SUBJECT, "SIMMY note")
                putExtra(Intent.EXTRA_TEXT, noteText)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val overlayAllowed =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    Settings.canDrawOverlays(this)

            if (overlayAllowed && overlayView != null) {
                startActivity(noteIntent)
                speak("I've sent your note to Samsung Notes. Please save it there.")
            } else {
                showActionNotification(
                    "SIMMY: Save your note",
                    "Tap here to send your note to Samsung Notes.",
                    noteIntent,
                    8101
                )
                speak("Your note is ready. Tap the notification to open Samsung Notes.")
            }
        } catch (_: Exception) {
            speak("I couldn't send the note to Samsung Notes. Please check that Samsung Notes is installed.")
        }
    }

    // VOICE RECORDER

    private fun startVoiceRecording() {
        awaitingCommand = false

        val launchIntent =
            packageManager.getLaunchIntentForPackage(URECORDER_PACKAGE)

        if (launchIntent == null) {
            speak("I couldn't find your Voice Recorder app.")
            showMessage("Check that URecorder is installed.")
            return
        }

        launchIntent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )

        val overlayAllowed =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(this)

        try {
            if (overlayAllowed && overlayView != null) {
                startActivity(launchIntent)
                speak("Opening Voice Recorder. Tap Record if recording doesn't start automatically.")
            } else {
                showActionNotification(
                    "SIMMY: Open Voice Recorder",
                    "Tap here to open URecorder.",
                    launchIntent,
                    8102
                )
                speak("Voice Recorder is ready. Tap the notification to open it.")
            }
        } catch (_: Exception) {
            showActionNotification(
                "SIMMY: Open Voice Recorder",
                "Tap here to open URecorder.",
                launchIntent,
                8102
            )
            speak("Please tap the notification to open Voice Recorder.")
        }
    }

    // PHONE CONTROLS

    private fun setFlashlight(enabled: Boolean) {
        if (checkSelfPermission(Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            speak("Please allow SIMMY camera permission.")

            try {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (_: Exception) {
                showMessage("Open SIMMY app permissions in phone settings.")
            }
            return
        }

        try {
            val cameraManager =
                getSystemService(Context.CAMERA_SERVICE) as CameraManager

            val cameraId = cameraManager.cameraIdList.firstOrNull { id ->
                cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }

            if (cameraId == null) {
                speak("Your phone flashlight is unavailable.")
                return
            }

            cameraManager.setTorchMode(cameraId, enabled)

            speak(
                if (enabled) "Flashlight switched on."
                else "Flashlight switched off."
            )
        } catch (_: Exception) {
            speak("I couldn't control the flashlight. Please check camera permission.")
        }
    }

    private fun changeVolume(direction: Int) {
        try {
            val audioManager =
                getSystemService(Context.AUDIO_SERVICE) as AudioManager

            audioManager.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                direction,
                AudioManager.FLAG_SHOW_UI
            )

            when (direction) {
                AudioManager.ADJUST_RAISE -> speak("Volume increased.")
                AudioManager.ADJUST_LOWER -> speak("Volume decreased.")
                AudioManager.ADJUST_MUTE -> speak("Media muted.")
                AudioManager.ADJUST_UNMUTE -> speak("Media unmuted.")
            }
        } catch (_: Exception) {
            speak("I couldn't change the volume.")
        }
    }

    private fun setBrightnessFromCommand(command: String) {
        val percentage = Regex("""(\d{1,3})\s*%?""")
            .find(command)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        if (percentage == null || percentage !in 1..100) {
            speak("Please say a brightness percentage between 1 and 100.")
            return
        }

        if (!Settings.System.canWrite(this)) {
            speak("Please allow SIMMY to modify system settings.")

            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:$packageName")
                ).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(intent)
            } catch (_: Exception) {
                showMessage("Allow Modify system settings for SIMMY in phone settings.")
            }
            return
        }

        try {
            val brightness = (percentage * 255) / 100

            Settings.System.putInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness
            )

            speak("Brightness set to $percentage percent.")
        } catch (_: Exception) {
            speak("I couldn't change the brightness.")
        }
    }

    private fun tellBatteryLevel() {
        try {
            val batteryIntent = registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )

            if (batteryIntent == null) {
                speak("I couldn't check the battery.")
                return
            }

            val level = batteryIntent.getIntExtra(
                BatteryManager.EXTRA_LEVEL,
                -1
            )

            val scale = batteryIntent.getIntExtra(
                BatteryManager.EXTRA_SCALE,
                -1
            )

            if (level >= 0 && scale > 0) {
                val percentage = (level * 100) / scale
                speak("Your battery is at $percentage percent.")
            } else {
                speak("I couldn't read the battery level.")
            }
        } catch (_: Exception) {
            speak("I couldn't check your battery level.")
        }
    }

    // MEDIA PLAYBACK CONTROLS

    private fun sendMediaKey(keyCode: Int) {
        try {
            val audioManager =
                getSystemService(Context.AUDIO_SERVICE) as AudioManager

            val downEvent = KeyEvent(
                KeyEvent.ACTION_DOWN,
                keyCode
            )

            val upEvent = KeyEvent(
                KeyEvent.ACTION_UP,
                keyCode
            )

            audioManager.dispatchMediaKeyEvent(downEvent)
            audioManager.dispatchMediaKeyEvent(upEvent)

        } catch (_: Exception) {
            showMessage("SIMMY could not control media playback.")
        }
    }

    private fun pauseMusic() {
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        speak("Pausing music.")
    }

    private fun resumeMusic() {
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
        speak("Resuming music.")
    }

    private fun skipMusic() {
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        speak("Skipping to the next track.")
    }

    // GOOGLE SEARCH

    private fun searchGoogle(query: String) {
        if (query.isBlank()) {
            speak("What would you like me to search for?")
            return
        }

        try {
            val url = "https://www.google.com/search?q=${Uri.encode(query)}"

            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(url)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val chromeIntent = Intent(intent).apply {
                setPackage("com.android.chrome")
            }

            try {
                startActivity(chromeIntent)
            } catch (_: Exception) {
                startActivity(intent)
            }

            speak("Searching Google for $query.")

        } catch (_: Exception) {
            speak("I couldn't open Google search.")
        }
    }

    // YOUTUBE SEARCH AND PLAYBACK

    private fun playOnYouTube(
        song: String,
        useYouTubeMusic: Boolean
    ) {
        if (song.isBlank()) {
            speak("Please tell me which song you want to play.")
            return
        }

        try {
            val url = if (useYouTubeMusic) {
                "https://music.youtube.com/search?q=${Uri.encode(song)}"
            } else {
                "https://www.youtube.com/results?search_query=${Uri.encode(song)}"
            }

            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(url)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            val targetPackage = if (useYouTubeMusic) {
                "com.google.android.apps.youtube.music"
            } else {
                "com.google.android.youtube"
            }

            val appIntent = Intent(intent).apply {
                setPackage(targetPackage)
            }

            try {
                startActivity(appIntent)
            } catch (_: Exception) {
                startActivity(intent)
            }

            speak("I found $song. I'll try to start playback.")

            handler.postDelayed({
                if (isRunning) {
                    sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
                }
            }, 3500)

        } catch (_: Exception) {
            speak("I couldn't open YouTube.")
        }
    }

    // CONTACT LOOKUP HELPERS

    private fun normaliseContactName(name: String): String {
        return name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun getPhoneContacts(): List<ContactMatch>? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            speak("Please allow contacts permission first.")
            return null
        }

        val contacts = mutableListOf<ContactMatch>()

        try {
            val cursor = contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                ),
                null,
                null,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
            )

            cursor?.use {
                val numberIndex = it.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                )
                val nameIndex = it.getColumnIndex(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME
                )

                if (numberIndex < 0 || nameIndex < 0) {
                    return contacts
                }

                while (it.moveToNext()) {
                    val number = it.getString(numberIndex)?.trim().orEmpty()
                    val name = it.getString(nameIndex)?.trim().orEmpty()

                    if (name.isNotBlank() && number.isNotBlank()) {
                        contacts.add(
                            ContactMatch(
                                name = name,
                                number = number
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {
            speak("I couldn't read your contacts.")
            return null
        }

        return contacts
    }

    private fun findContact(name: String): ContactMatch? {
        val requested = normaliseContactName(name)

        if (requested.isBlank()) return null

        val contacts = getPhoneContacts() ?: return null

        contacts.firstOrNull {
            normaliseContactName(it.name) == requested
        }?.let {
            return it
        }

        val matchingContacts = contacts.filter {
            val contactName = normaliseContactName(it.name)

            contactName.isNotBlank() &&
                (
                    contactName.contains(requested) ||
                        requested.contains(contactName)
                    )
        }

        return matchingContacts.maxByOrNull {
            normaliseContactName(it.name).length
        }
    }

    private fun findContactNumber(name: String): String? {
        return findContact(name)?.number
    }

    // WHATSAPP CONTACT CHAT

    private fun openWhatsAppContact(name: String) {
        if (name.isBlank()) {
            speak("Please say the contact name.")
            return
        }

        val contact = findContact(name)

        if (contact == null) {
            speak("I couldn't find that contact.")
            return
        }

        val number = contact.number

        try {
            var digits = number.filter { it.isDigit() }

            if (digits.startsWith("0") && digits.length >= 10) {
                digits = "27" + digits.drop(1)
            }

            if (digits.isBlank()) {
                speak("That contact does not have a valid phone number.")
                return
            }

            val url = "https://wa.me/$digits"

            val intent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse(url)
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            try {
                val whatsappIntent = Intent(intent).apply {
                    setPackage("com.whatsapp")
                }
                startActivity(whatsappIntent)
            } catch (_: Exception) {
                try {
                    val businessIntent = Intent(intent).apply {
                        setPackage("com.whatsapp.w4b")
                    }
                    startActivity(businessIntent)
                } catch (_: Exception) {
                    startActivity(intent)
                }
            }

            speak("Opening WhatsApp chat for ${contact.name}.")

        } catch (_: Exception) {
            speak("I couldn't open that WhatsApp chat.")
        }
    }

    // VOICE COMMANDS

    private fun executeCommand(command: String) {
        val text = normaliseCommand(command)

        when {
            text == "make a note" ||
                text.startsWith("make a note ") -> {
                startMakingNote()
            }

            text == "start recording" ||
                text.startsWith("start recording ") -> {
                startVoiceRecording()
            }

            text == "end conversation" ||
                text == "stop conversation" ||
                text == "exit conversation" -> {
                endConversationMode()
                speak("Conversation ended. Say Mamma or Simon when you need me.")
            }

            text.contains("stop listening") ||
                text.contains("goodbye") ||
                text.contains("go to sleep") -> {
                conversationMode = false
                conversationTimeoutRunnable?.let {
                    handler.removeCallbacks(it)
                }
                speak("Goodbye.")
                handler.postDelayed({
                    stopSelf()
                }, 2000)
            }

            text.contains("time left") ||
                text.contains("time remaining") ||
                text.contains("how much longer") ||
                text.contains("check timer") ||
                text.contains("timer remaining") ||
                text.contains("remaining on timer") -> {
                tellTimerRemaining()
            }

            text == "pause" ||
                text.contains("pause music") ||
                text.contains("pause song") ||
                text.contains("stop music") -> {
                pauseMusic()
            }

            text == "resume" ||
                text == "play" ||
                text.contains("resume music") ||
                text.contains("continue music") ||
                text.contains("continue playing") ||
                text.contains("play music") -> {
                resumeMusic()
            }

            text.contains("skip song") ||
                text.contains("next song") ||
                text.contains("next track") ||
                text == "skip" -> {
                skipMusic()
            }

            text.startsWith("play ") &&
                (text.contains(" on youtube music") ||
                    text.contains(" on youtube")) -> {

                val song = text
                    .removePrefix("play ")
                    .replace(" on youtube music", "")
                    .replace(" on youtube", "")
                    .trim()

                val music = text.contains("youtube music")
                playOnYouTube(song, music)
            }

            text.startsWith("search google for ") -> {
                searchGoogle(text.removePrefix("search google for ").trim())
            }

            text.startsWith("google search for ") -> {
                searchGoogle(text.removePrefix("google search for ").trim())
            }

            text.startsWith("search chrome for ") -> {
                searchGoogle(text.removePrefix("search chrome for ").trim())
            }

            text.startsWith("search for ") -> {
                searchGoogle(text.removePrefix("search for ").trim())
            }

            text.startsWith("google ") -> {
                searchGoogle(text.removePrefix("google ").trim())
            }

            text.startsWith("whatsapp ") -> {
                openWhatsAppContact(
                    text.removePrefix("whatsapp ").trim()
                )
            }

            text.startsWith("open whatsapp chat ") -> {
                openWhatsAppContact(
                    text.removePrefix("open whatsapp chat ").trim()
                )
            }

            text.contains("timer") -> {
                startTimer(text)
            }

            text.contains("flashlight") ||
                text.contains("torch") -> {
                when {
                    text.contains("off") -> setFlashlight(false)
                    text.contains("on") -> setFlashlight(true)
                    else -> speak("Please say turn flashlight on or turn flashlight off.")
                }
            }

            text.contains("increase volume") ||
                text.contains("volume up") ||
                text.contains("turn volume up") -> {
                changeVolume(AudioManager.ADJUST_RAISE)
            }

            text.contains("decrease volume") ||
                text.contains("volume down") ||
                text.contains("turn volume down") -> {
                changeVolume(AudioManager.ADJUST_LOWER)
            }

            text.contains("unmute") -> {
                changeVolume(AudioManager.ADJUST_UNMUTE)
            }

            text.contains("mute") -> {
                changeVolume(AudioManager.ADJUST_MUTE)
            }

            text.contains("brightness") -> {
                setBrightnessFromCommand(text)
            }

            text.contains("battery") -> {
                tellBatteryLevel()
            }

            text.contains("bluetooth") -> {
                speak("Opening Bluetooth settings.")
                openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
            }

            text.contains("wifi") -> {
                speak("Opening Wi-Fi settings.")
                openSettings(Settings.ACTION_WIFI_SETTINGS)
            }

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

            text == "settings" ||
                text == "open settings" -> {
                speak("Opening settings.")
                openSettings(Settings.ACTION_SETTINGS)
            }

            text == "camera" ||
                text == "open camera" -> {
                openNamedApp("camera")
            }

            text == "youtube" ||
                text == "open youtube" -> {
                openNamedApp("youtube")
            }

            text == "youtube music" ||
                text == "open youtube music" -> {
                openNamedApp("youtube music")
            }

            text == "whatsapp" ||
                text == "open whatsapp" -> {
                openNamedApp("whatsapp")
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

            text.startsWith("open ") -> {
                openNamedApp(text.removePrefix("open ").trim())
            }

            else -> {
                speak("Sorry, I don't understand that command yet.")
            }
        }
    }

    // TIMER

    private fun parseTimerNumber(value: String): Long? {
        val words = value.replace("-", " ").trim().split(Regex("\\s+"))
        if (words.isEmpty()) return null

        val smallNumbers = mapOf(
            "zero" to 0L, "one" to 1L, "two" to 2L, "three" to 3L,
            "four" to 4L, "five" to 5L, "six" to 6L, "seven" to 7L,
            "eight" to 8L, "nine" to 9L, "ten" to 10L,
            "eleven" to 11L, "twelve" to 12L, "thirteen" to 13L,
            "fourteen" to 14L, "fifteen" to 15L, "sixteen" to 16L,
            "seventeen" to 17L, "eighteen" to 18L, "nineteen" to 19L
        )
        val tens = mapOf(
            "twenty" to 20L, "thirty" to 30L, "forty" to 40L,
            "fifty" to 50L, "sixty" to 60L, "seventy" to 70L,
            "eighty" to 80L, "ninety" to 90L
        )

        if (words.size == 1) {
            words[0].toLongOrNull()?.let { return it }
            return smallNumbers[words[0]] ?: tens[words[0]]
        }

        if (words.size == 2 && words[0] in tens) {
            return tens[words[0]]!! + (smallNumbers[words[1]] ?: return null)
        }

        return null
    }

    private fun startTimer(command: String) {
        val numberPattern =
            "(?:\\d+|zero|one|two|three|four|five|six|seven|eight|nine|ten|" +
                "eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen|" +
                "eighteen|nineteen|twenty|thirty|forty|fifty|sixty|seventy|" +
                "eighty|ninety)(?:[ -](?:one|two|three|four|five|six|seven|" +
                "eight|nine))?"

        val timePattern = Regex(
            "\\b($numberPattern)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\b"
        )

        var totalSeconds = 0L
        var foundDuration = false

        for (match in timePattern.findAll(command)) {
            val amount = parseTimerNumber(match.groupValues[1]) ?: continue
            val unit = match.groupValues[2].lowercase(Locale.ROOT)

            val multiplier = when {
                unit.startsWith("hour") || unit.startsWith("hr") -> 3600L
                unit.startsWith("second") || unit.startsWith("sec") -> 1L
                else -> 60L
            }

            if (amount > 604800L / multiplier) {
                speak("Please choose a timer of seven days or less.")
                return
            }

            totalSeconds += amount * multiplier
            foundDuration = true

            if (totalSeconds > 604800L) {
                speak("Please choose a timer of seven days or less.")
                return
            }
        }

        if (!foundDuration) {
            val simpleNumber = Regex("\\b\\d+\\b")
                .find(command)?.value?.toLongOrNull()

            if (simpleNumber != null) {
                if (simpleNumber > 604800L / 60L) {
                    speak("Please choose a timer of seven days or less.")
                    return
                }

                totalSeconds = simpleNumber * 60L
                foundDuration = true
            }
        }

        if (!foundDuration || totalSeconds <= 0L) {
            speak("Please tell me how long to set the timer, for example ten minutes or one minute thirty seconds.")
            return
        }

        if (totalSeconds > 604800L) {
            speak("Please choose a timer of seven days or less.")
            return
        }

        timerRunnable?.let {
            handler.removeCallbacks(it)
        }

        timerEndTimeMillis =
            System.currentTimeMillis() + totalSeconds * 1000L

        timerRunnable = Runnable {
            if (isRunning) {
                timerRunnable = null
                timerEndTimeMillis = null
                speak("Your timer is finished.")
            }
        }

        handler.postDelayed(
            timerRunnable!!,
            totalSeconds * 1000L
        )

        speak("Timer started.")
    }

    private fun tellTimerRemaining() {
        val endTime = timerEndTimeMillis

        if (endTime == null) {
            speak("There is no active timer.")
            return
        }

        val remainingMillis = endTime - System.currentTimeMillis()

        if (remainingMillis <= 0L) {
            timerEndTimeMillis = null
            speak("Your timer has finished.")
            return
        }

        val remainingSeconds =
            (remainingMillis + 999L) / 1000L

        val hours = remainingSeconds / 3600L
        val minutes = (remainingSeconds % 3600L) / 60L
        val seconds = remainingSeconds % 60L

        val parts = mutableListOf<String>()

        if (hours > 0) {
            parts.add("$hours ${if (hours == 1L) "hour" else "hours"}")
        }

        if (minutes > 0) {
            parts.add("$minutes ${if (minutes == 1L) "minute" else "minutes"}")
        }

        if (seconds > 0) {
            parts.add("$seconds ${if (seconds == 1L) "second" else "seconds"}")
        }

        speak("Your timer has ${parts.joinToString(" and ")} remaining.")
    }

    // CONTACTS AND CALLING

    private fun makeCall(name: String) {
        if (name.isBlank()) {
            speak("Please say the contact name.")
            return
        }

        val contact = findContact(name)

        if (contact == null) {
            speak("I couldn't find that contact.")
            return
        }

        try {
            val intent = Intent(
                Intent.ACTION_DIAL,
                Uri.parse("tel:${contact.number}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            startActivity(intent)
            speak("Opening the phone for ${contact.name}.")
        } catch (_: Exception) {
            speak("I couldn't open the phone.")
        }
    }

    // MATCH A FULL CONTACT NAME AT THE START OF AN SMS REQUEST

    private fun findContactAndMessage(
        details: String
    ): Pair<ContactMatch, String>? {

        val contacts = getPhoneContacts() ?: return null

        val spokenWords = details.trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        if (spokenWords.isEmpty()) return null

        val matches = mutableListOf<Pair<ContactMatch, String>>()

        for (contact in contacts) {
            val contactWords = normaliseContactName(contact.name)
                .split(" ")
                .filter { it.isNotBlank() }

            if (contactWords.isEmpty() ||
                contactWords.size > spokenWords.size
            ) {
                continue
            }

            val spokenNameWords = spokenWords
                .take(contactWords.size)
                .map { normaliseContactName(it) }

            if (spokenNameWords == contactWords) {
                val message = spokenWords
                    .drop(contactWords.size)
                    .joinToString(" ")
                    .trim()

                matches.add(contact to message)
            }
        }

        return matches.maxByOrNull {
            normaliseContactName(it.first.name).length
        }
    }

    private fun sendText(details: String) {
        if (details.isBlank()) {
            speak("Please say the contact name and message.")
            return
        }

        val match = findContactAndMessage(details)

        if (match == null) {
            speak("I couldn't match the contact name. Please say the full contact name followed by your message.")
            return
        }

        val contact = match.first
        val message = match.second

        if (message.isBlank()) {
            speak("What message would you like to send to ${contact.name}?")
            return
        }

        try {
            val intent = Intent(
                Intent.ACTION_SENDTO,
                Uri.parse("smsto:${contact.number}")
            ).apply {
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            startActivity(intent)
            speak("Opening your messages for ${contact.name}. Please review and send your message.")
        } catch (_: Exception) {
            speak("I couldn't open your messages.")
        }
    }

    // SETTINGS

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

    // APP SEARCH

    private fun normaliseAppName(name: String): String {
        return name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9]"), "")
    }

    private fun cleanRequestedAppName(name: String): String {
        return name.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("please open ")
            .removePrefix("open ")
            .removeSuffix(" application")
            .removeSuffix(" app")
            .trim()
    }

    private fun knownAppPackages(): Map<String, String> {
        return mapOf(
            "youtube" to "com.google.android.youtube",
            "youtube music" to "com.google.android.apps.youtube.music",
            "facebook" to "com.facebook.katana",
            "whatsapp" to "com.whatsapp",
            "whatsapp business" to "com.whatsapp.w4b",
            "instagram" to "com.instagram.android",
            "tiktok" to "com.zhiliaoapp.musically",
            "chrome" to "com.android.chrome",
            "google chrome" to "com.android.chrome",
            "gmail" to "com.google.android.gm",
            "google maps" to "com.google.android.apps.maps",
            "maps" to "com.google.android.apps.maps",
            "google photos" to "com.google.android.apps.photos",
            "photos" to "com.google.android.apps.photos",
            "play store" to "com.android.vending",
            "google play store" to "com.android.vending",
            "google" to "com.google.android.googlequicksearchbox",
            "messenger" to "com.facebook.orca",
            "spotify" to "com.spotify.music",
            "netflix" to "com.netflix.mediaclient",
            "snapchat" to "com.snapchat.android",
            "telegram" to "org.telegram.messenger",
            "twitter" to "com.twitter.android",
            "samsung internet" to "com.sec.android.app.sbrowser",
            "internet" to "com.sec.android.app.sbrowser",
            "samsung notes" to NOTES_PACKAGE,
            "notes" to NOTES_PACKAGE,
            "voice recorder" to URECORDER_PACKAGE,
            "urecorder" to URECORDER_PACKAGE,
            "calculator" to "com.sec.android.app.popupcalculator",
            "clock" to "com.sec.android.app.clockpackage",
            "gallery" to "com.sec.android.gallery3d",
            "phone" to "com.samsung.android.dialer",
            "contacts" to "com.samsung.android.contacts",
            "calendar" to "com.samsung.android.calendar",
            "my files" to "com.sec.android.app.myfiles",
            "files" to "com.sec.android.app.myfiles",
            "camera" to "com.sec.android.app.camera"
        )
    }

    private fun getLaunchableApps(): List<LaunchableApp> {
        val apps = mutableListOf<LaunchableApp>()

        try {
            val launcherIntent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val activities: List<ResolveInfo> =
                packageManager.queryIntentActivities(launcherIntent, 0)

            for (resolveInfo in activities) {
                val activityInfo = resolveInfo.activityInfo ?: continue
                val packageName = activityInfo.packageName

                val label = try {
                    resolveInfo.loadLabel(packageManager).toString()
                } catch (_: Exception) {
                    packageName
                }

                if (label.isNotBlank() &&
                    apps.none { it.packageName == packageName }
                ) {
                    apps.add(LaunchableApp(label, packageName))
                }
            }
        } catch (e: Exception) {
            showMessage("Could not search installed apps: ${e.message}")
        }

        return apps
    }

    private fun findLaunchableApp(requestedName: String): LaunchableApp? {
        val requested = cleanRequestedAppName(requestedName)
        val requestedNormal = normaliseAppName(requested)

        if (requestedNormal.isBlank()) return null

        val apps = getLaunchableApps()
        val knownPackage = knownAppPackages()[requested]

        if (knownPackage != null) {
            apps.firstOrNull {
                it.packageName == knownPackage
            }?.let {
                return it
            }

            try {
                val appInfo = packageManager.getApplicationInfo(
                    knownPackage,
                    0
                )

                if (packageManager.getLaunchIntentForPackage(knownPackage) != null) {
                    return LaunchableApp(
                        packageManager.getApplicationLabel(appInfo).toString(),
                        knownPackage
                    )
                }
            } catch (_: Exception) {
            }
        }

        apps.firstOrNull {
            normaliseAppName(it.label) == requestedNormal
        }?.let { return it }

        apps.firstOrNull {
            normaliseAppName(it.packageName) == requestedNormal
        }?.let { return it }

        apps.firstOrNull {
            normaliseAppName(it.label).startsWith(requestedNormal)
        }?.let { return it }

        apps.firstOrNull {
            normaliseAppName(it.label).contains(requestedNormal)
        }?.let { return it }

        apps.firstOrNull {
            requestedNormal.contains(normaliseAppName(it.label)) &&
                normaliseAppName(it.label).length >= 4
        }?.let { return it }

        return null
    }

    private fun openNamedApp(name: String) {
        val cleanedName = cleanRequestedAppName(name)

        if (cleanedName.isBlank()) {
            speak("Please tell me which app to open.")
            return
        }

        val app = findLaunchableApp(cleanedName)

        if (app == null) {
            speak("I couldn't find $cleanedName on your phone.")
            showMessage(
                "SIMMY could not find $cleanedName. Check that the app is installed."
            )
            return
        }

        speak("Opening ${app.label}.")
        openApp(app.packageName, app.label)
    }

    private fun openApp(
        packageName: String,
        appLabel: String
    ) {
        try {
            val launchIntent =
                packageManager.getLaunchIntentForPackage(packageName)

            if (launchIntent == null) {
                speak("I found $appLabel, but it has no opening screen.")
                return
            }

            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            )

            val overlayAllowed =
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                    Settings.canDrawOverlays(this)

            if (overlayAllowed && overlayView != null) {
                try {
                    startActivity(launchIntent)
                    return
                } catch (_: Exception) {
                }
            }

            showAppLaunchNotification(packageName, appLabel)

            if (!overlayAllowed) {
                speak("Please allow SIMMY to display over other apps.")
                showOverlayPermissionNotification()
            } else {
                speak("Please tap the notification to open $appLabel.")
            }
        } catch (_: Exception) {
            speak("I couldn't open $appLabel.")
            showAppLaunchNotification(packageName, appLabel)
        }
    }

    // CLEANUP

    override fun onDestroy() {
        isRunning = false
        awaitingCommand = false
        awaitingNoteContent = false
        modelLoading = false
        speaking = false
        ttsReady = false
        listeningStarted = false
        onlineRecognitionActive = false
        startOnlineRecognitionAfterSpeech = false
        recoveryScheduled = false
        conversationMode = false

        conversationTimeoutRunnable?.let {
            handler.removeCallbacks(it)
        }
        conversationTimeoutRunnable = null

        handler.removeCallbacks(overlayPermissionChecker)

        timerRunnable?.let {
            handler.removeCallbacks(it)
        }

        timerRunnable = null
        timerEndTimeMillis = null

        removeSimmyOverlay()
        handler.removeCallbacksAndMessages(null)
        executor.shutdownNow()

        releaseWakeLock()

        try {
            destroyOnlineRecognizer()

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
