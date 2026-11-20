
package com.simmy.assistant

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Button

class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(12, 18, 30))
        }

        val title = TextView(this).apply {
            text = "SIMMY 6.0"
            textSize = 34f
            setTextColor(Color.CYAN)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Hey! Ek is Simmy."
            textSize = 23f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val status = TextView(this).apply {
            text = "Your personal AI assistant"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }

        val mic = Button(this).apply {
            text = "🎙  TALK TO SIMMY"
            textSize = 19f
        }

        mic.setOnClickListener {
            status.text = "Ek luister... I'm listening!"
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(status)

        layout.addView(mic)

        setContentView(layout)
    }
}
