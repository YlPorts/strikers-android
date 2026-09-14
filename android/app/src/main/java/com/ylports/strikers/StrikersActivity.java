package com.ylports.strikers;

import android.content.Context;

import dev.encounter.aurora.AuroraSurface;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/** Runs the native Strikers port through SDL3 and Aurora. */
public final class StrikersActivity extends SDLActivity {
    @Override
    protected String[] getLibraries() {
        // SDL3 is linked statically into libstrikers.so by Aurora, so loading the
        // game library also runs SDL's JNI_OnLoad and registers SDLActivity natives.
        return new String[] { "strikers" };
    }

    @Override
    protected SDLSurface createSDLSurface(Context context) {
        return new AuroraSurface(context);
    }
}
