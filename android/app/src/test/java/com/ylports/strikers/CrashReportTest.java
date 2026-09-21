package com.ylports.strikers;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Looper;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {26, 35}, application = Application.class)
public class CrashReportTest {
    private static AlertDialog awaitDialog() throws Exception {
        long deadline = System.nanoTime() + 5_000_000_000L;
        do {
            shadowOf(Looper.getMainLooper()).idle();
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            if (dialog != null && dialog.isShowing()) return dialog;
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Report reader did not display a dialog");
    }

    @Test public void latestSessionCanBeCopiedWithoutASystemCrashRecord() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RunLog.reset(activity, "[session] match=3 cleanup complete");
        RunLog.append(activity, "[memdiag] rss=512000KB");
        SessionDiagnostics.append(activity, "frame=24000 phase=2 present_age_ms=12000");
        CrashReport.showLatest(activity);
        AlertDialog dialog = awaitDialog();
        assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        String text = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
        assertTrue(text.contains("match=3 cleanup complete"));
        assertTrue(text.contains("rss=512000KB"));
        assertTrue(text.contains("frame=24000 phase=2 present_age_ms=12000"));
        assertTrue(text.contains("dispositivo:"));
        assertTrue(text.contains("version del informe:"));
        assertTrue(text.contains("session_version="));
        assertFalse(text.contains("diagnostico: CRASH_NATIVE"));
        dialog.dismiss();
    }

    @Test public void missingLogStillProducesAReport() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RunLog.file(activity).delete();
        CrashReport.showLatest(activity);
        AlertDialog dialog = awaitDialog();
        assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertTrue(clipboard.getPrimaryClip().getItemAt(0).getText().toString().contains("(vacio)"));
        dialog.dismiss();
    }
}
