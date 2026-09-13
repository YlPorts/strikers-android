package dev.ylports.strikers

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.TextView

class MainActivity : Activity() {
    external fun nativeStatus(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )

        val status = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 18f
            text = nativeStatus()
        }
        setContentView(status)
    }

    companion object {
        init {
            System.loadLibrary("strikers_android")
        }
    }
}
