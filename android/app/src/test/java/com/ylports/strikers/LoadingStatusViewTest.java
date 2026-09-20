package com.ylports.strikers;

import android.app.Activity;
import android.app.Application;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.time.Duration;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class LoadingStatusViewTest {
    private LoadingStatusView view;
    private FrameLayout root;
    private int status;
    private int reads;

    @Before public void setup() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        root = new FrameLayout(activity);
        activity.setContentView(root);
        view = new LoadingStatusView(activity, () -> { reads++; return status; });
        root.addView(view);
        view.start();
        shadowOf(Looper.getMainLooper()).idle();
    }

    private void tick() {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(250));
    }

    @Test public void fastLoadsDoNotFlashAnOverlay() {
        status = 1;
        tick();
        assertEquals(View.GONE, view.getVisibility());
        status = 0;
        tick();
        assertEquals(View.GONE, view.getVisibility());
        view.stop();
    }

    @Test public void slowLoadAndGraphicsWaitClearWhenNativeFinishes() {
        status = 1;
        tick();
        tick();
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals("Cargando partido…", view.getText().toString());
        assertFalse(view.isClickable());
        status = 3;
        tick();
        assertEquals("Preparando gráficos…", view.getText().toString());
        status = 0;
        tick();
        assertEquals(View.GONE, view.getVisibility());
        view.stop();
    }

    @Test public void pauseAndDetachStopNativePollingAndResumeRestartsOnce() {
        status = 2;
        tick();
        tick();
        view.stop();
        int stoppedReads = reads;
        tick();
        tick();
        assertEquals(stoppedReads, reads);
        assertEquals(View.GONE, view.getVisibility());
        view.start();
        view.start();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(stoppedReads + 1, reads);
        tick();
        assertEquals(View.VISIBLE, view.getVisibility());
        root.removeView(view);
        stoppedReads = reads;
        tick();
        assertEquals(stoppedReads, reads);
        assertEquals(View.GONE, view.getVisibility());
    }
}
