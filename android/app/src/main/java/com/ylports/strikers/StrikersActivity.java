package com.ylports.strikers;

import android.content.Context;
import android.os.Bundle;

import dev.encounter.aurora.AuroraSurface;
import org.libsdl.app.SDLActivity;
import org.libsdl.app.SDLSurface;

/** Runs the native Strikers port through SDL3 and Aurora. */
public final class StrikersActivity extends SDLActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        RunLog.installJavaCrashHandler(this);
        RunLog.append(this, "SDL activity: onCreate BEFORE SDLActivity.onCreate");
        super.onCreate(savedInstanceState);
        RunLog.append(this, "SDL activity: onCreate AFTER SDLActivity.onCreate");
    }

    @Override
    protected void onStart() {
        RunLog.append(this, "SDL activity: onStart BEFORE super");
        super.onStart();
        RunLog.append(this, "SDL activity: onStart AFTER super");
    }

    @Override
    protected void onResume() {
        RunLog.append(this, "SDL activity: onResume BEFORE super");
        super.onResume();
        RunLog.append(this, "SDL activity: onResume AFTER super");
    }

    @Override
    protected void onPause() {
        RunLog.append(this, "SDL activity: onPause BEFORE super");
        super.onPause();
        RunLog.append(this, "SDL activity: onPause AFTER super");
    }

    @Override
    protected void onStop() {
        RunLog.append(this, "SDL activity: onStop BEFORE super");
        super.onStop();
        RunLog.append(this, "SDL activity: onStop AFTER super");
    }

    @Override
    protected void onDestroy() {
        RunLog.append(this, "SDL activity: onDestroy BEFORE super");
        super.onDestroy();
        RunLog.append(this, "SDL activity: onDestroy AFTER super");
    }

    @Override
    protected String[] getLibraries() {
        // Match SDL3's Android project layout: SDL's JNI runtime is loaded first,
        // then SDLActivity runs SDL_main from the Strikers library.
        RunLog.append(this, "SDL activity: getLibraries -> SDL3, strikers");
        return new String[] { "SDL3", "strikers" };
    }

    @Override
    protected SDLSurface createSDLSurface(Context context) {
        RunLog.append(context, "SDL activity: createSDLSurface -> AuroraSurface");
        return new AuroraSurface(context);
    }
}
