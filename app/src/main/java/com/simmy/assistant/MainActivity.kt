
package com.simmy.assistant

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.ComponentActivity

class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var face: ImageView
    private var faceAnimator: AnimatorSet? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        createInterface()
        requestNeededPermissions()
    }

    private fun createInterface() {
        root = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        face = ImageView(this).apply {
            setImageResource(R.drawable.simmy_face)
            scaleType = ImageView.ScaleType.FIT_XY
            contentDescription = "SIMMY"
        }

        root.addView(
            face,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        val centreButton = View(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true

            setOnClickListener {
                toggleBackgroundListening()
            }
        }

        val buttonSize = (190 * resources.displayMetrics.density).toInt()

        root.addView(
            centreButton,
            FrameLayout.LayoutParams(
                buttonSize,
                buttonSize,
                Gravity.CENTER
            )
        )

        setContentView(root)
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CONTACTS
        )

        if (Build.VERSION.SDK_INT >= 33) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missing = permissions.filter {
            checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 100)
        }
    }

    private fun toggleBackgroundListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )
            return
        }

        if (BackgroundWakeService.isRunning) {
            stopService(Intent(this, BackgroundWakeService::class.java))
            stopFaceAnimation()
            Toast.makeText(this, "SIMMY stopped listening", Toast.LENGTH_SHORT).show()
        } else {
            val intent = Intent(this, BackgroundWakeService::class.java).apply {
                action = BackgroundWakeService.ACTION_START
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(intent)
                } else {
                    startService(intent)
                }

                animateFace()
                Toast.makeText(this, "SIMMY is starting...", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                stopFaceAnimation()
                Toast.makeText(
                    this,
                    "Could not start background listening: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun animateFace() {
        stopFaceAnimation()

        val growX = ObjectAnimator.ofFloat(face, View.SCALE_X, 1f, 1.025f)
        val growY = ObjectAnimator.ofFloat(face, View.SCALE_Y, 1f, 1.025f)

        faceAnimator = AnimatorSet().apply {
            playTogether(growX, growY)
            duration = 650
            interpolator = AccelerateDecelerateInterpolator()

            growX.repeatCount = ObjectAnimator.INFINITE
            growY.repeatCount = ObjectAnimator.INFINITE
            growX.repeatMode = ObjectAnimator.REVERSE
            growY.repeatMode = ObjectAnimator.REVERSE

            start()
        }
    }

    private fun stopFaceAnimation() {
        faceAnimator?.cancel()
        faceAnimator = null

        if (::face.isInitialized) {
            face.scaleX = 1f
            face.scaleY = 1f
            face.alpha = 1f
        }
    }

    override fun onResume() {
        super.onResume()

        if (BackgroundWakeService.isRunning) {
            if (faceAnimator == null) animateFace()
        } else {
            stopFaceAnimation()
        }
    }

    override fun onDestroy() {
        stopFaceAnimation()
        super.onDestroy()
    }
}
