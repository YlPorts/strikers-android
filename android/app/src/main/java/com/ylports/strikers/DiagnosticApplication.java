package com.ylports.strikers;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.system.Os;

/** Keeps the launcher alive and surfaces the most recent :game process crash. */
public final class DiagnosticApplication extends Application
        implements Application.ActivityLifecycleCallbacks {

    @Override
    public void onCreate() {
        super.onCreate();
        registerActivityLifecycleCallbacks(this);

        // This Application is created independently in the launcher and :game
        // processes. Exporting the diagnostic switches here means the native
        // core sees them before SDL/libstrikers is loaded.
        try {
            Os.setenv("STRIKERS_DIAGNOSTIC_BUILD", "1", true);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (activity instanceof MainActivity) {
            CrashReport.scheduleCheck(activity);
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {}
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
