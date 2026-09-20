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
    @Test public void latestSessionCanBeCopiedWithoutASystemCrashRecord() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RunLog.reset(activity, "[session] match=3 cleanup complete");
        RunLog.append(activity, "[memdiag] rss=512000KB");
        CrashReport.showLatest(activity);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        String text = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
        assertTrue(text.contains("match=3 cleanup complete"));
        assertTrue(text.contains("rss=512000KB"));
        assertTrue(text.contains("dispositivo:"));
        assertTrue(text.contains("version:"));
        assertFalse(text.contains("diagnostico: CRASH_NATIVE"));
        dialog.dismiss();
    }

    @Test public void missingLogStillProducesAReport() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        RunLog.file(activity).delete();
        CrashReport.showLatest(activity);
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertTrue(clipboard.getPrimaryClip().getItemAt(0).getText().toString().contains("(vacio)"));
        dialog.dismiss();
    }
}
