package com.ylports.strikers;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

/** Sparse background samples survive a frozen render thread or an Android process kill. */
final class SessionDiagnostics {
    static final String FILE_NAME = "session-performance.log";
    static final int MAX_FILE_BYTES = 32 * 1024;
    private static final long INTERVAL_MS = 10_000;
    private static final String PROCESS_TAG = " pid=" + Process.myPid();
    private final Context context;
    private final Supplier<String> snapshot;
    private final HandlerThread thread;
    private final Handler handler;
    private volatile boolean resumed;
    private volatile boolean stopped;

    SessionDiagnostics(Context context, Supplier<String> snapshot) {
        this.context = context.getApplicationContext();
        this.snapshot = snapshot;
        thread = new HandlerThread("Strikers session diagnostics", Process.THREAD_PRIORITY_BACKGROUND);
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(() -> {
            try (FileOutputStream out = new FileOutputStream(file(this.context))) {
                out.write(("pid=" + Process.myPid() + " device=" + Build.MODEL
                        + " version=" + RunLog.version(this.context)
                        + " phase: 0=between frames, 1=frame slot, 2=staging, 3=recording, 4=FIFO drain,"
                        + " 5=cleanup, 6=finish, 7=overlay, 8=enqueue\n"
                        + "render: (frame_id << 8) | stage; 0=idle, 1=begin, 2=draw, 3=submit, 4=sync\n"
                        + "fifo_stage: 0=idle, 1=buffer lock, 2=commands, 3=draw-done callback\n"
                        + "*_max: peak since previous sample, not necessarily the same frame;"
                        + " cpu_frame includes game/limiter/FIFO time, not GPU execution time\n")
                        .getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) { }
            sample.run();
        });
    }

    void setResumed(boolean value) { resumed = value; }

    void stop() {
        stopped = true;
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    private final Runnable sample = new Runnable() {
        @Override public void run() {
            if (stopped) return;
            if (resumed) {
                try {
                    // Native snapshot reads atomics only; it cannot queue work on a stuck renderer.
                    append(context, snapshot.get() + " " + deviceState(context));
                } catch (RuntimeException | LinkageError ignored) { }
            }
            if (!stopped) handler.postDelayed(this, INTERVAL_MS);
        }
    };

    static File file(Context context) { return new File(context.getFilesDir(), FILE_NAME); }

    static void append(Context context, String sample) {
        // Use the same sleep-excluding clock as native CLOCK_MONOTONIC. Include
        // PID per sample because rotation/report tails can remove the header.
        byte[] bytes = (SystemClock.uptimeMillis() + "ms clock=monotonic" + PROCESS_TAG
                + " " + sample + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 2048) return;
        File file = file(context);
        // A rolling, bounded diagnostic file. No fsync or disk I/O on the UI/render/game threads.
        boolean keep = file.length() + bytes.length <= MAX_FILE_BYTES;
        try (FileOutputStream out = new FileOutputStream(file, keep)) {
            out.write(bytes);
        } catch (IOException ignored) { }
    }

    static String deviceState(Context context) {
        int thermal = -1;
        if (Build.VERSION.SDK_INT >= 29) {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (power != null) thermal = power.getCurrentThermalStatus();
        }
        Intent battery = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        int temperature = battery == null ? -1 : battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        if (manager != null) manager.getMemoryInfo(memory);
        return "thermal_status=" + thermal + " battery_tenths_C=" + temperature
                + " available_mb=" + (memory.availMem / 1048576) + " low_memory=" + memory.lowMemory;
    }
}
