
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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
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

        private const val NOTES_PACKAGE = "com.samsung.android.app.notes"
        private const val URECORDER_PACKAGE =
            "voicerecorder.audiorecorder.voice"

        @Volatile
        var isRunning = false
    }

    private data class LaunchableApp(
        val label: String,
        val packageName: String
    )

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speechService: SpeechService? = null
    private var textToSpeech: TextToSpeech? = null

    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null
    private var overlayPermissionNotificationShown = false

    @Volatile
    private var modelLoading = false

    private var ttsReady = false
    private var awaitingCommand = false
    private var awaitingNoteContent = false
    private var listeningStarted = false
    private var readyMessageSpoken = false
    private var speaking = false
    private var timerRunnable: Runnable? = null

    private var lastProcessedText = ""
    private var lastProcessedTime = 0L

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

    // FLOATING SIMMY INDICATOR

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
            val bubble = TextView(this).apply {
                text = "SIMMY • Listening"
                setTextColor(Color.WHITE)
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(18, 12, 18, 12)

                background = GradientDrawable().apply {
                    setColor(Color.rgb(35, 65, 115))
                    cornerRadius = 40f
                    setStroke(2, Color.WHITE)
                }

                elevation = 8f

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
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
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
            updateNotification("Listening for Hi, Make a note, or Start recording.")

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

    // VOSK LISTENING

    private fun startVoskListening() {
        if (!isRunning) return

        try {
            val loadedModel = model ?: return

            recognizer = Recognizer(loadedModel, 16000.0f)
            speechService = SpeechService(recognizer, 16000.0f)
            speechService?.startListening(this)

            listeningStarted = true
            updateNotification("Listening for Hi, Make a note, or Start recording.")
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
        speak("SIMMY is ready. Say Hi, Make a note, or Start recording.")
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
            updateNotification("SIMMY is listening.")
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
        val normalised = words
            .replace(Regex("[^a-z0-9 ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

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

        if (normalised == "hi" || normalised.startsWith("hi ")) {
            val command = normalised
                .removePrefix("hi")
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

    // VOICE COMMANDS

    private fun executeCommand(command: String) {
        val text = command.lowercase(Locale.ROOT).trim()

        when {
            text.contains("make a note") -> {
                startMakingNote()
            }

            text.contains("start recording") -> {
                startVoiceRecording()
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

            text.contains("wifi") ||
                text.contains("wi fi") -> {
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

            text.contains("settings") -> {
                speak("Opening settings.")
                openSettings(Settings.ACTION_SETTINGS)
            }

            text.contains("camera") -> {
                openNamedApp("camera")
            }

            text.contains("youtube") -> {
                openNamedApp("youtube")
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
                openNamedApp("whatsapp")
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

    // TIMER

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

    // CONTACTS AND CALLING

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
                // Continue searching installed apps.
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
                    // Use notification fallback.
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

        handler.removeCallbacks(overlayPermissionChecker)

        timerRunnable?.let {
            handler.removeCallbacks(it)
        }

        removeSimmyOverlay()
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
