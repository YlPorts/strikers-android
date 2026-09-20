package com.ylports.strikers;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.widget.TextView;

import java.util.function.IntSupplier;

/** Shows actual slow loads on the UI thread, even while native rendering waits. */
final class LoadingStatusView extends TextView {
    private static final long POLL_MS = 250L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final IntSupplier status;
    private boolean running;
    private long activeSince = -1L;

    LoadingStatusView(Context context, IntSupplier status) {
        super(context);
        this.status = status;
        setTextColor(Color.WHITE);
        setBackgroundColor(Color.argb(225, 13, 15, 19));
        setGravity(Gravity.CENTER);
        setTextSize(16f);
        int pad = Math.round(20f * getResources().getDisplayMetrics().density);
        setPadding(pad, pad, pad, pad);
        setClickable(false);
        setFocusable(false);
        setVisibility(View.GONE);
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!running) return;
            int value = status.getAsInt();
            if (value == 0) {
                activeSince = -1L;
                if (getVisibility() != View.GONE) setVisibility(View.GONE);
            } else {
                long now = SystemClock.uptimeMillis();
                if (activeSince < 0L) activeSince = now;
                // No flicker for a fast phone that finishes between two polls.
                if (now - activeSince >= POLL_MS) {
                    String message = (value & 2) != 0 ? "Preparando gráficos…" : "Cargando partido…";
                    if (!message.contentEquals(getText())) setText(message);
                    if (getVisibility() != View.VISIBLE) setVisibility(View.VISIBLE);
                }
            }
            handler.postDelayed(this, POLL_MS);
        }
    };

    void start() {
        if (running) return;
        running = true;
        handler.post(poll);
    }

    void stop() {
        running = false;
        handler.removeCallbacks(poll);
        activeSince = -1L;
        setVisibility(View.GONE);
    }

    @Override protected void onDetachedFromWindow() {
        stop();
        super.onDetachedFromWindow();
    }
}
