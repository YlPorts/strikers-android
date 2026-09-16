package com.ylports.strikers;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.util.Date;
import java.util.List;

final class CrashReport {
    private static final String PREF_LAST_EXIT_SHOWN = "diag_last_exit_shown";
    private static final String PREF_LAST_LOG_MTIME_SHOWN = "diag_last_log_mtime_shown";
    private static final int MAX_LOG_BYTES = 48 * 1024;

    private CrashReport() {
    }

    static void scheduleCheck(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 30) {
            return;
        }
        Handler handler = new Handler(Looper.getMainLooper());
        handler.postDelayed(() -> showIfNeeded(activity), 500L);
        handler.postDelayed(() -> showIfNeeded(activity), 1800L);
    }

    private static void showIfNeeded(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }

        SharedPreferences prefs = activity.getSharedPreferences(MainActivity.PREFS, Context.MODE_PRIVATE);
        long lastExitShown = prefs.getLong(PREF_LAST_EXIT_SHOWN, 0L);
        long lastLogMtimeShown = prefs.getLong(PREF_LAST_LOG_MTIME_SHOWN, 0L);

        ApplicationExitInfo latest = findLatestGameExit(activity);
        File logFile = RunLog.file(activity);
        long logMtime = logFile.exists() ? logFile.lastModified() : 0L;
        String log = readTail(logFile, MAX_LOG_BYTES);
        boolean logContainsNativeCrash = log.contains("STRIKERS NATIVE CRASH")
                || log.contains("early native load crash")
                || log.contains("JAVA CRASH");

        boolean newSystemCrash = latest != null
                && latest.getTimestamp() > lastExitShown
                && isInterestingExit(latest.getReason());
        boolean newLogCrash = logContainsNativeCrash && logMtime > lastLogMtimeShown;

        if (!newSystemCrash && !newLogCrash) {
            return;
        }

        long exitTimestamp = latest != null ? latest.getTimestamp() : 0L;
        String report = buildReport(latest, log);
        prefs.edit()
                .putLong(PREF_LAST_EXIT_SHOWN, Math.max(lastExitShown, exitTimestamp))
                .putLong(PREF_LAST_LOG_MTIME_SHOWN, Math.max(lastLogMtimeShown, logMtime))
                .apply();

        showDialog(activity, report);
    }

    private static ApplicationExitInfo findLatestGameExit(Context context) {
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return null;
        }
        try {
            List<ApplicationExitInfo> exits = manager.getHistoricalProcessExitReasons(
                    context.getPackageName(), 0, 16);
            String wanted = context.getPackageName() + ":game";
            ApplicationExitInfo latest = null;
            for (ApplicationExitInfo info : exits) {
                if (info == null || !wanted.equals(info.getProcessName())) {
                    continue;
                }
                if (latest == null || info.getTimestamp() > latest.getTimestamp()) {
                    latest = info;
                }
            }
            return latest;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isInterestingExit(int reason) {
        return reason == ApplicationExitInfo.REASON_CRASH_NATIVE
                || reason == ApplicationExitInfo.REASON_CRASH
                || reason == ApplicationExitInfo.REASON_SIGNALED
                || reason == ApplicationExitInfo.REASON_ANR
                || reason == ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
                || reason == ApplicationExitInfo.REASON_LOW_MEMORY;
    }

    private static String buildReport(ApplicationExitInfo exit, String log) {
        StringBuilder out = new StringBuilder(32768);
        out.append("STRIKERS ANDROID - INFORME DE CIERRE\n");
        out.append("Copia este informe completo y envialo en el chat.\n\n");

        if (exit != null) {
            out.append("=== Android ApplicationExitInfo ===\n");
            out.append("proceso: ").append(exit.getProcessName()).append('\n');
            out.append("pid: ").append(exit.getPid()).append('\n');
            out.append("fecha: ").append(DateFormat.getDateTimeInstance().format(new Date(exit.getTimestamp()))).append('\n');
            out.append("reason: ").append(reasonName(exit.getReason()))
                    .append(" (").append(exit.getReason()).append(")\n");
            out.append("status/signal: ").append(exit.getStatus()).append('\n');
            out.append("descripcion: ").append(exit.getDescription() == null ? "(sin descripcion)" : exit.getDescription()).append('\n');
            out.append("rss_kb: ").append(exit.getRss()).append('\n');
            out.append("pss_kb: ").append(exit.getPss()).append('\n');
            if (exit.getReason() == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                out.append("diagnostico: CRASH_NATIVE - fallo dentro del codigo C/C++ nativo.\n");
            } else if (exit.getReason() == ApplicationExitInfo.REASON_SIGNALED) {
                out.append("diagnostico: proceso terminado por una senal del sistema; mira status/signal.\n");
            } else if (exit.getReason() == ApplicationExitInfo.REASON_LOW_MEMORY) {
                out.append("diagnostico: Android termino el proceso por presion de memoria.\n");
            }
            out.append('\n');
        } else {
            out.append("=== Android ApplicationExitInfo ===\n");
            out.append("Aun no disponible; usando el registro nativo persistente.\n\n");
        }

        out.append("=== last-run.log (cola) ===\n");
        if (log == null || log.isEmpty()) {
            out.append("(vacio)\n");
        } else {
            out.append(log);
            if (!log.endsWith("\n")) {
                out.append('\n');
            }
        }
        out.append("=== fin del informe ===\n");
        return out.toString();
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "EXIT_SELF";
            case ApplicationExitInfo.REASON_SIGNALED: return "SIGNALED";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "LOW_MEMORY";
            case ApplicationExitInfo.REASON_CRASH: return "CRASH_JAVA";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "CRASH_NATIVE";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "INITIALIZATION_FAILURE";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "PERMISSION_CHANGE";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "EXCESSIVE_RESOURCE_USAGE";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "USER_REQUESTED";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "DEPENDENCY_DIED";
            case ApplicationExitInfo.REASON_OTHER: return "OTHER";
            default: return "UNKNOWN";
        }
    }

    private static String readTail(File file, int maxBytes) {
        if (file == null || !file.isFile()) {
            return "";
        }
        try (FileInputStream in = new FileInputStream(file)) {
            long length = file.length();
            long skip = Math.max(0L, length - maxBytes);
            while (skip > 0) {
                long moved = in.skip(skip);
                if (moved <= 0) {
                    break;
                }
                skip -= moved;
            }
            byte[] data = new byte[(int) Math.min((long) maxBytes, Math.max(0L, length))];
            int total = 0;
            while (total < data.length) {
                int count = in.read(data, total, data.length - total);
                if (count <= 0) {
                    break;
                }
                total += count;
            }
            return new String(data, 0, total, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    private static void showDialog(Activity activity, String report) {
        ScrollView scroll = new ScrollView(activity);
        TextView text = new TextView(activity);
        text.setText(report);
        text.setTextIsSelectable(true);
        text.setTypeface(Typeface.MONOSPACE);
        text.setTextSize(11f);
        int pad = Math.round(16f * activity.getResources().getDisplayMetrics().density);
        text.setPadding(pad, pad, pad, pad);
        scroll.addView(text, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("El juego se cerro - informe de diagnostico")
                .setView(scroll)
                .setPositiveButton("Copiar informe", (d, which) -> {
                    ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("Strikers crash report", report));
                        Toast.makeText(activity, "Informe copiado.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cerrar", null)
                .create();
        dialog.show();
    }
}
