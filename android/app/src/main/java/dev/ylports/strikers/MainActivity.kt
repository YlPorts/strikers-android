package dev.ylports.strikers

import android.content.Context
import android.os.Bundle
import android.view.View
import dev.encounter.aurora.AuroraSurface
import org.libsdl.app.SDLActivity
import org.libsdl.app.SDLSurface

class MainActivity : SDLActivity() {
    override fun createSDLSurface(context: Context): SDLSurface {
        return AuroraSurface(context)
    }

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
    }

    override fun getLibraries(): Array<String> {
        // SDLActivity loads these in order, then invokes SDL_main from libmain.so.
        return arrayOf("SDL3", "main")
    }
}
