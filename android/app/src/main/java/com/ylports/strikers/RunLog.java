package com.ylports.strikers;

import android.content.Context;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;

/** Small, fsync-backed startup trace shared by the launcher and :game process. */
public final class RunLog {
    public static final String FILE_NAME = "last-run.log";

    private static final Object LOCK = new Object();
    private static boolean crashHandlerInstalled;

    private RunLog() {
    }

    public static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }

    public static void reset(Context context, String message) {
        write(context, "session_version=" + version(context) + " " + message, false);
    }

    static String version(Context context) {
        if (context == null) return "unknown";
        try {
            return context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return "unknown";
        }
    }

    public static void append(Context context, String message) {
        write(context, message, true);
    }

    private static void write(Context context, String message, boolean append) {
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            try (FileOutputStream out = new FileOutputStream(file(context), append)) {
                String line = SystemClock.elapsedRealtime()
                        + "ms pid=" + Process.myPid()
                        + " tid=" + Process.myTid()
                        + " " + message + "\n";
                out.write(line.getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            } catch (Throwable ignored) {
                // Diagnostics must never become another reason for the app to fail.
            }
        }
    }

    public static synchronized void installJavaCrashHandler(Context context) {
        if (crashHandlerInstalled) {
            return;
        }
        crashHandlerInstalled = true;
        final Context appContext = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                error.printStackTrace(pw);
                pw.flush();
                String trace = sw.toString();
                if (trace.length() > 12000) {
                    trace = trace.substring(0, 12000);
                }
                append(appContext, "JAVA CRASH on " + thread.getName() + ":\n" + trace);
            } catch (Throwable ignored) {
            }
            if (previous != null) {
                previous.uncaughtException(thread, error);
            }
        });
    }
}
